package dev.ubox.pocketenv;

/**
 * 设置页「软件安装」目录：全部工具的声明与安装/卸载/检测命令生成。
 *
 * <p>纯数据 + 纯函数，无 Android 依赖，可单元测试。新增一个可安装工具
 * 只需在 {@link #SECTIONS} 里加一行工厂调用。
 */
final class Toolbox {
  private Toolbox() {}

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

  /** 状态检测脚本里 SSH 相关的固定行。 */
  static final String SSH_STATUS_CHECKS =
      "[ -x /usr/sbin/sshd ] && echo sshd=1 || echo sshd=0\n"
      + "echo sshkeys=$(grep -cE '^(ssh-|ecdsa-|sk-)' /root/.ssh/authorized_keys 2>/dev/null || echo 0)\n"
      + "awk -F: '$1==\"root\"{print \"sshpw=\" (($2 ~ /^[!*]/ || $2 == \"\") ? 0 : 1)}' /etc/shadow\n";

  /** 组装整段状态检测脚本：软件源当前值 + 全部工具 + SSH 状态。 */
  static String statusScript() {
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
    script.append(SSH_STATUS_CHECKS);
    return script.toString();
  }

  /** 按行解析状态脚本输出（仅 key=value 行），工具 id → 是否已安装。 */
  static java.util.Map<String, Boolean> parseInstalled(String output) {
    java.util.Map<String, Boolean> result = new java.util.HashMap<>();
    for (String line : output.split("\n")) {
      int split = line.indexOf('=');
      if (split <= 0) continue;
      String key = line.substring(0, split), value = line.substring(split + 1).trim();
      result.put(key, value.equals("1"));
    }
    return result;
  }
}

