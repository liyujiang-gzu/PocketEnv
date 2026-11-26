# UbuntuBox - Pocket Develop Environment

在 Android 上运行一个完整的 Ubuntu 24.04 (arm64) 环境，免 root、免 Termux，用来安装开发环境和运行各种 CLI Agent。

基于 [biyan113/ubuntu-box](https://github.com/biyan113/ubuntu-box)（MIT）二次开发，运行时部分剥离自 [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA)（MIT），只保留了 [proot](https://github.com/proot-me/proot) 运行时、启动参数和 [Termux 终端](https://github.com/termux/termux-app/tree/master/terminal-view)，DSH 相关的插件、备份、补丁体系以及设备自动化功能都去掉了。

## 功能特性

- 完整 Ubuntu 24.04 (arm64) 环境运行在 Android 上
- 无需 root 权限
- 无需安装 Termux
- 支持 Ubuntu 系统访问安卓文件系统（挂载 /sdcard ）
- **支持安卓文件管理器直接访问 Ubuntu 文件系统**
- 内置 SSH 服务，方便远程访问
- 支持切换中国大陆友好的软件源（APT、NPM、PyPI）
- 预置 Git、Node.js 22、Python 3 等开发环境及多种编程智能体的快捷安装支持

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
- NDK 仅在修改 `native/termux-jni` 时需要（运行 `native/termux-jni/build.sh` 重新生成 `libtermux.so`）

构建产物：`app/build/outputs/apk/debug/app-debug.apk`

## 项目结构

| 文件 | 说明 |
|------|------|
| `Rootfs.java` | 首次启动时解压 rootfs 到应用内部存储，写入 DNS、apt、Android 补充组等配置；每次启动时刷新辅助脚本 |
| `RootfsProvider.java` | SAF DocumentsProvider，将 rootfs 暴露为文件管理器里的「UbuntuBox」存储；除 `/root` 外只读 |
| `Proot.java` | 组装 proot 命令行，自动检测是否需要 `--link2symlink`，Android 无权读取某些文件时绑定假数据 |
| `TerminalService.java` | 前台服务，持有所有终端会话，切到后台后继续运行；支持保持 CPU 唤醒 |
| `MainActivity.java` | 安装进度界面、终端界面、额外按键（ESC/TAB/CTRL/ALT/方向键等）、多会话管理 |
| `SettingsActivity.java` | 设置页面，支持换源、安装推荐软件、SSH 管理等功能 |
| `assets/guest/` | 安装到 Ubuntu 内的辅助脚本 |

## 使用方法

### 首次启动

1. 安装 APK 并打开应用
2. 等待 rootfs 自动解压完成（约 5-10 分钟，视设备性能而定）
3. 自动进入终端界面

### 在文件管理器里访问 Ubuntu

菜单 →「授权访问 ubuntu」会打开系统文件管理器并定位到 rootfs。也可以在任意支持 SAF 的文件管理器（系统「文件」、MT 管理器等）的存储列表里找到「UbuntuBox」，浏览和复制文件。

- 除 `/root` 外都是只读的
- `/root` 可直接新建、修改、删除和改名，适合把文件拷进拷出
- rootfs 必须留在应用内部存储，/sdcard 的 FOSE 不支持符号链接和执行权限

### SSH 远程访问

在设置页面可以开启 SSH 服务，支持：
- 密码登录
- 公钥认证
- 查看局域网 IP 地址

### 额外按键

应用支持以下额外按键：
- ESC / TAB / CTRL / ALT
- 方向键
- 功能键（F1-F12）

## 许可

- **本项目二开代码**：采用国内的 MulanPSL 许可（见 `LICENSE`）
- **ubuntu-box 基线代码**：沿用 DSHA 的 MIT 许可（见 `assets/licenses/DSHA-LICENSE.txt`）
- **libproot.so / libprootloader*.so**：Termux proot，GPL-2.0（`assets/licenses/proot-COPYING.txt`）。对外分发 APK 时需要按 GPL 提供对应源码
- **libtalloc.so**：LGPL
- **libandroidshmem.so**：来源同 DSHA，原仓库标注许可未核实
- **Termux terminal-view / terminal-emulator 及 native/termux-jni/termux.c**：Apache-2.0
- **Ubuntu Base**：各软件包各自的许可