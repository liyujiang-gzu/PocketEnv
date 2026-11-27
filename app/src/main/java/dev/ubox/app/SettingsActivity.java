package dev.ubox.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 软件源切换和常用工具的安装。安装在新终端会话里执行，进度和错误都直接可见。 */
public class SettingsActivity extends Activity {
  static final String EXTRA_NAME = "name";
  static final String EXTRA_COMMAND = "command";
  static final String EXTRA_RESET = "reset";
  private static final String TAG = "UbuntuBox";

  enum Kind { APT, NODE, NPM, UV, GO, CURL, GRADLE, ANDROID_SDK }

  /** packages：apt/npm 包名；check：NODE/NPM 用来判断是否已安装的命令名。 */
  record Tool(String id, String title, String summary, Kind kind, String[] packages, String check) {
    String installCommand() {
      switch (kind) {
        case APT: return "ubox-install apt " + String.join(" ", packages);
        case NPM:
          // 兜底：个别 npm 包的 bin 可能没正确注册进 PATH（装完 command -v 找不到）。
          // 这里在 npm 装完后补建软链（仅当命令缺失且包内二进制存在时），自带 bin
          // 的包不会触发，不影响正常安装。
          String bin = check, pkg = packages[0];
          return "ubox-install npm " + pkg
              + " && command -v " + bin + " >/dev/null 2>&1 || { f=$(npm root -g)"
              + "/" + pkg + "/bin/" + bin
              + "; [ -x \"$f\" ] && ln -sf \"$f\" /usr/local/bin/" + bin
              + " && echo 已为 " + bin + " 创建命令链接 || echo 未找到 " + bin + " 二进制; }";
        case UV: return "ubox-install uv";
        case GO: return "ubox-install go";
        case GRADLE: return "ubox-install gradle";
        case ANDROID_SDK: return "ubox-install android-sdk";
        case CURL: return "curl -fsSL " + packages[0] + " | bash";
        case NODE: return "ubox-install node";
        default: throw new IllegalStateException("未处理的安装类型: " + kind);
      }
    }

    String removeCommand() {
      switch (kind) {
        case APT: return "ubox-install apt-remove " + String.join(" ", packages);
        case NPM: return "ubox-install npm-remove " + String.join(" ", packages);
        case UV: return "rm -f /usr/local/bin/uv \"$HOME/.local/bin/uv\" \"$HOME/.local/bin/uvx\""
            + " && echo 已卸载 uv";
        case GO: return "rm -rf /usr/local/go /usr/local/bin/go /usr/local/bin/gofmt"
            + " && echo 已卸载 Go";
        case GRADLE: return "rm -rf /usr/local/gradle /usr/local/bin/gradle"
            + " && echo 已卸载 Gradle";
        case ANDROID_SDK: return "ubox-install android-sdk remove";
        case CURL:
          if (id.equals("qwen"))
            // qwen 装到 ~/.local/bin/qwen + ~/.local/lib/qwen-code，PATH block 写进 .bashrc
            return "rm -f \"$HOME/.local/bin/qwen\" && rm -rf \"$HOME/.local/lib/qwen-code\""
                + " && sed -i '/# Qwen Code PATH block begin/,/# Qwen Code PATH block end/d'"
                + " \"$HOME/.bashrc\" && echo 已卸载 QwenCode";
          // opencode 装到 ~/.opencode
          return "rm -rf \"$HOME/.opencode\""
              + " && sed -i '/# opencode/d; /\\.opencode\\/bin/d' \"$HOME/.bashrc\""
              + " && echo 已卸载 OpenCode";
        case NODE: return "rm -rf /usr/local/bin/node /usr/local/bin/npm /usr/local/bin/npx"
            + " /usr/local/bin/corepack /usr/local/lib/node_modules/npm /usr/local/include/node"
            + " && echo 已卸载 Node.js";
        default: throw new IllegalStateException("未处理的卸载类型: " + kind);
      }
    }

    String statusCheck() {
      if (kind == Kind.APT)
        return "n=$(dpkg-query -W -f='${db:Status-Status}\\n' " + String.join(" ", packages)
            + " 2>/dev/null | grep -c '^installed$'); [ \"$n\" = " + packages.length
            + " ] && echo " + id + "=1 || echo " + id + "=0";
      if (kind == Kind.UV)
        // uv 官方脚本装到 ~/.local/bin（不在非交互 shell 的 PATH），补软链前
        // command -v uv 可能找不到，故再回退检查 ~/.local/bin/uv 是否存在。
        return "if command -v uv >/dev/null 2>&1 || [ -x \"$HOME/.local/bin/uv\" ]; "
            + "then echo " + id + "=1; else echo " + id + "=0; fi";
      if (kind == Kind.GO)
        // Go tarball 装到 /usr/local/go 并软链 go 到 /usr/local/bin。软链在 PATH 里，
        // command -v go 通常能命中；再回退检查 /usr/local/go/bin/go 兜底。
        return "if command -v go >/dev/null 2>&1 || [ -x /usr/local/go/bin/go ]; "
            + "then echo " + id + "=1; else echo " + id + "=0; fi";
      if (kind == Kind.GRADLE)
        // Gradle 解压到 /usr/local/gradle 并软链 gradle 到 /usr/local/bin。软链在
        // PATH 里，command -v gradle 通常能命中；再回退检查实际目录兜底。
        return "if command -v gradle >/dev/null 2>&1 || [ -x /usr/local/gradle/bin/gradle ]; "
            + "then echo " + id + "=1; else echo " + id + "=0; fi";
      if (kind == Kind.ANDROID_SDK)
        // SDK 装在 /opt/android-sdk，aapt2 等原生工具在 build-tools 目录里。
        // 直接查固定路径：非交互 shell 不一定加载 /etc/profile.d 的 PATH。
        return "if [ -x /opt/android-sdk/build-tools/36.0.0/aapt2 ]"
            + " && [ -d /opt/android-sdk/platforms/android-36 ]; "
            + "then echo " + id + "=1; else echo " + id + "=0; fi";
      if (kind == Kind.CURL) {
        // curl 脚本装到各自目录并把该目录写进 .bashrc 的 PATH。非交互 shell 不一定
        // source .bashrc，故再回退检查实际二进制：opencode 在 ~/.opencode/bin，
        // qwen 在 ~/.local/bin。
        String curlBin = id.equals("qwen") ? "$HOME/.local/bin" : "$HOME/.opencode/bin";
        return "if command -v " + check + " >/dev/null 2>&1 || [ -x \"" + curlBin + "/" + check + "\" ]; "
            + "then echo " + id + "=1; else echo " + id + "=0; fi";
      }
      return "command -v " + check + " >/dev/null 2>&1 && echo " + id + "=1 || echo " + id + "=0";
    }
  }

  record Section(String title, Tool... tools) {}

  private static Tool apt(String id, String title, String summary, String... packages) {
    return new Tool(id, title, summary, Kind.APT, packages, null);
  }

  private static Tool npm(String id, String title, String summary, String bin, String pkg) {
    return new Tool(id, title, summary, Kind.NPM, new String[] {pkg}, bin);
  }

  private static Tool go(String id, String title, String summary) {
    return new Tool(id, title, summary, Kind.GO, new String[0], "go");
  }

  private static Tool gradle(String id, String title, String summary) {
    return new Tool(id, title, summary, Kind.GRADLE, new String[0], "gradle");
  }

  private static Tool androidSdk(String id, String title, String summary) {
    return new Tool(id, title, summary, Kind.ANDROID_SDK, new String[0], null);
  }

  /** curl 官方脚本安装（packages[0] = 安装脚本 URL），check = 装完的命令名。 */
  private static Tool curl(String id, String title, String summary, String url, String bin) {
    return new Tool(id, title, summary, Kind.CURL, new String[] {url}, bin);
  }

  static final Section[] SECTIONS = {
    new Section(
        "常用工具",
        apt("net", "网络工具", "curl、wget、dig、ifconfig 和 HTTPS 根证书（ping 已内置）",
            "curl", "wget", "ca-certificates", "dnsutils", "net-tools"),
        apt("cli", "常用命令", "less、htop、tree、zip/unzip、xz、jq、ripgrep、file、tmux",
            "less", "htop", "tree", "zip", "unzip", "xz-utils", "jq", "ripgrep", "file",
            "procps", "tmux"),
        apt("editor", "编辑器", "nano、vim", "nano", "vim"),
        apt("ssh", "SSH 客户端", "ssh、scp、ssh-keygen", "openssh-client")),
    new Section(
        "开发环境",
        apt("git", "Git", "版本管理，Agent 改代码基本都需要", "git", "ca-certificates"),
        new Tool("node", "Node.js 22", "官方 linux-arm64 版本，含 npm；AI Agent 依赖它",
            Kind.NODE, new String[0], "node"),
        apt("python", "Python 3", "python3、pip、venv；AI 时代的著名语言", "python3", "python3-pip", "python3-venv"),
        new Tool("uv", "uv", "Astral 出品的高速 Python 包管理器（替代 pip/venv），需先装 Python 3",
            Kind.UV, new String[0], "uv"),
        apt("build", "C/C++", "gcc、g++、make；npm 原生模块编译需要", "build-essential"),
        apt("jdk17", "OpenJDK 17", "Java/Kotlin 开发工具包 17；Gradle 9 运行需要", "openjdk-17-jdk"),
        androidSdk("androidsdk", "Android SDK 36", "platform-36 + build-tools 36.0.0（arm64 原生工具），无 system image / source"),
        apt("maven3", "Maven 3", "Java 项目构建与依赖管理（apt 自带 3.x）", "maven"),
        gradle("gradle9", "Gradle 9", "官方 9.x 二进制包（腾讯云镜像 + 校验），缺 Java 时自动装 OpenJDK 17"),
        go("golang", "Go", "官方 linux-arm64 tarball（golang.google.cn 镜像），含 go、gofmt")),
    new Section(
        "编程智能体",
        curl("opencode", "OpenCode", "Anomaly · 官方脚本安装（含后台服务依赖），装到 ~/.opencode",
            "https://opencode.ai/v2/install", "opencode"),
        npm("claude", "Claude Code", "Anthropic · npm @anthropic-ai/claude-code", "claude",
            "@anthropic-ai/claude-code"),
        npm("codex", "Codex CLI", "OpenAI · npm @openai/codex", "codex", "@openai/codex"),
        npm("gemini", "Gemini CLI", "Google · npm @google/gemini-cli", "gemini",
            "@google/gemini-cli"),
        npm("codebuddy", "CodeBuddy", "Tencent · npm @tencent-ai/codebuddy-code",
            "codebuddy", "@tencent-ai/codebuddy-code"),
        curl("qwen", "QwenCode", "Alibaba · 官方脚本安装 QwenCode，装到 ~/.local",
            "https://qwen-code-assets.oss-cn-hangzhou.aliyuncs.com/installation/install-qwen-standalone.sh",
            "qwen")),
  };

  private static final String[][] MIRRORS = {
    {"official", "官方（默认）", "ports.ubuntu.com"},
    {"tuna", "清华（推荐）", "mirrors.tuna.tsinghua.edu.cn"},
    {"ustc", "中科大", "mirrors.ustc.edu.cn"},
    {"aliyun", "阿里云", "mirrors.aliyun.com"},
  };

  /** npm 源（registry）：id、名称、URL。切换走 ubox-npm-registry。 */
  private static final String[][] NPM_REGISTRIES = {
    {"official", "官方（默认）", "https://registry.npmjs.org"},
    {"aliyun", "阿里云（推荐）", "https://registry.npmmirror.com"},
    {"tencent", "腾讯云", "https://mirrors.cloud.tencent.com/npm"},
    {"huawei", "华为云", "https://repo.huaweicloud.com/repository/npm"},
  };

  /** PyPI 源：id、名称、URL。切换走 ubox-pypi-mirror（同时配 pip 和 uv）。 */
  private static final String[][] PYPI_MIRRORS = {
    {"official", "官方（默认）", "https://pypi.org/simple"},
    {"tuna", "清华（推荐）", "https://pypi.tuna.tsinghua.edu.cn/simple"},
    {"aliyun", "阿里云", "https://mirrors.aliyun.com/pypi/simple"},
    {"tencent", "腾讯云", "https://mirrors.cloud.tencent.com/pypi/simple"},
  };

  /** GO 源（Go 模块代理 GOPROXY）：id、名称、URL。切换走 ubox-goproxy。 */
  private static final String[][] GO_PROXIES = {
    {"official", "官方（默认）", "https://proxy.golang.org"},
    {"goproxy", "七牛云（推荐）", "https://goproxy.cn"},
  };

  /** 项目 Gitee 开源地址，「关于我们」跳转用。 */
  private static final String GITEE_URL = "https://gitee.com/li_yu_jiang/ubuntu-box";

  private final Map<String, Boolean> installed = new HashMap<>();
  private final Map<String, Button> buttons = new HashMap<>();
  private TextView mirrorValue, npmRegistryValue, pypiMirrorValue, goProxyValue, statusLine;
  private TextView sshState, sshCommand, sshKeys, sshPassword;
  private Button storageButton, batteryButton;
  private Button sshButton;
  private boolean sshInstalled;
  private int sshKeyCount;
  private boolean sshHasPassword;
  private TerminalService service;
  private final android.content.ServiceConnection connection =
      new android.content.ServiceConnection() {
        @Override
        public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
          service = ((TerminalService.LocalBinder) binder).service();
          updateSshUi();
        }

        @Override
        public void onServiceDisconnected(android.content.ComponentName name) {
          service = null;
        }
      };
  private Proot proot;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    proot = new Proot(this, new Rootfs(this));

    LinearLayout list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    list.setPadding(dp(16), dp(8), dp(16), dp(24));

    statusLine = text("正在检查安装状态…", 13, 0xFF9E9E9E);
    statusLine.setPadding(0, dp(8), 0, dp(4));
    list.addView(statusLine);

    list.addView(header("软件源"));
    LinearLayout mirrorRow = row("APT 源", null);
    mirrorValue = (TextView) ((LinearLayout) mirrorRow.getChildAt(0)).getChildAt(1);
    mirrorValue.setText("读取中…");
    mirrorRow.setOnClickListener(v -> chooseMirror());
    list.addView(mirrorRow);

    LinearLayout npmRow = row("NPM 源", null);
    npmRegistryValue = (TextView) ((LinearLayout) npmRow.getChildAt(0)).getChildAt(1);
    npmRegistryValue.setText("读取中…");
    npmRow.setOnClickListener(v -> chooseNpmRegistry());
    list.addView(npmRow);

    LinearLayout pypiRow = row("PyPI 源", null);
    pypiMirrorValue = (TextView) ((LinearLayout) pypiRow.getChildAt(0)).getChildAt(1);
    pypiMirrorValue.setText("读取中…");
    pypiRow.setOnClickListener(v -> choosePypiMirror());
    list.addView(pypiRow);

    LinearLayout goRow = row("GO 源", null);
    goProxyValue = (TextView) ((LinearLayout) goRow.getChildAt(0)).getChildAt(1);
    goProxyValue.setText("读取中…");
    goRow.setOnClickListener(v -> chooseGoProxy());
    list.addView(goRow);

    for (Section section : SECTIONS) {
      list.addView(header(section.title()));
      for (Tool tool : section.tools()) {
        Button action = actionButton();
        action.setOnClickListener(v -> onToolClicked(tool));
        buttons.put(tool.id(), action);
        LinearLayout row = row(tool.title(), action);
        ((TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1)).setText(tool.summary());
        row.setOnClickListener(v -> onToolClicked(tool));
        list.addView(row);
      }
    }

    buildSshSection(list);

    Toolbar toolbar = new Toolbar(this);
    toolbar.setBackgroundColor(0xFF1E1E1E);
    toolbar.setTitleTextColor(Color.WHITE);
    toolbar.setMinimumHeight(dp(48));

    ScrollView scroll = new ScrollView(this);
    scroll.addView(list);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0xFF121212);
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

  // ---------------------------------------------------------------- 状态

  private void refreshStatus() {
    StringBuilder script = new StringBuilder();
    script.append("grep -m1 '^URIs:' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null"
        + " | sed 's/^URIs: */mirror=/'\n");
    // npm 源用空格分隔（URL 里含 = ，不能走下面按 = 切分的那套解析）
    script.append("echo npmreg $(npm config get registry 2>/dev/null)\n");
    // PyPI 源读 pip.conf 的 index-url（uv.toml 与之同步，展示以 pip 为准），同样空格分隔
    script.append("echo pypi $(grep -m1 '^index-url' $HOME/.config/pip/pip.conf 2>/dev/null"
        + " | sed 's/^index-url[[:space:]]*=[[:space:]]*//')\n");
    // GO 源读 GOPROXY（切换脚本写入 /etc/profile.d/goproxy.sh），空格分隔避免 URL 里的 =
    script.append("echo goproxy $(grep -m1 '^export GOPROXY' /etc/profile.d/goproxy.sh 2>/dev/null"
        + " | sed -e 's/^export GOPROXY=//' -e 's/,direct$//' -e 's/\"//g')\n");
    for (Section section : SECTIONS)
      for (Tool tool : section.tools()) script.append(tool.statusCheck()).append('\n');
    script.append("[ -x /usr/sbin/sshd ] && echo sshd=1 || echo sshd=0\n");
    script.append("echo sshkeys=$(grep -cE '^(ssh-|ecdsa-|sk-)' /root/.ssh/authorized_keys 2>/dev/null || echo 0)\n");
    script.append("awk -F: '$1==\"root\"{print \"sshpw=\" (($2 ~ /^[!*]/ || $2 == \"\") ? 0 : 1)}' /etc/shadow\n");
    new Thread(
            () -> {
              String output;
              try {
                output = proot.run(script.toString(), 30_000);
              } catch (Exception error) {
                Log.e(TAG, "status check failed", error);
                runOnUiThread(() -> statusLine.setText("检查安装状态失败：" + error.getMessage()));
                return;
              }
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
              int keyCount = keys;
              String mirrorText = describeMirror(mirror);
              String npmRegistryText = describeNpmRegistry(npmRegistry);
              String pypiMirrorText = describePypiMirror(pypiMirror);
              String goProxyText = describeGoProxy(goProxy);
              runOnUiThread(
                  () -> {
                    installed.clear();
                    installed.putAll(result);
                    mirrorValue.setText(mirrorText);
                    npmRegistryValue.setText(npmRegistryText);
                    pypiMirrorValue.setText(pypiMirrorText);
                    goProxyValue.setText(goProxyText);
                    sshInstalled = Boolean.TRUE.equals(result.get("sshd"));
                    sshHasPassword = Boolean.TRUE.equals(result.get("sshpw"));
                    sshKeyCount = keyCount;
                    updateSshUi();
                    for (Map.Entry<String, Button> entry : buttons.entrySet())
                      styleButton(entry.getValue(), Boolean.TRUE.equals(installed.get(entry.getKey())));
                    statusLine.setText("安装会在新的终端会话中进行，完成后返回这里即可看到状态");
                  });
            },
            "settings-status")
        .start();
  }

  private static int parseInt(String value) {
    try {
      return Integer.parseInt(value.split("\\s+")[0]);
    } catch (NumberFormatException error) {
      return 0;
    }
  }

  private static String describeMirror(String uri) {
    for (String[] mirror : MIRRORS) if (uri.contains(mirror[2])) return mirror[1] + " · " + mirror[2];
    return uri.isEmpty() ? "未知" : uri;
  }

  // ---------------------------------------------------------------- 操作

  private void chooseMirror() {
    String[] labels = new String[MIRRORS.length];
    for (int i = 0; i < MIRRORS.length; i++) labels[i] = MIRRORS[i][1] + "\n" + MIRRORS[i][2];
    new AlertDialog.Builder(this)
        .setTitle("选择 APT 源")
        .setItems(labels, (dialog, which) -> applyMirror(MIRRORS[which][0], MIRRORS[which][1]))
        .show();
  }

  private void applyMirror(String id, String label) {
    mirrorValue.setText("切换中…");
    new Thread(
            () -> {
              String message;
              try {
                proot.run("ubox-mirror " + id, 15_000);
                message = "已切换到" + label + "，下次安装时会自动 apt update";
              } catch (Exception error) {
                message = "切换失败：" + error.getMessage();
              }
              String toast = message;
              runOnUiThread(
                  () -> {
                    Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
                    refreshStatus();
                  });
            },
            "settings-mirror")
        .start();
  }

  private static String describeNpmRegistry(String url) {
    if (url.isEmpty()) return "未安装 Node.js";
    for (String[] registry : NPM_REGISTRIES)
      if (url.startsWith(registry[2])) return registry[1] + " · " + registry[2];
    return url;
  }

  private void chooseNpmRegistry() {
    String[] labels = new String[NPM_REGISTRIES.length];
    for (int i = 0; i < NPM_REGISTRIES.length; i++)
      labels[i] = NPM_REGISTRIES[i][1] + "\n" + NPM_REGISTRIES[i][2];
    new AlertDialog.Builder(this)
        .setTitle("选择 NPM 源")
        .setItems(
            labels,
            (dialog, which) ->
                applyNpmRegistry(NPM_REGISTRIES[which][0], NPM_REGISTRIES[which][1]))
        .show();
  }

  private void applyNpmRegistry(String id, String label) {
    npmRegistryValue.setText("切换中…");
    new Thread(
            () -> {
              String message;
              try {
                proot.run("ubox-npm-registry " + id, 15_000);
                message = "已切换到" + label;
              } catch (Exception error) {
                message = "切换失败：" + error.getMessage();
              }
              String toast = message;
              runOnUiThread(
                  () -> {
                    Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
                    refreshStatus();
                  });
            },
            "settings-npm-registry")
        .start();
  }

  private static String describePypiMirror(String url) {
    if (url.isEmpty()) return "pip 默认（未配置）";
    for (String[] mirror : PYPI_MIRRORS)
      if (url.startsWith(mirror[2])) return mirror[1] + " · " + mirror[2];
    return url;
  }

  private void choosePypiMirror() {
    String[] labels = new String[PYPI_MIRRORS.length];
    for (int i = 0; i < PYPI_MIRRORS.length; i++)
      labels[i] = PYPI_MIRRORS[i][1] + "\n" + PYPI_MIRRORS[i][2];
    new AlertDialog.Builder(this)
        .setTitle("选择 PyPI 源（pip + uv）")
        .setItems(
            labels,
            (dialog, which) ->
                applyPypiMirror(PYPI_MIRRORS[which][0], PYPI_MIRRORS[which][1]))
        .show();
  }

  private void applyPypiMirror(String id, String label) {
    pypiMirrorValue.setText("切换中…");
    new Thread(
            () -> {
              String message;
              try {
                proot.run("ubox-pypi-mirror " + id, 15_000);
                message = "已切换到" + label + "（pip + uv）";
              } catch (Exception error) {
                message = "切换失败：" + error.getMessage();
              }
              String toast = message;
              runOnUiThread(
                  () -> {
                    Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
                    refreshStatus();
                  });
            },
            "settings-pypi-mirror")
        .start();
  }

  private static String describeGoProxy(String url) {
    if (url.isEmpty()) return "官方默认（未配置）";
    for (String[] proxy : GO_PROXIES)
      if (url.startsWith(proxy[2])) return proxy[1] + " · " + proxy[2];
    return url;
  }

  private void chooseGoProxy() {
    String[] labels = new String[GO_PROXIES.length];
    for (int i = 0; i < GO_PROXIES.length; i++)
      labels[i] = GO_PROXIES[i][1] + "\n" + GO_PROXIES[i][2];
    new AlertDialog.Builder(this)
        .setTitle("选择 GO 源（Go 模块代理）")
        .setItems(
            labels,
            (dialog, which) -> applyGoProxy(GO_PROXIES[which][0], GO_PROXIES[which][1]))
        .show();
  }

  private void applyGoProxy(String id, String label) {
    goProxyValue.setText("切换中…");
    new Thread(
            () -> {
              String message;
              try {
                proot.run("ubox-goproxy " + id, 15_000);
                message = "已切换到" + label + "（GOPROXY）";
              } catch (Exception error) {
                message = "切换失败：" + error.getMessage();
              }
              String toast = message;
              runOnUiThread(
                  () -> {
                    Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
                    refreshStatus();
                  });
            },
            "settings-go-proxy")
        .start();
  }

  private void onToolClicked(Tool tool) {
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

  private void confirmRemove(Tool tool) {
    new AlertDialog.Builder(this)
        .setTitle("卸载 " + tool.title() + "？")
        .setMessage(tool.kind() == Kind.APT
            ? "将执行 apt-get remove " + String.join(" ", tool.packages()) + "，依赖它们的其他软件也可能被一起移除。"
            : "将从 /usr/local 中移除。")
        .setNegativeButton("取消", null)
        .setPositiveButton("卸载", (d, w) -> runInTerminal("卸载 " + tool.title(), tool.removeCommand()))
        .show();
  }

  /** 交给 MainActivity 在新会话中执行，便于看到实时输出。 */
  private void runInTerminal(String name, String command) {
    setResult(RESULT_OK, new Intent().putExtra(EXTRA_NAME, name).putExtra(EXTRA_COMMAND, command));
    finish();
  }

  // ---------------------------------------------------------------- SSH

  private void buildSshSection(LinearLayout list) {
    list.addView(header("局域网访问"));

    sshButton = actionButton();
    sshButton.setOnClickListener(v -> toggleSsh());
    LinearLayout stateRow = row("SSH 服务（端口 " + TerminalService.SSH_PORT + "）", sshButton);
    sshState = subtitle(stateRow);
    stateRow.setOnClickListener(v -> toggleSsh());
    list.addView(stateRow);

    Button copy = actionButton();
    copy.setText("复制");
    copy.setTextColor(0xFFE95420);
    copy.setOnClickListener(v -> copySshCommand());
    LinearLayout commandRow = row("连接命令", copy);
    sshCommand = subtitle(commandRow);
    sshCommand.setTypeface(Typeface.MONOSPACE);
    sshCommand.setTextIsSelectable(true);
    commandRow.setOnClickListener(v -> copySshCommand());
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
    logRow.setOnClickListener(v -> showSshLog());
    list.addView(logRow);

    TextView note = text(
        "手机开着代理 VPN 时，局域网可能连不上：VPN 会把 UbuntuBox 的回包也送进隧道。"
            + "可在 VPN 应用里把 UbuntuBox 加入绕过列表，或开启「绕过局域网」。",
        12, 0xFF9E9E9E);
    note.setPadding(0, dp(4), 0, 0);
    list.addView(note);
    updateSshUi();

    buildPermissionSection(list);
    buildAboutSection(list);
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

    LinearLayout resetRow = row("重置 Ubuntu 环境", null);
    subtitle(resetRow).setText("结束所有会话并删除整个 Ubuntu 环境后重新解压，数据不可恢复");
    resetRow.setOnClickListener(v -> confirmReset());
    list.addView(resetRow);
  }

  /** 返回设置页时刷新授权与电池优化状态。 */
  private void updatePermissionUi() {
    if (storageButton != null)
      styleGrantButton(storageButton, Environment.isExternalStorageManager());
    if (batteryButton != null) styleGrantButton(batteryButton, ignoringBatteryOptimizations());
  }

  private static void styleGrantButton(Button button, boolean granted) {
    button.setText(granted ? "已开启 ✓" : "开启");
    button.setTextColor(granted ? 0xFF81C784 : 0xFFE95420);
  }

  private boolean ignoringBatteryOptimizations() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
    PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
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
        .setTitle("确认重置？")
        .setMessage("删除后无法恢复 /root 下的文件和已安装的软件，确定继续吗？")
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "确认重置",
            (dialog, which) -> {
              setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESET, true));
              finish();
            })
        .show();
  }

  // ---------------------------------------------------------------- 关于

  private void buildAboutSection(LinearLayout list) {
    list.addView(header("关于我们"));

    LinearLayout introRow = row("项目介绍", null);
    subtitle(introRow).setText("UbuntuBox · 在 Android 上运行完整 Ubuntu 环境，点此访问 Gitee 开源地址");
    introRow.setOnClickListener(v -> openUrl(GITEE_URL));
    list.addView(introRow);

    LinearLayout licenseRow = row("第三方开源许可", null);
    subtitle(licenseRow).setText("查看本应用使用的第三方组件及其许可证");
    licenseRow.setOnClickListener(v -> startActivity(new Intent(this, LicensesActivity.class)));
    list.addView(licenseRow);

    LinearLayout versionRow = row("版本", null);
    subtitle(versionRow).setText(appVersion());
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

  private void updateSshUi() {
    if (sshButton == null) return;
    boolean running = service != null && service.isSshRunning();
    if (!sshInstalled) {
      sshState.setText("未安装 openssh-server");
      sshButton.setText("安装");
      sshButton.setTextColor(0xFFE95420);
    } else if (running) {
      sshState.setText("运行中");
      sshButton.setText("停止");
      sshButton.setTextColor(0xFFE57373);
    } else {
      sshState.setText("已停止");
      sshButton.setText("启动");
      sshButton.setTextColor(0xFFE95420);
    }
    List<String> addresses = lanAddresses();
    StringBuilder commands = new StringBuilder();
    for (String address : addresses) {
      if (commands.length() > 0) commands.append('\n');
      commands.append(sshCommandFor(address));
    }
    sshCommand.setText(addresses.isEmpty() ? "未连接 Wi-Fi，暂无局域网地址" : commands);
    sshKeys.setText(sshKeyCount > 0
        ? "已有 " + sshKeyCount + " 个公钥（/root/.ssh/authorized_keys）"
        : "粘贴电脑上的 ~/.ssh/id_ed25519.pub，用公钥免密登录（推荐）");
    sshPassword.setText(sshHasPassword ? "已设置，可用密码登录" : "未设置，只能用公钥登录");
  }

  private static String sshCommandFor(String address) {
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

  private void toggleSsh() {
    if (!sshInstalled) {
      runInTerminal("安装 SSH 服务", "ubox-install apt openssh-server");
      return;
    }
    if (service == null) return;
    if (service.isSshRunning()) {
      service.stopSsh();
      updateSshUi();
      return;
    }
    try {
      service.startSsh();
    } catch (java.io.IOException error) {
      Toast.makeText(this, "启动失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
    }
    // sshd 启动需要一两秒，之后检查它是否还活着
    sshState.setText("启动中…");
    sshState.postDelayed(
        () -> {
          updateSshUi();
          if (service != null && !service.isSshRunning()) showSshLog();
          else if (sshKeyCount == 0 && !sshHasPassword)
            Toast.makeText(this, "SSH 已启动，请先添加公钥或设置密码", Toast.LENGTH_LONG).show();
        },
        2500);
  }

  private void copySshCommand() {
    List<String> addresses = lanAddresses();
    if (addresses.isEmpty()) {
      Toast.makeText(this, "未连接 Wi-Fi", Toast.LENGTH_SHORT).show();
      return;
    }
    String command = sshCommandFor(addresses.get(0));
    getSystemService(android.content.ClipboardManager.class)
        .setPrimaryClip(android.content.ClipData.newPlainText("ssh", command));
    Toast.makeText(this, "已复制：" + command, Toast.LENGTH_SHORT).show();
  }

  private void addPublicKey() {
    android.widget.EditText input = new android.widget.EditText(this);
    input.setHint("ssh-ed25519 AAAA... user@host");
    input.setTypeface(Typeface.MONOSPACE);
    input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    input.setMinLines(3);
    android.content.ClipData clip =
        getSystemService(android.content.ClipboardManager.class).getPrimaryClip();
    if (clip != null && clip.getItemCount() > 0) {
      CharSequence copied = clip.getItemAt(0).coerceToText(this);
      if (copied != null && copied.toString().trim().matches("^(ssh-|ecdsa-|sk-)\\S+ \\S+.*"))
        input.setText(copied.toString().trim());
    }
    new AlertDialog.Builder(this)
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
      Toast.makeText(this, "没有识别到公钥（应以 ssh-ed25519 / ssh-rsa / ecdsa- 开头）", Toast.LENGTH_LONG).show();
      return;
    }
    java.io.File ssh = new java.io.File(new Rootfs(this).rootfs, "root/.ssh");
    java.io.File file = new java.io.File(ssh, "authorized_keys");
    try {
      ssh.mkdirs();
      android.system.Os.chmod(ssh.getAbsolutePath(), 0700);
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(file, true)) {
        out.write(keys.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      android.system.Os.chmod(file.getAbsolutePath(), 0600);
      Toast.makeText(this, "已添加", Toast.LENGTH_SHORT).show();
    } catch (java.io.IOException | android.system.ErrnoException error) {
      Toast.makeText(this, "写入失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
    }
    refreshStatus();
  }

  private void setRootPassword() {
    android.widget.EditText input = new android.widget.EditText(this);
    input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    input.setHint("至少 6 位");
    new AlertDialog.Builder(this)
        .setTitle("设置 root 密码")
        .setMessage("设置后可以用 ssh 密码登录，同一局域网内的设备都能尝试连接，请使用强密码。")
        .setView(input)
        .setNegativeButton("取消", null)
        .setPositiveButton("保存", (d, w) -> savePassword(input.getText().toString()))
        .show();
  }

  private void savePassword(String password) {
    if (password.length() < 6 || password.contains("\n") || password.contains(":")) {
      Toast.makeText(this, "密码至少 6 位，且不能包含冒号或换行", Toast.LENGTH_LONG).show();
      return;
    }
    // base64 传入，避免密码中的特殊字符被 shell 解释
    String encoded = android.util.Base64.encodeToString(
        ("root:" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8),
        android.util.Base64.NO_WRAP);
    new Thread(
            () -> {
              String message;
              try {
                String output = proot.run(
                    "printf %s " + encoded + " | base64 -d | chpasswd && echo ok", 20_000);
                message = output.trim().endsWith("ok") ? "密码已设置" : "设置失败：" + output.trim();
              } catch (Exception error) {
                message = "设置失败：" + error.getMessage();
              }
              String toast = message;
              runOnUiThread(() -> {
                Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
                refreshStatus();
              });
            },
            "settings-password")
        .start();
  }

  private void showSshLog() {
    String log = "";
    if (service != null) {
      java.io.File file = service.sshLog();
      try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(file, "r")) {
        long start = Math.max(0, in.length() - 8192);
        byte[] bytes = new byte[(int) (in.length() - start)];
        in.seek(start);
        in.readFully(bytes);
        log = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
      } catch (java.io.IOException missing) {
        log = "";
      }
    }
    TextView view = text(log.isEmpty() ? "（暂无日志）" : log, 11, Color.WHITE);
    view.setTypeface(Typeface.MONOSPACE);
    view.setTextIsSelectable(true);
    view.setPadding(dp(16), dp(8), dp(16), dp(8));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(view);
    new AlertDialog.Builder(this).setTitle("SSH 日志").setView(scroll).setPositiveButton("关闭", null).show();
  }

  private TextView subtitle(LinearLayout row) {
    return UiViews.subtitle(row);
  }

  // ---------------------------------------------------------------- 视图

  private TextView header(String title) {
    TextView view = text(title, 13, 0xFFE95420);
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
    button.setTextColor(0xFF9E9E9E);
    return button;
  }

  private static void styleButton(Button button, boolean isInstalled) {
    button.setText(isInstalled ? "已安装 ✓" : "安装");
    button.setTextColor(isInstalled ? 0xFF81C784 : 0xFFE95420);
  }

  private TextView text(String value, int sp, int color) {
    return UiViews.text(this, value, sp, color);
  }

  private int dp(int value) {
    return UiViews.dp(this, value);
  }
}
