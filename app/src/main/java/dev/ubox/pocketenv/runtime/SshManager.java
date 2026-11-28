package dev.ubox.pocketenv.runtime;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.PowerManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * sshd 进程管理：启动/停止/守候/兜底清理，与终端会话无关。
 *
 * <p>从 TerminalService 拆出，让 Service 只负责前台通知与会话生命周期。
 * 状态变更通过 {@link Listener} 通知宿主刷新前台通知。
 */
public final class SshManager {
  static final String PREFS = "ubox";
  static final String PREF_SSH = "ssh_enabled";
  static final int SSH_PORT = 8022;

  /** sshd 状态变化（启动/停止/自行退出）后回调，宿主用于刷新通知。 */
  interface Listener {
    void onSshChanged();
  }

  private final Context context;
  private final Listener listener;
  private final PowerManager.WakeLock wakeLock;
  private final WifiManager.WifiLock wifiLock;
  // 设置页在后台线程启停 SSH，UI 线程读状态，需要 volatile 保证可见性
  private volatile Process sshProcess;

  SshManager(Context context, Listener listener) {
    this.context = context;
    this.listener = listener;
    PowerManager power = context.getSystemService(PowerManager.class);
    wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ubox:sshd");
    wakeLock.setReferenceCounted(false);
    WifiManager wifi = context.getSystemService(WifiManager.class);
    // 极少数无 Wi-Fi 的设备（如电视盒子）wifi 可能为 null
    wifiLock = wifi == null ? null
        : wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ubox:sshd");
  }

  File logFile() {
    return new File(new Rootfs(context, ActiveDistro.get(context)).base, "sshd.log");
  }

  boolean isRunning() {
    return sshProcess != null && sshProcess.isAlive();
  }

  /** 在后台（不占终端会话）运行 sshd，输出写到 sshd.log。 */
  synchronized void start() throws IOException {
    if (isRunning()) return;
    if (isPortListening()) {
      // 服务重建或应用进程被杀后，上次的 proot/sshd 可能仍占着端口
      stopProcess();
      if (isPortListening()) throw new IOException("端口 " + SSH_PORT + " 被其他程序占用");
    }
    sshProcess = UbuntuProcess.startDetached(
        context, new String[] {"/usr/local/bin/ubox-sshd", String.valueOf(SSH_PORT)}, logFile());
    acquireLocks();
    watch(sshProcess);
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(PREF_SSH, true).apply();
    listener.onSshChanged();
  }

  /** 停止 sshd；返回 8022 是否已释放，false 表示端口仍被其他程序占用。 */
  synchronized boolean stop() {
    boolean freed = stopProcess();
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(PREF_SSH, false).apply();
    listener.onSshChanged();
    return freed;
  }

  /** 仅停进程，不写偏好设置（退出应用时用）。 */
  synchronized void stopQuietly() {
    stopProcess();
  }

  /** 恢复上次会话的 SSH 开关状态（前台服务重建时）。 */
  void autostartIfNeeded() {
    if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_SSH, false)
        || !new Rootfs(context, ActiveDistro.get(context)).isInstalled()) return;
    try {
      start();
    } catch (IOException error) {
      android.util.Log.e("PocketEnv", "ssh autostart failed", error);
    }
  }

  /**
   * 结束 sshd 并等待端口释放。
   * 只按 sshProcess 句柄结束不够：前台服务重建、应用进程被系统杀掉或 proot 被 SIGKILL 后，
   * 句柄会丢失或留下孤儿 sshd，端口一直占用，下次启动就会失败，所以再按命令行兜底清理一遍。
   */
  private synchronized boolean stopProcess() {
    try {
      Process process = sshProcess;
      sshProcess = null;
      if (process != null) {
        // proot 带 --kill-on-exit，正常结束会连同 sshd 及其子进程一起结束
        process.destroy();
        try {
          if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      List<Integer> pids = sshPids();
      if (pids.isEmpty()) return !isPortListening();
      signal(pids, android.system.OsConstants.SIGTERM);
      if (awaitPortFree(1_000)) return true;
      // proot 被强杀后 guest 里的 sshd 会变成孤儿继续监听，逐个补刀
      signal(sshPids(), android.system.OsConstants.SIGKILL);
      return awaitPortFree(500);
    } finally {
      releaseLocks();
    }
  }

  /**
   * SSH 运行期间保持 CPU 与 Wi-Fi 唤醒：锁屏后设备会休眠 CPU，sshd 虽然活着却无法响应连接。
   * 与菜单里手动开启的「保持 CPU 唤醒」相互独立。
   */
  private void acquireLocks() {
    if (!wakeLock.isHeld()) wakeLock.acquire();
    if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
  }

  private void releaseLocks() {
    if (wakeLock.isHeld()) wakeLock.release();
    if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
  }

  /** sshd 自行退出（配置错误、崩溃）时释放锁并刷新通知。 */
  private void watch(Process process) {
    Thread watcher =
        new Thread(
            () -> {
              try {
                process.waitFor();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
              }
              new Handler(context.getMainLooper())
                  .post(
                      () -> {
                        if (sshProcess != process) return;
                        sshProcess = null;
                        releaseLocks();
                        listener.onSshChanged();
                      });
            },
            "ubox-sshd-watch");
    watcher.setDaemon(true);
    watcher.start();
  }

  /** /proc 里命令行含 ubox-sshd（proot 外壳）或 /usr/sbin/sshd -D（guest 内 sshd）的进程。 */
  private static List<Integer> sshPids() {
    List<Integer> pids = new ArrayList<>();
    File[] entries = new File("/proc").listFiles();
    if (entries == null) return pids;
    for (File entry : entries) {
      String name = entry.getName();
      if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
      int pid;
      try {
        pid = Integer.parseInt(name);
      } catch (NumberFormatException ignored) {
        continue;
      }
      if (pid == android.os.Process.myPid()) continue;
      String cmdline = readCmdline(new File(entry, "cmdline"));
      if (cmdline.contains("ubox-sshd")
          || (cmdline.contains("/usr/sbin/sshd") && cmdline.contains("-D"))) pids.add(pid);
    }
    return pids;
  }

  private static String readCmdline(File file) {
    try (FileInputStream in = new FileInputStream(file)) {
      // proot 的完整命令行（含各 -b 绑定）可能超过 1KB，必须整段读完才能匹配到 ubox-sshd
      java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
      byte[] chunk = new byte[1024];
      int read;
      while ((read = in.read(chunk)) > 0) {
        buffer.write(chunk, 0, read);
        if (buffer.size() >= 4096) break;
      }
      return new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)
          .replace('\0', ' ');
    } catch (IOException unreadable) {
      return "";
    }
  }

  private static void signal(List<Integer> pids, int signal) {
    for (int pid : pids) {
      try {
        android.system.Os.kill(pid, signal);
      } catch (android.system.ErrnoException gone) {
        // 进程已退出或不属于本应用
      }
    }
  }

  /** 尝试绑定 8022：绑定失败说明已有进程在监听。 */
  private static boolean isPortListening() {
    try (java.net.ServerSocket socket = new java.net.ServerSocket()) {
      // 避免刚断开的连接处于 TIME_WAIT 时误判端口仍被占用
      socket.setReuseAddress(true);
      socket.bind(new java.net.InetSocketAddress("0.0.0.0", SSH_PORT));
      return false;
    } catch (IOException inUse) {
      return true;
    }
  }

  private static boolean awaitPortFree(long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (!isPortListening()) return true;
      try {
        Thread.sleep(100);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return !isPortListening();
  }
}
