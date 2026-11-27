package dev.ubox.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 持有所有终端会话；前台服务保证切到后台后 Agent 仍在运行。 */
public class TerminalService extends Service {
  static final String ACTION_EXIT = "dev.ubox.app.EXIT";
  private static final String CHANNEL = "sessions";
  private static final int NOTIFICATION_ID = 1;

  final class LocalBinder extends Binder {
    TerminalService service() {
      return TerminalService.this;
    }
  }

  private final IBinder binder = new LocalBinder();
  private final List<TerminalSession> sessions = new ArrayList<>();
  private PowerManager.WakeLock wakeLock;
  private Runnable onExit;

  @Override
  public void onCreate() {
    super.onCreate();
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel(CHANNEL, "终端会话", NotificationManager.IMPORTANCE_LOW));
    startForeground(
        NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_SSH, false)
        && new Rootfs(this).isInstalled()) {
      try {
        startSsh();
      } catch (IOException error) {
        android.util.Log.e("UbuntuBox", "ssh autostart failed", error);
      }
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_EXIT.equals(intent.getAction())) {
      exit();
      return START_NOT_STICKY;
    }
    return START_NOT_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  @Override
  public void onDestroy() {
    stopSshProcess();
    for (TerminalSession session : sessions) session.finishIfRunning();
    sessions.clear();
    releaseWakeLock();
    super.onDestroy();
  }

  List<TerminalSession> sessions() {
    return sessions;
  }

  TerminalSession createSession(TerminalSessionClient client) throws IOException {
    return createSession(client, null, null);
  }

  /** guestCommand 为 null 时启动登录 shell，否则执行这条命令（用于设置页的安装任务）。 */
  TerminalSession createSession(TerminalSessionClient client, String name, String guestCommand)
      throws IOException {
    Rootfs rootfs = new Rootfs(this);
    rootfs.refreshGuestFiles(this);
    Proot proot = new Proot(this, rootfs);
    proot.prepare();
    String[] argv =
        guestCommand == null ? proot.argv() : proot.argv("/bin/bash", "-lc", guestCommand);
    TerminalSession session =
        new TerminalSession(
            argv[0], rootfs.base.getAbsolutePath(), argv, proot.environment(), 5000, client);
    session.mSessionName = name != null ? name : "会话 " + (sessions.size() + 1);
    sessions.add(session);
    updateNotification();
    return session;
  }

  void removeSession(TerminalSession session) {
    session.finishIfRunning();
    sessions.remove(session);
    updateNotification();
  }

  /** Activity 不在前台时，会话回调交给这个空实现。 */
  void detachClient(TerminalSessionClient fallback) {
    for (TerminalSession session : sessions) session.updateTerminalSessionClient(fallback);
  }

  // ---------------------------------------------------------------- SSH

  static final String PREFS = "ubox";
  static final String PREF_SSH = "ssh_enabled";
  static final int SSH_PORT = 8022;
  // 设置页在后台线程启停 SSH，UI 线程读状态，需要 volatile 保证可见性
  private volatile Process sshProcess;

  File sshLog() {
    return new File(new Rootfs(this).base, "sshd.log");
  }

  boolean isSshRunning() {
    return sshProcess != null && sshProcess.isAlive();
  }

  /** 在后台（不占终端会话）运行 sshd，输出写到 sshd.log。 */
  synchronized void startSsh() throws IOException {
    if (isSshRunning()) return;
    if (isPortListening()) {
      // 服务重建或应用进程被杀后，上次的 proot/sshd 可能仍占着端口
      stopSshProcess();
      if (isPortListening()) throw new IOException("端口 " + SSH_PORT + " 被其他程序占用");
    }
    Rootfs rootfs = new Rootfs(this);
    rootfs.refreshGuestFiles(this);
    Proot proot = new Proot(this, rootfs);
    proot.prepare();
    ProcessBuilder builder =
        new ProcessBuilder(proot.argv("/usr/local/bin/ubox-sshd", String.valueOf(SSH_PORT)))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(sshLog()))
            .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
    builder.environment().clear();
    for (String entry : proot.environment()) {
      int split = entry.indexOf('=');
      builder.environment().put(entry.substring(0, split), entry.substring(split + 1));
    }
    sshProcess = builder.start();
    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_SSH, true).apply();
    updateNotification();
  }

  /** 停止 sshd；返回 8022 是否已释放，false 表示端口仍被其他程序占用。 */
  synchronized boolean stopSsh() {
    boolean freed = stopSshProcess();
    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_SSH, false).apply();
    updateNotification();
    return freed;
  }

  /**
   * 结束 sshd 并等待端口释放。
   * 只按 sshProcess 句柄结束不够：前台服务重建、应用进程被系统杀掉或 proot 被 SIGKILL 后，
   * 句柄会丢失或留下孤儿 sshd，端口一直占用，下次启动就会失败，所以再按命令行兜底清理一遍。
   */
  private synchronized boolean stopSshProcess() {
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
    try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
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

  void setOnExit(Runnable onExit) {
    this.onExit = onExit;
  }

  void exit() {
    stopSshProcess();
    for (TerminalSession session : new ArrayList<>(sessions)) session.finishIfRunning();
    sessions.clear();
    releaseWakeLock();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
    if (onExit != null) onExit.run();
  }

  boolean isWakeLockHeld() {
    return wakeLock != null && wakeLock.isHeld();
  }

  void setWakeLock(boolean hold) {
    if (hold && !isWakeLockHeld()) {
      wakeLock =
          getSystemService(PowerManager.class)
              .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ubox:sessions");
      wakeLock.acquire();
    } else if (!hold) {
      releaseWakeLock();
    }
    updateNotification();
  }

  private void releaseWakeLock() {
    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    wakeLock = null;
  }

  private void updateNotification() {
    getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
  }

  private Notification buildNotification() {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent exit =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, TerminalService.class).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    String text =
        sessions.size()
            + " 个会话运行中"
            + (isSshRunning() ? " · SSH :" + SSH_PORT : "")
            + (isWakeLockHeld() ? " · 保持唤醒" : "");
    return new Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("UbuntuBox")
        .setContentText(text)
        .setContentIntent(open)
        .setOngoing(true)
        .addAction(new Notification.Action.Builder(null, "退出", exit).build())
        .build();
  }
}
