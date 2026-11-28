# 代码仓库指令文档（Repository Instructions Document）

> 本文件是 PocketEnv 的代码仓库指令，集中记录技术栈、构建、测试、打包等命令与约定。
> 未覆盖事项以概要设计 `docs/HLD.md` 为准；流程与协作约定见 `AGENTS.md`。

---

## 一、项目概览

在 Android（arm64）上运行完整 Ubuntu 24.04 的「口袋开发环境」：免 root、免 Termux，通过 proot 启动 Ubuntu 容器，内置多语言编译环境与编程智能体。基于 [biyan113/ubuntu-box](https://github.com/biyan113/ubuntu-box)（MIT）重构。

- **代码**：双平台同步，gitee 为主库，github 为从库。
- **形态**：单个 Android 应用（`com.android.application`），无多模块。
- **自举**：从 1.0.0 起可在自带的 Ubuntu 环境里完成自身「编辑 → 构建 → 打包 → 安装 → 真机运行」全流水线。

---

## 二、技术栈

| 层面 | 选型 | 版本 |
|---|---|---|
| 语言 | Java | 17（`source/targetCompatibility`） |
| 构建 | Gradle | 9.3.1（wrapper） |
| Android 构建 | AGP | 9.1.0 |
| 编译/目标 SDK | compileSdk / targetSdk | 36（Android 16） |
| 最低支持 | minSdk | 26（Android 8.0） |
| ABI | abiFilters | 仅 `arm64-v8a` |
| 运行时容器 | proot（Termux 分支） | GPL-2.0，随 `jniLibs` 分发 |
| 终端 UI | termux-app `terminal-view` | 0.118.0 |
| 解/压缩 | commons-compress | 1.27.1 |
| 单元测试 | JUnit | 4.13.2 |
| 目标 rootfs | Ubuntu Base | 24.04.5 arm64 |

> 仓库镜像：`settings.gradle` 用 Aliyun 镜像 + `repositoriesMode=FAIL_ON_PROJECT_REPOS`（禁止 init/插件偷偷加仓库，勿关）。Gradle wrapper 走腾讯镜像。

---

## 三、构建要求

- **JDK 17**
- **Gradle 9**（用 wrapper `./gradlew`，勿用系统 gradle）
- **Android SDK 36**，`local.properties` 的 `sdk.dir` 指向之（已 gitignore）
- **NDK**：仅在改动 `native/termux-jni` 时需要
- **rootfs 资产**：`app/src/main/assets/rootfs.tar.gz.bin`（已 gitignore），缺失时 `preBuild` 会失败

---

## 四、常用命令

```sh
# 1) 首次/缺失 rootfs 时：下载并校验 Ubuntu Base 24.04.5 arm64
./scripts/fetch-rootfs.sh

# 2) 构建 Debug APK（日常开发）
./gradlew assembleDebug

# 3) 构建 Release APK（需 keystore.properties 提供签名，已 gitignore）
./gradlew assembleRelease

# 4) 清理
./gradlew clean
```

### 构建前置自检

`preBuild` 依赖 `checkRootfs`：若 `rootfs.tar.gz.bin` 不存在则直接失败，先跑 `./scripts/fetch-rootfs.sh`。

---

## 五、测试

```sh
# 单元测试（app 模块，JUnit 4）
./gradlew :app:testDebugUnitTest

# 单测报告
app/build/reports/tests/testDebugUnitTest/index.html
```

---

## 六、打包与产物验证

```sh
# Release 打包
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

**务必验证产物**（`BUILD SUCCESSFUL ≠ 产物正确`，见 `docs/pitfalls.md`）：

```bash
# 检查 APK 是否含 manifest / 资源
python3 -c "import zipfile; z=zipfile.ZipFile('app/build/outputs/apk/release/app-release.apk'); \
  n=z.namelist(); print(len(n), 'AndroidManifest.xml' in n, 'resources.arsc' in n)"

# 检查签名 scheme（本项目启用 v2/v3，v1/v4 关闭）
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```

---

## 七、签名

- 签名信息放本地 `keystore.properties`（已 gitignore），`app/build.gradle` 读取后配置 release signingConfig。无该文件时 release 不产出有效签名包。
- 签名 scheme：v2 + v3 启用；v1（minSdk 26 用不到）、v4（不 ADB 调试或 Google Play 分发）关闭。

---

## 八、Native（按需）

仅在改动 `native/termux-jni` 时重新编译 `libtermux.so`（16KB 页对齐）：

```sh
ANDROID_NDK_HOME=<ndk路径> ./native/termux-jni/build.sh
# 产物覆盖：app/src/main/jniLibs/arm64-v8a/libtermux.so
```

> 合规：上游 `termux.c` 未修改，Apache 2.0；若改动需按上游协议声明。proot 相关 `.so` 为 GPL-2.0，对外分发 APK 需提供对应源码（见 `README.md` 许可章节）。

---

## 九、关键约定

- **arm64-only**：abiFilters 仅 `arm64-v8a`，packaging 已 exclude x86/x86_64/armeabi-v7a。
- **rootfs 不压缩**：`androidResources.noCompress += ['bin']`，`rootfs.tar.gz.bin` 命名为 `.bin` 防止 aapt 二次压缩。
- **release 资源优化已关闭**：`gradle.properties` 设 `android.enableResourceOptimizations=false`，规避 ARM64 aapt2 bug（详见 `docs/pitfalls.md`）。
- **proot 的 `.so` 必须解压**：`jniLibs.useLegacyPackaging=true`，保证运行时从 `nativeLibraryDir` exec（W^X）。
- **踩坑记录**：构建与运行时的典型坑见 `docs/pitfalls.md`，排查前先查阅。
