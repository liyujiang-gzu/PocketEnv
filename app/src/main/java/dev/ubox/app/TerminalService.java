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

/** 持有所有终端会话；前台服务保证切到后台后 Agent 仍在运行。SSH 进程管理委托给 SshManager。 */
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
  private SshManager sshManager;

  @Override
  public void onCreate() {
    super.onCreate();
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel(CHANNEL, "终端会话", NotificationManager.IMPORTANCE_LOW));
    startForeground(
        NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    sshManager = new SshManager(this, this::updateNotification);
    sshManager.autostartIfNeeded();
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
    if (sshManager != null) sshManager.stopQuietly();
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
    // 默认会话不设名称，展示时由 MainActivity 按列表序号补「会话N / N. 标题」
    session.mSessionName = name;
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

  // ---------------------------------------------------------------- SSH（委托 SshManager）

  static final int SSH_PORT = SshManager.SSH_PORT;

  File sshLog() {
    return sshManager.logFile();
  }

  boolean isSshRunning() {
    return sshManager != null && sshManager.isRunning();
  }

  /** 在后台（不占终端会话）运行 sshd，输出写到 sshd.log。 */
  synchronized void startSsh() throws IOException {
    sshManager.start();
  }

  /** 停止 sshd；返回 8022 是否已释放，false 表示端口仍被其他程序占用。 */
  synchronized boolean stopSsh() {
    return sshManager.stop();
  }

  void setOnExit(Runnable onExit) {
    this.onExit = onExit;
  }

  void exit() {
    if (sshManager != null) sshManager.stopQuietly();
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
