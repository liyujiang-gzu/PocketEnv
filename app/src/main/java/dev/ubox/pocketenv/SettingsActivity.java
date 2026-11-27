package dev.ubox.pocketenv;

import dev.ubox.pocketenv.model.MirrorOption;
import dev.ubox.pocketenv.model.Toolbox;
import dev.ubox.pocketenv.runtime.TerminalService;
import dev.ubox.pocketenv.runtime.UbuntuProcess;
import dev.ubox.pocketenv.ui.EdgeToEdge;
import dev.ubox.pocketenv.ui.SshSection;
import dev.ubox.pocketenv.ui.Theme;
import dev.ubox.pocketenv.ui.UiViews;
import dev.ubox.pocketenv.util.Async;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 设置页：软件源切换、工具安装、SSH 管理。安装在新终端会话里执行，进度和错误都直接可见。
 *
 *  <p>工具目录数据在 {@link Toolbox}，软件源模型在 {@link MirrorOption}，本类只负责 UI 组装与交互。
 */
public class SettingsActivity extends Activity {
  static final String EXTRA_NAME = "name";
  static final String EXTRA_COMMAND = "command";
  static final String EXTRA_RESET = "reset";
  private static final int REQUEST_PICK_BACKUP = 3;

  /** APT 源：切换走 ubox-mirror。 */
  private static final MirrorOption[] MIRRORS = {
    new MirrorOption("official", "官方（默认）", "ports.ubuntu.com", "ports.ubuntu.com", "mirror"),
    new MirrorOption("tuna", "清华（推荐）", "mirrors.tuna.tsinghua.edu.cn",
        "mirrors.tuna.tsinghua.edu.cn", "mirror"),
    new MirrorOption("ustc", "中科大", "mirrors.ustc.edu.cn", "mirrors.ustc.edu.cn", "mirror"),
    new MirrorOption("aliyun", "阿里云", "mirrors.aliyun.com", "mirrors.aliyun.com", "mirror"),
  };

  /** npm 源（registry）：切换走 ubox-npm-registry。 */
  private static final MirrorOption[] NPM_REGISTRIES = {
    new MirrorOption("official", "官方（默认）", "https://registry.npmjs.org",
        "https://registry.npmjs.org", "npm-registry"),
    new MirrorOption("aliyun", "阿里云（推荐）", "https://registry.npmmirror.com",
        "https://registry.npmmirror.com", "npm-registry"),
    new MirrorOption("tencent", "腾讯云", "https://mirrors.cloud.tencent.com/npm",
        "https://mirrors.cloud.tencent.com/npm", "npm-registry"),
    new MirrorOption("huawei", "华为云", "https://repo.huaweicloud.com/repository/npm",
        "https://repo.huaweicloud.com/repository/npm", "npm-registry"),
  };

  /** PyPI 源：切换走 ubox-pypi-mirror（同时配 pip 和 uv）。 */
  private static final MirrorOption[] PYPI_MIRRORS = {
    new MirrorOption("official", "官方（默认）", "https://pypi.org/simple",
        "https://pypi.org/simple", "pypi-mirror"),
    new MirrorOption("tuna", "清华（推荐）", "https://pypi.tuna.tsinghua.edu.cn/simple",
        "https://pypi.tuna.tsinghua.edu.cn/simple", "pypi-mirror"),
    new MirrorOption("aliyun", "阿里云", "https://mirrors.aliyun.com/pypi/simple",
        "https://mirrors.aliyun.com/pypi/simple", "pypi-mirror"),
    new MirrorOption("tencent", "腾讯云", "https://mirrors.cloud.tencent.com/pypi/simple",
        "https://mirrors.cloud.tencent.com/pypi/simple", "pypi-mirror"),
  };

  /** GO 源（Go 模块代理 GOPROXY）：切换走 ubox-goproxy。 */
  private static final MirrorOption[] GO_PROXIES = {
    new MirrorOption("official", "官方（默认）", "https://proxy.golang.org",
        "https://proxy.golang.org", "goproxy"),
    new MirrorOption("goproxy", "七牛云（推荐）", "https://goproxy.cn",
        "https://goproxy.cn", "goproxy"),
  };

  /** 项目 GitHub 开源地址，项目源码跳转用。 */
  private static final String GITHUB_URL = "https://github.com/liyujiang-gzu/PocketEnv";
  /** 意见反馈地址，跳转 GitEE Issues。 */
  private static final String FEEDBACK_URL = "https://gitee.com/li_yu_jiang/PocketEnv/issues";
  /** 作者联系邮箱，联系作者跳转用。 */
  private static final String AUTHOR_EMAIL = "1032694760@qq.com";
  /** 版本更新地址，跳转 GitHub Releases。 */
  private static final String RELEASES_URL = "https://github.com/liyujiang-gzu/PocketEnv/releases/";

  private final Map<String, Boolean> installed = new HashMap<>();
  private final Map<String, Button> buttons = new HashMap<>();
  private TextView mirrorValue, npmRegistryValue, pypiMirrorValue, goProxyValue, statusLine;
  private Button storageButton, batteryButton;
  private TerminalService service;
  private SshSection sshSection;
  private final android.content.ServiceConnection connection =
      new android.content.ServiceConnection() {
        @Override
        public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
          service = ((TerminalService.LocalBinder) binder).service();
          sshSection.bind(service);
        }

        @Override
        public void onServiceDisconnected(android.content.ComponentName name) {
          service = null;
        }
      };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    LinearLayout list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    list.setPadding(dp(16), dp(8), dp(16), dp(24));

    statusLine = text("正在检查安装状态…", 13, Theme.TEXT_DIM);
    statusLine.setPadding(0, dp(8), 0, dp(4));
    list.addView(statusLine);

    list.addView(header("软件源"));
    LinearLayout mirrorRow = row("APT 源", null);
    mirrorValue = subtitle(mirrorRow);
    mirrorValue.setText("读取中…");
    mirrorRow.setOnClickListener(v -> chooseMirror());
    list.addView(mirrorRow);

    LinearLayout npmRow = row("NPM 源", null);
    npmRegistryValue = subtitle(npmRow);
    npmRegistryValue.setText("读取中…");
    npmRow.setOnClickListener(v -> chooseNpmRegistry());
    list.addView(npmRow);

    LinearLayout pypiRow = row("PyPI 源", null);
    pypiMirrorValue = subtitle(pypiRow);
    pypiMirrorValue.setText("读取中…");
    pypiRow.setOnClickListener(v -> choosePypiMirror());
    list.addView(pypiRow);

    LinearLayout goRow = row("GO 源", null);
    goProxyValue = subtitle(goRow);
    goProxyValue.setText("读取中…");
    goRow.setOnClickListener(v -> chooseGoProxy());
    list.addView(goRow);

    for (Toolbox.Section section : Toolbox.SECTIONS) {
      list.addView(header(section.title()));
      for (Toolbox.Tool tool : section.tools()) {
        Button action = actionButton();
        action.setOnClickListener(v -> onToolClicked(tool));
        buttons.put(tool.id(), action);
        LinearLayout row = row(tool.title(), action);
        subtitle(row).setText(tool.summary());
        row.setOnClickListener(v -> onToolClicked(tool));
        list.addView(row);
      }
    }

    sshSection = new SshSection(this);
    sshSection.build(list);

    buildPermissionSection(list);
    buildAboutSection(list);

    Toolbar toolbar = new Toolbar(this);
    toolbar.setBackgroundColor(Theme.SURFACE);
    toolbar.setTitleTextColor(Theme.TEXT);
    toolbar.setMinimumHeight(dp(48));

    ScrollView scroll = new ScrollView(this);
    scroll.addView(list);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Theme.BACKGROUND);
    root.addView(toolbar, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    root.addView(scroll, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    setContentView(root);
    setActionBar(toolbar);
    setTitle("设置");
    getActionBar().setDisplayHomeAsUpEnabled(true);
    EdgeToEdge.padForSystemBars(root);
    bindService(new Intent(this, TerminalService.class), connection, BIND_AUTO_CREATE);
  }

  @Override
  protected void onDestroy() {
    unbindService(connection);
    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();
    updatePermissionUi();
    refreshStatus();
  }

  @Override
  public boolean onNavigateUp() {
    finish();
    return true;
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != REQUEST_PICK_BACKUP || resultCode != RESULT_OK
        || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    String name = displayName(uri);
    if (name != null && !isBackupFile(name)) {
      Toast.makeText(this, "请选择 .tar.gz 或 .tgz 格式的备份文件", Toast.LENGTH_LONG).show();
      return;
    }
    confirmRestore(uri, safeFileName(name));
  }

  // ---------------------------------------------------------------- 状态

  public void refreshStatus() {
    String statusScript = Toolbox.statusScript();
    Async.run(this, "settings-status",
        () -> UbuntuProcess.runScript(this, statusScript, UbuntuProcess.timeoutMs(30)),
        output -> applyStatus(output),
        error -> statusLine.setText("检查安装状态失败：" + error.getMessage()));
  }

  /** 解析状态脚本输出并刷新 UI。 */
  private void applyStatus(String output) {
    Map<String, Boolean> result = new HashMap<>();
    String mirror = "";
    String npmRegistry = "";
    String pypiMirror = "";
    String goProxy = "";
    int keys = 0;
    for (String line : output.split("\n")) {
      // npm 源行是空格分隔（URL 含 = ），单独处理
      if (line.startsWith("npmreg ")) {
        npmRegistry = line.substring("npmreg ".length()).trim();
        continue;
      }
      // PyPI 源行同样空格分隔
      if (line.startsWith("pypi ")) {
        pypiMirror = line.substring("pypi ".length()).trim();
        continue;
      }
      // GO 源行同样空格分隔
      if (line.startsWith("goproxy ")) {
        goProxy = line.substring("goproxy ".length()).trim();
        continue;
      }
      int split = line.indexOf('=');
      if (split <= 0) continue;
      String key = line.substring(0, split), value = line.substring(split + 1).trim();
      if (key.equals("mirror")) mirror = value;
      else if (key.equals("sshkeys")) keys = parseInt(value);
      else result.put(key, value.equals("1"));
    }
    applyStatus(result, mirror, npmRegistry, pypiMirror, goProxy, keys);
  }

  /** 按解析结果刷新各分区。包内可见，便于单独验证解析逻辑。 */
  void applyStatus(Map<String, Boolean> result, String mirror, String npmRegistry,
      String pypiMirror, String goProxy, int keys) {
    installed.clear();
    installed.putAll(result);
    mirrorValue.setText(describeMirror(mirror));
    npmRegistryValue.setText(describeNpmRegistry(npmRegistry));
    pypiMirrorValue.setText(describePypiMirror(pypiMirror));
    goProxyValue.setText(describeGoProxy(goProxy));
    sshSection.updateStatus(
        Boolean.TRUE.equals(result.get("sshd")), keys, Boolean.TRUE.equals(result.get("sshpw")));
    for (Map.Entry<String, Button> entry : buttons.entrySet())
      styleButton(entry.getValue(), Boolean.TRUE.equals(installed.get(entry.getKey())));
    statusLine.setText("安装会在新的终端会话中进行，完成后返回这里即可看到状态");
  }

  private static int parseInt(String value) {
    try {
      return Integer.parseInt(value.split("\\s+")[0]);
    } catch (NumberFormatException error) {
      return 0;
    }
  }

  private static String describeMirror(String uri) {
    return MirrorOption.describe(MIRRORS, uri);
  }

  // ---------------------------------------------------------------- 操作

  private void chooseMirror() {
    MirrorOption.choose(this, "选择 APT 源", MIRRORS, "，下次安装时会自动 apt update",
        () -> mirrorValue.setText("切换中…"), this::refreshStatus);
  }

  private static String describeNpmRegistry(String url) {
    return MirrorOption.describeByPrefix(NPM_REGISTRIES, url, "未安装 Node.js");
  }

  private void chooseNpmRegistry() {
    MirrorOption.choose(this, "选择 NPM 源", NPM_REGISTRIES, "",
        () -> npmRegistryValue.setText("切换中…"), this::refreshStatus);
  }

  private static String describePypiMirror(String url) {
    return MirrorOption.describeByPrefix(PYPI_MIRRORS, url, "pip 默认（未配置）");
  }

  private void choosePypiMirror() {
    MirrorOption.choose(this, "选择 PyPI 源（pip + uv）", PYPI_MIRRORS, "（pip + uv）",
        () -> pypiMirrorValue.setText("切换中…"), this::refreshStatus);
  }

  private static String describeGoProxy(String url) {
    return MirrorOption.describeByPrefix(GO_PROXIES, url, "官方默认（未配置）");
  }

  private void chooseGoProxy() {
    MirrorOption.choose(this, "选择 GO 源（Go 模块代理）", GO_PROXIES, "（GOPROXY）",
        () -> goProxyValue.setText("切换中…"), this::refreshStatus);
  }

  private void onToolClicked(Toolbox.Tool tool) {
    if (!Boolean.TRUE.equals(installed.get(tool.id()))) {
      runInTerminal("安装 " + tool.title(), tool.installCommand());
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle(tool.title() + " 已安装")
        .setItems(
            new String[] {"重新安装 / 更新", "卸载"},
            (dialog, which) -> {
              if (which == 0) runInTerminal("安装 " + tool.title(), tool.installCommand());
              else confirmRemove(tool);
            })
        .show();
  }

  private void confirmRemove(Toolbox.Tool tool) {
    new AlertDialog.Builder(this)
        .setTitle("卸载 " + tool.title() + "？")
        .setMessage(tool.kind() == Toolbox.Kind.APT
            ? "将执行 apt-get remove " + String.join(" ", tool.packages()) + "，依赖它们的其他软件也可能被一起移除。"
            : "将从 /usr/local 中移除。")
        .setNegativeButton("取消", null)
        .setPositiveButton("卸载", (d, w) -> runInTerminal("卸载 " + tool.title(), tool.removeCommand()))
        .show();
  }

  /** 交给 MainActivity 在新会话中执行，便于看到实时输出。 */
  public void runInTerminal(String name, String command) {
    setResult(RESULT_OK, new Intent().putExtra(EXTRA_NAME, name).putExtra(EXTRA_COMMAND, command));
    finish();
  }

  // ---------------------------------------------------------------- 权限与数据

  private void buildPermissionSection(LinearLayout list) {
    list.addView(header("权限与数据"));

    storageButton = actionButton();
    storageButton.setOnClickListener(v -> requestAllFilesAccess());
    LinearLayout storageRow = row("授权访问 /sdcard", storageButton);
    subtitle(storageRow).setText("开启后新会话可读写手机存储");
    storageRow.setOnClickListener(v -> requestAllFilesAccess());
    list.addView(storageRow);

    batteryButton = actionButton();
    batteryButton.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
    LinearLayout batteryRow = row("忽略电池优化", batteryButton);
    subtitle(batteryRow).setText("避免系统后台限制导致会话中断");
    batteryRow.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
    list.addView(batteryRow);

    LinearLayout backupRow = row("备份 Ubuntu 数据", null);
    subtitle(backupRow).setText("备份新增或修改的内容与空目录，输出到 /sdcard/Download/backups，打包为 .tar.gz");
    backupRow.setOnClickListener(v -> runInTerminal("备份 Ubuntu 新增内容", "ubox-backup"));
    list.addView(backupRow);

    LinearLayout restoreRow = row("恢复 Ubuntu 备份", null);
    subtitle(restoreRow).setText("从 .tar.gz 备份文件恢复，覆盖现有同名文件，请先授权访问 /sdcard");
    restoreRow.setOnClickListener(v -> pickBackup());
    list.addView(restoreRow);

    LinearLayout resetRow = row("重置 Ubuntu 环境", null);
    subtitle(resetRow).setText("结束所有会话并删除整个 Ubuntu 环境后重置到初始的 Base 系统，数据不可恢复");
    resetRow.setOnClickListener(v -> confirmReset());
    list.addView(resetRow);
  }

  // ---------------------------------------------------------------- 恢复

  /** 系统文件选择器挑备份文件；选中后复制到 guest 可见的应用外部目录再恢复。 */
  private void pickBackup() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*");
    // 默认打开备份输出目录；目录不存在时由文件选择器自行处理
    intent.putExtra(
        DocumentsContract.EXTRA_INITIAL_URI,
        DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Download/backups"));
    startActivityForResult(intent, REQUEST_PICK_BACKUP);
  }

  private void confirmRestore(Uri uri, String name) {
    new AlertDialog.Builder(this)
        .setTitle("恢复 Ubuntu 备份")
        .setMessage("将从「" + name + "」恢复备份：\n\n"
            + "• 覆盖当前环境中的同名文件，备份之后新增的文件会保留\n"
            + "• 正在运行的 SSH 服务会先停止\n"
            + "• 恢复不可撤销，建议先做一次 Ubuntu 备份\n\n确定继续吗？")
        .setNegativeButton("取消", null)
        .setPositiveButton("确认恢复", (dialog, which) -> copyAndRestore(uri, name))
        .show();
  }

  private void copyAndRestore(Uri uri, String name) {
    File external = getExternalFilesDir(null);
    if (external == null) {
      Toast.makeText(this, "外部存储不可用，无法准备备份文件", Toast.LENGTH_LONG).show();
      return;
    }
    File dir = new File(external, "restore");
    if (!dir.isDirectory() && !dir.mkdirs()) {
      Toast.makeText(this, "无法创建临时目录：" + dir, Toast.LENGTH_LONG).show();
      return;
    }
    File archive = new File(dir, name);
    File partial = new File(dir, name + ".part");
    AlertDialog progress = new AlertDialog.Builder(this)
        .setTitle("准备恢复")
        .setMessage("正在复制备份文件…\n\n文件较大时可能需要一段时间，请勿退出。")
        .setCancelable(false)
        .create();
    progress.show();
    Async.run(this, "restore-copy",
        () -> {
          File sidecar = new File(archive.getPath() + ".sha256");
          sidecar.delete(); // 避免上一次选择留下的旧校验文件误伤本次归档
          try (InputStream in = getContentResolver().openInputStream(uri);
              OutputStream out = new FileOutputStream(partial)) {
            if (in == null) throw new IOException("无法读取所选文件");
            in.transferTo(out);
          }
          archive.delete();
          if (!partial.renameTo(archive)) throw new IOException("无法写入 " + archive);
          copyChecksum(uri, archive);
          return null;
        },
        ignored -> {
          progress.dismiss();
          stopSshThenRestore(archive);
        },
        error -> {
          progress.dismiss();
          partial.delete();
          Toast.makeText(this, "复制失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        });
  }

  /** 从标准备份目录选择时顺带复制同名 .sha256；拿不到就跳过，由脚本退化为 gzip 校验。 */
  private void copyChecksum(Uri uri, File archive) {
    try {
      if (!"com.android.externalstorage.documents".equals(uri.getAuthority())) return;
      String docId = DocumentsContract.getDocumentId(uri);
      int split = docId.indexOf(':');
      if (split <= 0) return;
      String volume = docId.substring(0, split);
      File root = "primary".equals(volume)
          ? Environment.getExternalStorageDirectory() : new File("/storage", volume);
      File source = new File(root, docId.substring(split + 1) + ".sha256");
      if (!source.isFile()) return;
      try (InputStream in = new FileInputStream(source);
          OutputStream out = new FileOutputStream(new File(archive.getPath() + ".sha256"))) {
        in.transferTo(out);
      }
    } catch (Exception ignored) {
      // 无存储权限或文件提供方不支持定位旁文件
    }
  }

  private void stopSshThenRestore(File archive) {
    String command = "ubox-restore " + shellQuote(archive.getAbsolutePath());
    if (service == null || !service.isSshRunning()) {
      runInTerminal("恢复 Ubuntu 备份文件", command);
      return;
    }
    Async.run(this, "restore-stop-ssh",
        () -> {
          service.stopSsh();
          return null;
        },
        ignored -> runInTerminal("恢复 Ubuntu 备份文件", command),
        error -> runInTerminal("恢复 Ubuntu 备份文件", command));
  }

  private String displayName(Uri uri) {
    try (android.database.Cursor cursor = getContentResolver()
        .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
    } catch (RuntimeException ignored) {
      // 个别文件提供方不支持查询，交给扩展名兜底
    }
    return null;
  }

  /** 只接受 gzip 备份，避免把任意文件复制进恢复目录。 */
  static boolean isBackupFile(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    return lower.endsWith(".tar.gz") || lower.endsWith(".tgz");
  }

  /** 去掉路径分隔符，避免 SAF 返回的文件名把文件写到目标目录之外。 */
  static String safeFileName(String name) {
    if (name == null) return "restore.tar.gz";
    name = name.replace('/', '_').replace('\\', '_').trim();
    return name.isEmpty() || name.equals(".") || name.equals("..") ? "restore.tar.gz" : name;
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  /** 返回设置页时刷新授权与电池优化状态。 */
  private void updatePermissionUi() {
    if (storageButton != null)
      styleGrantButton(storageButton, Environment.isExternalStorageManager());
    if (batteryButton != null) styleGrantButton(batteryButton, ignoringBatteryOptimizations());
  }

  private static void styleGrantButton(Button button, boolean granted) {
    button.setText(granted ? "已开启 ✓" : "开启");
    button.setTextColor(granted ? Theme.OK : Theme.ACCENT);
  }

  private boolean ignoringBatteryOptimizations() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
    PowerManager power = (PowerManager) getSystemService(android.content.Context.POWER_SERVICE);
    return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
  }

  private void requestAllFilesAccess() {
    if (Environment.isExternalStorageManager()) {
      Toast.makeText(this, "已授权，新会话中可访问 /sdcard", Toast.LENGTH_SHORT).show();
      return;
    }
    startActivity(
        new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:" + getPackageName())));
  }

  private void requestIgnoreBatteryOptimizations() {
    if (ignoringBatteryOptimizations()) {
      Toast.makeText(this, "已忽略电池优化", Toast.LENGTH_SHORT).show();
      return;
    }
    startActivity(
        new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:" + getPackageName())));
  }

  private void confirmReset() {
    new AlertDialog.Builder(this)
        .setTitle("重置 Ubuntu 环境")
        .setMessage("将结束所有会话并删除整个 Ubuntu 环境（包括 /root 下的所有文件和已安装的软件），然后重新解压。此操作不可恢复，建议先备份。")
        .setNegativeButton("取消", null)
        .setPositiveButton("删除并重置", (dialog, which) -> confirmResetAgain())
        .show();
  }

  /** 重置不可恢复，再确认一次。 */
  private void confirmResetAgain() {
    new AlertDialog.Builder(this)
        .setTitle("风险提示")
        .setMessage("删除后无法恢复 /root/ 下的文件和已安装的软件，确定继续重置吗？")
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "确认重置",
            (dialog, which) -> {
              setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESET, true));
              finish();
            })
        .show();
  }

  /** 锁屏后 Doze 会挂起网络，提示把应用加入电池优化白名单，否则仅靠唤醒锁仍可能断连。 */
  public boolean maybeWarnBatteryOptimization() {
    if (isFinishing() || ignoringBatteryOptimizations()) return false;
    new AlertDialog.Builder(this)
        .setTitle("锁屏后保持 SSH 连接")
        .setMessage(
            "Android 会在锁屏后休眠 CPU 并限制后台网络，SSH 可能因此断开。"
                + "建议把口袋环境加入电池优化白名单。")
        .setNegativeButton("稍后", null)
        .setPositiveButton("去设置", (dialog, which) -> requestIgnoreBatteryOptimizations())
        .show();
    return true;
  }

  // ---------------------------------------------------------------- 关于

  private void buildAboutSection(LinearLayout list) {
    list.addView(header("关于软件"));

    LinearLayout sourceRow = row("项目源码", null);
    subtitle(sourceRow).setText("GitEE：https://gitee.com/li_yu_jiang/PocketEnv\n"
        + "GitHub：https://github.com/liyujiang-gzu/PocketEnv");
    sourceRow.setOnClickListener(v -> openUrl(GITHUB_URL));
    list.addView(sourceRow);

    LinearLayout feedbackRow = row("意见反馈", null);
    subtitle(feedbackRow).setText("点此在 GitEE 提交问题或建议");
    feedbackRow.setOnClickListener(v -> openUrl(FEEDBACK_URL));
    list.addView(feedbackRow);

    LinearLayout licenseRow = row("第三方开源许可", null);
    subtitle(licenseRow).setText("查看本应用使用的第三方组件及其许可证");
    licenseRow.setOnClickListener(v -> startActivity(new Intent(this, LicensesActivity.class)));
    list.addView(licenseRow);

    LinearLayout contactRow = row("联系作者", null);
    subtitle(contactRow).setText(AUTHOR_EMAIL);
    contactRow.setOnClickListener(v -> openUrl("mailto:" + AUTHOR_EMAIL));
    list.addView(contactRow);

    LinearLayout versionRow = row("版本更新", null);
    subtitle(versionRow).setText(appVersion());
    versionRow.setOnClickListener(v -> openUrl(RELEASES_URL));
    list.addView(versionRow);
  }

  /** 版本名称 + 内部版本号，来自 Gradle 打包时注入的 BuildConfig 常量。 */
  private String appVersion() {
    return BuildConfig.VERSION_NAME + "（内部版本号 " + BuildConfig.VERSION_CODE + "）";
  }

  private void openUrl(String url) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW).setData(Uri.parse(url)));
    } catch (Exception error) {
      Toast.makeText(this, "无法打开链接：" + url, Toast.LENGTH_LONG).show();
    }
  }

  private TextView subtitle(LinearLayout row) {
    return UiViews.subtitle(row);
  }

  // ---------------------------------------------------------------- 视图

  private TextView header(String title) {
    TextView view = text(title, 13, Theme.ACCENT);
    view.setTypeface(Typeface.DEFAULT_BOLD);
    view.setPadding(0, dp(24), 0, dp(4));
    return view;
  }

  private LinearLayout row(String title, View action) {
    return UiViews.row(this, title, action);
  }

  private Button actionButton() {
    Button button = new Button(this, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless);
    button.setAllCaps(false);
    button.setText("…");
    button.setTextColor(Theme.TEXT_DIM);
    return button;
  }

  private static void styleButton(Button button, boolean isInstalled) {
    button.setText(isInstalled ? "已安装 ✓" : "安装");
    button.setTextColor(isInstalled ? Theme.OK : Theme.ACCENT);
  }

  private TextView text(String value, int sp, int color) {
    return UiViews.text(this, value, sp, color);
  }

  private int dp(int value) {
    return UiViews.dp(this, value);
  }
}
