# PocketEnv - Development Environment in Your Pocket

口袋里的开发环境：在 Android 上运行一个完整的 Ubuntu 24.04，免 root、免 Termux，内置 TypeScript/Python/Java/Go/C 等语言编译环境和 OpenCode/Codex/CodeBuddy/QwenCode 等编程智能体。

本项目基于 [biyan113/ubuntu-box](https://github.com/biyan113/ubuntu-box)（MIT 许可）重构开发，增加了大量的易用特性。从 1.0.0 版本开始就已完成**自举**，通过自带的 Git + OpenCode 2 + OpenJDK 17 + Gradle 9 + Android SDK 36 完成自身的代码编辑、编译构建、打包安装、真机运行流水线。

## 功能特性

- 完整 Ubuntu 24.04 环境运行在 Android 上
- 无需 root 权限，无需安装 Termux
- 支持 Ubuntu 系统访问安卓文件系统
- 支持安卓文件管理器直接访问 Ubuntu 文件系统
- 内置 SSH 服务，方便局域网远程访问，锁屏/熄屏不中断
- 支持切换中国大陆友好的软件源（APT、NPM、PyPI 等）
- 内置 Git、Node.js 22、Python 3、OpenJDK 17、Maven 3、Gradle 9、Android SDK 36、Go、C/C++ 等开发环境支持
- 内置 OpenCode、Claude Code、Codex、CodeBuddy、QwenCode 等编程智能体支持

## 构建

```sh
# 下载并校验 Ubuntu Base 24.04.5 arm64
./scripts/fetch-rootfs.sh

# 构建 Debug 版 APK
./gradlew assembleDebug
```

**构建要求：**
- JDK 17
- Android SDK 36
- NDK 仅在修改 `native/termux-jni` 时需要（运行 `native/termux-jni/build.sh` 重新生成 `libtermux.so`。注意合规性，若修改了需按上游的 Apache 2.0 协议进行说明）

## 使用方法

### 首次启动

1. 安装 APK 并打开应用
2. 等待 rootfs 自动解压完成（约 5-10 分钟，视设备性能而定）
3. 自动进入终端界面

### 在文件管理器里访问 Ubuntu

可以在任意支持 SAF 的文件管理器（系统「文件」、MT 管理器等）的存储列表里找到「PocketEnv」或「口袋环境」，浏览和复制文件。

- 除 `/root` 外都是只读的
- `/root` 可直接新建、修改、删除和改名，适合把文件拷进拷出
- rootfs 必须留在应用内部存储，/sdcard 的 FOSE 不支持符号链接和执行权限

### SSH 远程访问

在设置页面可以开启 SSH 服务，支持：
- 密码登录
- 公钥认证
- 查看局域网 IP 地址

开启 SSH 后会自动持有 CPU 唤醒锁和 Wi-Fi 锁，锁屏后也能继续连接。首次开启时建议在设置里开启忽略电池优化，否则 Android 的 Doze 仍可能挂起网络，导致锁屏/熄屏后 SSH 断开连接。

### 额外按键

应用支持以下额外按键：
- ESC / TAB / CTRL / ALT / SHIFT / ENTER
- 方向键
- 功能键（F1-F12）
- 常用符号

## 许可协议

> 合规注意事项：本项目用了 GPL 协议的 proot 动态链接库，主程序没有使用进程隔离手段，可能有被认定为“衍生作品”而非“聚合体”的风险。由于 GPL 协议的传染性，即使你只用其二进制库、一行代码都没抄，只要对外分发，就得提供完整的源代码才没有法律风险。

- **本项目自有代码**：采用国内通行的 MulanPSL v2 许可（见 `LICENSE`），木兰宽松许可证的法律条款以中文为准。
- **ubuntu-box 基线代码**：原仓库声明沿用 DSHA 的 MIT 许可（见 `assets/licenses/DSHA-LICENSE.txt`）。
- **libproot.so / libprootloader.so / libprootloader32.so**：Termux 分支的 [proot](https://github.com/proot-me/proot)，GPL-2.0（见 `assets/licenses/proot-COPYING.txt`），对外分发 APK 时需要按 GPL 提供[对应源码](https://github.com/termux/proot)。
- **libtalloc.so**：来源同 DSHA，原仓库标注“LGPL family; exact binary revision unverified”。
- **libandroidshmem.so**：来源同 DSHA，原仓库标注“unknown origin/license for this original binary”。
- **Termux terminal-view 及 libtermux.so（native/termux-jni/termux.c）**：来源同 DSHA，Apache 2.0（见 `assets/licenses/Termux-terminal-Apache-2.0.txt`），直接用的官方原版 v0.118.0，未做任何修改。
- **随包的其他第三方组件**：分别遵循其许可，不因 APK 顶层许可变成 MulanPSL v2 许可。
