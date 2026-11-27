package dev.ubox.pocketenv;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 设置页「局域网访问」（SSH）分区：UI 构建 + 启停 + 公钥/密码管理。
 *
 * <p>SSH 状态数据（是否安装、公钥数、是否有密码）由 SettingsActivity 检测后
 * 通过 {@link #updateStatus} 推入；服务绑定对象通过 {@link #bind} 注入。
 */
final class SshSection {
  private final SettingsActivity host;

  private Button sshButton;
  private TextView sshState, sshCommand, sshKeys, sshPassword;
  private TerminalService service;
  private boolean installed;
  private int keyCount;
  private boolean hasPassword;

  SshSection(SettingsActivity host) {
    this.host = host;
  }

  /** 注入 TerminalService 绑定（连接/断开时调用）。 */
  void bind(TerminalService service) {
    this.service = service;
    updateUi();
  }

  /** 设置页状态检测完成后推入 SSH 相关状态。 */
  void updateStatus(boolean installed, int keyCount, boolean hasPassword) {
    this.installed = installed;
    this.keyCount = keyCount;
    this.hasPassword = hasPassword;
    updateUi();
  }

  /** 向 list 追加本分区的全部视图。 */
  void build(LinearLayout list) {
    list.addView(header("局域网访问"));

    sshButton = actionButton();
    sshButton.setOnClickListener(v -> toggle());
    LinearLayout stateRow = row("SSH 服务（端口 " + TerminalService.SSH_PORT + "）", sshButton);
    sshState = subtitle(stateRow);
    stateRow.setOnClickListener(v -> toggle());
    list.addView(stateRow);

    Button copy = actionButton();
    copy.setText("复制");
    copy.setTextColor(Theme.ACCENT);
    copy.setOnClickListener(v -> copyCommand());
    LinearLayout commandRow = row("连接命令", copy);
    sshCommand = subtitle(commandRow);
    sshCommand.setTypeface(Typeface.MONOSPACE);
    sshCommand.setTextIsSelectable(true);
    commandRow.setOnClickListener(v -> copyCommand());
    list.addView(commandRow);

    LinearLayout keysRow = row("添加公钥", null);
    sshKeys = subtitle(keysRow);
    keysRow.setOnClickListener(v -> addPublicKey());
    list.addView(keysRow);

    LinearLayout passwordRow = row("设置 root 密码", null);
    sshPassword = subtitle(passwordRow);
    passwordRow.setOnClickListener(v -> setRootPassword());
    list.addView(passwordRow);

    LinearLayout logRow = row("查看 SSH 日志", null);
    subtitle(logRow).setText("sshd 的启动信息和登录记录");
    logRow.setOnClickListener(v -> showLog());
    list.addView(logRow);

    TextView note = text(
        "手机开着代理 VPN 时，局域网可能连不上：VPN 会把口袋环境的回包也送进隧道。"
            + "可在 VPN 应用里把口袋环境加入绕过列表，或开启「绕过局域网」。",
        12, Theme.TEXT_DIM);
    note.setPadding(0, dp(4), 0, 0);
    list.addView(note);
    updateUi();
  }

  private void updateUi() {
    if (sshButton == null) return;
    boolean running = service != null && service.isSshRunning();
    if (!installed) {
      sshState.setText("未安装 openssh-server");
      sshButton.setText("安装");
      sshButton.setTextColor(Theme.ACCENT);
    } else if (running) {
      sshState.setText("运行中");
      sshButton.setText("停止");
      sshButton.setTextColor(Theme.DANGER);
    } else {
      sshState.setText("已停止");
      sshButton.setText("启动");
      sshButton.setTextColor(Theme.ACCENT);
    }
    List<String> addresses = lanAddresses();
    StringBuilder commands = new StringBuilder();
    for (String address : addresses) {
      if (commands.length() > 0) commands.append('\n');
      commands.append(commandFor(address));
    }
    sshCommand.setText(addresses.isEmpty() ? "未连接 Wi-Fi，暂无局域网地址" : commands);
    sshKeys.setText(keyCount > 0
        ? "已有 " + keyCount + " 个公钥（/root/.ssh/authorized_keys）"
        : "粘贴电脑上的 ~/.ssh/id_ed25519.pub，用公钥免密登录（推荐）");
    sshPassword.setText(hasPassword ? "已设置，可用密码登录" : "未设置，只能用公钥登录");
  }

  private static String commandFor(String address) {
    return "ssh root@" + address + " -p " + TerminalService.SSH_PORT;
  }

  /** Wi-Fi / 以太网 / 热点上的 IPv4 地址，不含蜂窝和 VPN 隧道。 */
  private static List<String> lanAddresses() {
    List<String> result = new ArrayList<>();
    try {
      for (java.net.NetworkInterface nif :
          java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
        String name = nif.getName();
        if (!nif.isUp() || nif.isLoopback()
            || !(name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")
                || name.startsWith("swlan") || name.startsWith("rndis") || name.startsWith("usb")))
          continue;
        for (java.net.InetAddress address : java.util.Collections.list(nif.getInetAddresses()))
          if (address instanceof java.net.Inet4Address) result.add(address.getHostAddress());
      }
    } catch (java.net.SocketException ignored) {
      // 没有网络接口时返回空列表
    }
    return result;
  }

  private void toggle() {
    // 按钮和整行都可点击，启停进行中时忽略重复点击
    if (!sshButton.isEnabled()) return;
    if (!installed) {
      host.runInTerminal("安装 SSH 服务", "ubox-install apt openssh-server");
      return;
    }
    TerminalService bound = service;
    if (bound == null) return;
    sshButton.setEnabled(false);
    if (bound.isSshRunning()) {
      sshState.setText("停止中…");
      Async.run(host, "settings-ssh-stop",
          bound::stopSsh,
          freed -> {
            sshButton.setEnabled(true);
            updateUi();
            if (!freed)
              Toast.makeText(host,
                  "端口 " + TerminalService.SSH_PORT + " 仍被其他程序占用", Toast.LENGTH_LONG)
                  .show();
          },
          error -> {
            sshButton.setEnabled(true);
            updateUi();
          });
      return;
    }
    sshState.setText("启动中…");
    Async.run(host, "settings-ssh-start",
        () -> {
          bound.startSsh();
          return null;
        },
        ignored -> {
          sshButton.setEnabled(true);
          // sshd 启动需要一两秒，之后检查它是否还活着
          sshState.setText("启动中…");
          sshState.postDelayed(() -> {
            updateUi();
            if (service != null && !service.isSshRunning()) {
              showLog();
            } else {
              boolean warned = host.maybeWarnBatteryOptimization();
              if (!warned && keyCount == 0 && !hasPassword)
                Toast.makeText(host, "SSH 已启动，请先添加公钥或设置密码", Toast.LENGTH_LONG)
                    .show();
            }
          }, 2500);
        },
        error -> {
          sshButton.setEnabled(true);
          updateUi();
          Toast.makeText(host, "启动失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        });
  }

  private void copyCommand() {
    List<String> addresses = lanAddresses();
    if (addresses.isEmpty()) {
      Toast.makeText(host, "未连接 Wi-Fi", Toast.LENGTH_SHORT).show();
      return;
    }
    String command = commandFor(addresses.get(0));
    host.getSystemService(ClipboardManager.class)
        .setPrimaryClip(ClipData.newPlainText("ssh", command));
    Toast.makeText(host, "已复制：" + command, Toast.LENGTH_SHORT).show();
  }

  private void addPublicKey() {
    EditText input = new EditText(host);
    input.setHint("ssh-ed25519 AAAA... user@host");
    input.setTypeface(Typeface.MONOSPACE);
    input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    input.setMinLines(3);
    ClipData clip = host.getSystemService(ClipboardManager.class).getPrimaryClip();
    if (clip != null && clip.getItemCount() > 0) {
      CharSequence copied = clip.getItemAt(0).coerceToText(host);
      if (copied != null && copied.toString().trim().matches("^(ssh-|ecdsa-|sk-)\\S+ \\S+.*"))
        input.setText(copied.toString().trim());
    }
    new AlertDialog.Builder(host)
        .setTitle("添加公钥")
        .setMessage("在电脑上运行 cat ~/.ssh/id_ed25519.pub，把输出粘贴到这里（每行一个）。")
        .setView(input)
        .setNegativeButton("取消", null)
        .setPositiveButton("添加", (d, w) -> savePublicKeys(input.getText().toString()))
        .show();
  }

  private void savePublicKeys(String text) {
    StringBuilder keys = new StringBuilder();
    for (String line : text.split("\n")) {
      line = line.trim();
      if (line.matches("^(ssh-(ed25519|rsa|dss)|ecdsa-sha2-\\S+|sk-\\S+) \\S+.*"))
        keys.append(line).append('\n');
    }
    if (keys.length() == 0) {
      Toast.makeText(host, "没有识别到公钥（应以 ssh-ed25519 / ssh-rsa / ecdsa- 开头）",
          Toast.LENGTH_LONG).show();
      return;
    }
    File ssh = new File(new Rootfs(host).rootfs, "root/.ssh");
    File file = new File(ssh, "authorized_keys");
    try {
      ssh.mkdirs();
      android.system.Os.chmod(ssh.getAbsolutePath(), 0700);
      try (FileOutputStream out = new FileOutputStream(file, true)) {
        out.write(keys.toString().getBytes(StandardCharsets.UTF_8));
      }
      android.system.Os.chmod(file.getAbsolutePath(), 0600);
      Toast.makeText(host, "已添加", Toast.LENGTH_SHORT).show();
    } catch (IOException | android.system.ErrnoException error) {
      Toast.makeText(host, "写入失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
    }
    host.refreshStatus();
  }

  private void setRootPassword() {
    EditText input = new EditText(host);
    input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    input.setHint("至少 6 位");
    new AlertDialog.Builder(host)
        .setTitle("设置 root 密码")
        .setMessage("设置后可以用 ssh 密码登录，同一局域网内的设备都能尝试连接，请使用强密码。")
        .setView(input)
        .setNegativeButton("取消", null)
        .setPositiveButton("保存", (d, w) -> savePassword(input.getText().toString()))
        .show();
  }

  private void savePassword(String password) {
    if (password.length() < 6 || password.contains("\n") || password.contains(":")) {
      Toast.makeText(host, "密码至少 6 位，且不能包含冒号或换行", Toast.LENGTH_LONG).show();
      return;
    }
    // base64 传入，避免密码中的特殊字符被 shell 解释
    String encoded = android.util.Base64.encodeToString(
        ("root:" + password).getBytes(StandardCharsets.UTF_8),
        android.util.Base64.NO_WRAP);
    Async.run(host, "settings-password",
        () -> UbuntuProcess.runScript(host,
            "printf %s " + encoded + " | base64 -d | chpasswd && echo ok",
            UbuntuProcess.timeoutMs(20)),
        output -> {
          Toast.makeText(host,
              output.trim().endsWith("ok") ? "密码已设置" : "设置失败：" + output.trim(),
              Toast.LENGTH_LONG).show();
          host.refreshStatus();
        },
        error -> {
          Toast.makeText(host, "设置失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
          host.refreshStatus();
        });
  }

  private void showLog() {
    String log = "";
    if (service != null) {
      File file = service.sshLog();
      try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
        long start = Math.max(0, in.length() - 8192);
        byte[] bytes = new byte[(int) (in.length() - start)];
        in.seek(start);
        in.readFully(bytes);
        log = new String(bytes, StandardCharsets.UTF_8);
      } catch (IOException missing) {
        log = "";
      }
    }
    TextView view = text(log.isEmpty() ? "（暂无日志）" : log, 11, Theme.TEXT);
    view.setTypeface(Typeface.MONOSPACE);
    view.setTextIsSelectable(true);
    view.setPadding(dp(16), dp(8), dp(16), dp(8));
    ScrollView scroll = new ScrollView(host);
    scroll.addView(view);
    new AlertDialog.Builder(host)
        .setTitle("SSH 日志").setView(scroll).setPositiveButton("关闭", null).show();
  }

  // ---------------------------------------------------------------- 视图工具

  private TextView header(String title) {
    TextView view = text(title, 13, Theme.ACCENT);
    view.setTypeface(Typeface.DEFAULT_BOLD);
    view.setPadding(0, UiViews.dp(host, 24), 0, UiViews.dp(host, 4));
    return view;
  }

  private LinearLayout row(String title, View action) {
    return UiViews.row(host, title, action);
  }

  private TextView subtitle(LinearLayout row) {
    return UiViews.subtitle(row);
  }

  private Button actionButton() {
    Button button = new Button(host, null, 0,
        android.R.style.Widget_DeviceDefault_Button_Borderless);
    button.setAllCaps(false);
    button.setText("…");
    button.setTextColor(Theme.TEXT_DIM);
    return button;
  }

  private TextView text(String value, int sp, int color) {
    return UiViews.text(host, value, sp, color);
  }

  private int dp(int value) {
    return UiViews.dp(host, value);
  }
}
