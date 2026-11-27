# 口袋环境开发踩坑记录

> 在 aarch64（ARM64）离线工具链上构建、运行本项目时踩到的坑，逐条记录现象、根因与解法，供后续排查参考。
> 环境：aarch64 Linux + Pocket Dev Env 离线工具链（`/opt/android-portable`）、AGP 9.1.0、Gradle 9.3.1、compileSdk 36。
> 只收「现象与根因严重错位、不点破难以定位」的坑；常规配置项压缩在第一节，安装操作要点见文末附录。

---

## 〇、环境前置配置（非坑，快速过）

构建前两处一次性配置，报错信息自解释，列出备查：

- **Gradle 版本**：AGP 9.1.0 要求 Gradle ≥ 9.3.1，`gradle-wrapper.properties` 的 `distributionUrl` 需指向 9.3.1（报 `Minimum supported Gradle version is 9.3.1` 即此项）。国内慢可换腾讯镜像。Pocket Dev Env 现已内置 9.3.1，`POCKET_GRADLE=8` 可回退 8.14.3。
- **仓库注入**：本机 Gradle 8.14.3 曾被上游塞过 `init.d/init.gradle`（强制向所有工程注入 Aliyun 镜像），与本项目 `repositoriesMode=FAIL_ON_PROJECT_REPOS` 冲突，删掉该脚本即可（报 `repository 'maven' was added by initialization script` 即此项）。`FAIL_ON_PROJECT_REPOS` 是禁止 init/插件偷偷加仓库的安全特性，不应为迁就 init 脚本而关闭。

---

## 一、Release APK 静默丢资源（ARM64 aapt2 bug）★ 最坑

**现象**：`assembleRelease` 报 **BUILD SUCCESSFUL**，但产物 APK 缺 `AndroidManifest.xml`、全部 `res/`、`resources.arsc`——只剩 classes.dex、lib/*.so、assets。这种 APK 装不上或一启动就闪退。`assembleDebug` 一切正常。

**根因**：AGP 9.x 默认对 release 启用**资源优化**（resource path shortening），会跑 `optimizeReleaseResources` 任务。而本机离线工具链用的是 **ARM64 aapt2**（`android.aapt2FromMavenOverride` 指向 build-tools 里的 aarch64 版本），它在优化路径上有 bug：

- `optimizeReleaseResources` 声称成功、耗时约 200ms，但**从未真正调起 `aapt2 optimize`**；
- 它声明的产物 `resources-release-optimize.ap_` **根本不存在**（只有 output-metadata.json）；
- `packageRelease` 消费这个空产物，于是打出没有 manifest/资源的空壳 APK。

debug 没有 `optimizeDebugResources` 任务，直接消费 `processDebugResources` 输出，所以正常。

**佐证**：
- Google IssueTracker [#184666005](https://issuetracker.google.com/issues/184666005)：AGP 9 改了资源优化默认值；
- [revanced/aapt2 issue #36](https://github.com/revanced/aapt2/issues/36)：「optimizeReleaseResources succeeds but writes no resource APK (**ARM64**)」——与本环境完全吻合；
- 受控实验：git stash 掉 build.gradle 的全部改动后，release 依旧丢资源 → **上游既有问题，非本地改动引入**。

**解法**：`gradle.properties` 关闭 release 资源优化：

```properties
android.enableResourceOptimizations=false
```

代价仅是放弃 release 的资源路径混淆——本项目 `minifyEnabled false`，无影响。修复后资源全部恢复。

> 教训：
> 1. **BUILD SUCCESSFUL ≠ 产物正确**。签名/打包类任务可能「成功」却产出残缺 APK，务必用 `apksigner verify` + 检查 zip 条目验证产物。
> 2. ARM64 上很多 x86 工具链的「理所当然」会失效，aapt2 这类原生工具尤甚。
> 3. 增量构建会掩盖问题，排查产物异常先 `clean`。

---

## 二、ARM64 上搭建 Android SDK（platform-36 + build-tools 36.0.0）

> 背景：给设置页新增「Android SDK」安装项，在 aarch64 proot Ubuntu 里装 `platforms;android-36` + `build-tools;36.0.0`。只装编译必需件，不含 system image / source / platform-tools。

**现象/根因**：Google 官方 build-tools 的 linux 包**只发 x86_64 原生工具**（解包核对 36.0.0 全部 ELF 为 x86-64），arm64 上 `aapt2` 直接 `exec format error`（`cannot execute: required file not found`）。而 `platform-36` 是纯 Java/XML 数据（android.jar、stub、模板），架构无关。

**解法**：SDK 骨架仍用官方 zip，再把 AGP 编译时**真正调用**的 4 个原生工具（`aapt2`/`aidl`/`zipalign`/`split-select`）换成 AOSP 源码编的 arm64 版（[Commit451/android-arm-build-tools](https://github.com/Commit451/android-arm-build-tools)，按 build-tools 版本对应 release tag）。其余 x86_64 文件（`aapt`、`dexdump`、renderscript 的 `lld` 等）**全部保留**——AGP 对 build-tools 做存在性检查，删了反而报 `Installed Build Tools revision is corrupted`（见本节坑 3）。

以下踩坑点逐条记录（反直觉、会误导排查的才算坑；纯安装步骤见文末附录 A）：

1. **tar.xz 资产内部的二进制 ≠ 单独下载的资产**（最坑）。该 release 的 `SHA256SUMS` 按「单独二进制资产」给哈希，**不匹配** tar.xz 解压出的同名文件（实测 `aapt2` sha256 不同，`sha256sum -c` 误报 `FAILED`）。解法：**直接下载 4 个单独二进制资产**，逐个用 `SHA256SUMS` 里的值校验，不要用 tar.xz 内容校验。
2. **zipalign 的返回码不可靠**。`zipalign`（无参、`-v`、`-h`、`-c 4`）均返回 2（打印 usage），不能当成功标志；`aapt2 version` 返回 0 才是可靠的运行自检。
3. **AGP 的 build-tools 存在性检查**。AGP 校验 build-tools 时逐个检查 `aapt`/`aapt2`/`aidl`/`dexdump`/`zipalign` 等**文件存在**（不执行）。删掉 x86_64 的 `aapt`、`dexdump` 会报 `is missing AAPT / missing DEXDUMP … is corrupted`。所以官方文件保留、只覆盖替换 arm64 版。
4. **build-tools 的官方 zip 不带 `package.xml`**。AGP 认不出这个包，会尝试下载「缺失版本」并报 license 错误（`Failed to install … build-tools;36.0.0`，实为没安装该版本）。解法：自写 `package.xml`（`localPackage path="build-tools;36.0.0"` + `revision`），**且 `<repository>` 下必须声明 `<license id="android-sdk-license" type="text">`**，否则解析报 `Undefined ID "android-sdk-license"`。**注意：`platforms/android-36` 官方 zip 本来就没有 `package.xml`，不要补**——补了 AGP 解析会因 `platformDetailsType`（`platform/02` 命名空间）不在其 JAXB 上下文而报 `TypeDetails` 无法实例化；platform 靠 `android.jar` + `source.properties` 识别。
5. **licenses 文件首行必须是空行**。`$SDK/licenses/android-sdk-license` 等三个文件首行空行 + 已知 hash（`sdkmanager --licenses` 的产物），否则 AGP 报「licences have not been accepted」。
6. **AGP 9 自带 aapt2 是 x86_64**。必须写用户级 `~/.gradle/gradle.properties`：
   ```properties
   android.aapt2FromMavenOverride=/opt/android-sdk/build-tools/36.0.0/aapt2
   ```
   全局生效，各项目无需再改；AGP 8 及以下不用（仍用 SDK 里的 aapt2）。
7. **arm64 二进制需要 glibc ≥ 2.34**（实测 `aapt2` 最高符号版本 `GLIBC_2.34`）。Ubuntu 22.04+（2.35）/ Debian 12+（2.36）均可；更老的报 `version GLIBC_2.xx not found`。

**验证方法**（本机 aarch64，真实源端到端）：安装后 `aapt2 version` 通过、4 工具确认 `AArch64`、官方文件保留完整、`package.xml`/`licenses/`/`/etc/profile.d/`/`~/.gradle/gradle.properties` 写入正确、`remove` 与重复安装均幂等；并用 `./gradlew :app:assembleDebug`（JDK 17 + Gradle 9.3.1 + AGP 9.1.0 + `ANDROID_HOME=/opt/android-sdk`）**完整构建出 `app-debug.apk`**（31MB，含 rootfs，签名 v2/v3 验证通过，manifest/resources.arsc/classes.dex 齐全）。

---

## 三、增量备份 tar 符号链接循环：`/.l2s`（proot link2symlink）★ 运行时最坑

> 背景：给口袋环境写增量备份脚本 `ubox-backup`——打包 Ubuntu Base 之上新增/改动的文件。首次真机运行，扫描、打包跑了近 4 分钟、归档涨到 2.9G，最后却 `归档失败`。

**现象**：`tar --no-recursion -czf ... -T files` 报满屏：

```
tar: /.l2s/.l2s.tmp_obj_bk2tJG0001: Cannot open: Too many levels of symbolic links
...
tar: Exiting with failure status due to previous errors
归档失败
```

且残留一个 2.9G 的**损坏**归档（无 `.sha256`），白跑一趟。

**根因**：当 Android 存储拒绝创建硬链接时（SELinux 策略，`Proot.hardlinksSupported()` 探测失败），proot 会启用 `--link2symlink`——用**符号链接模拟硬链接**（`dpkg` 依赖硬链接）。它把模拟出的链接集中写到 rootfs 下的 `.l2s` 目录（`-b` bind 进容器、`PROOT_L2S_DIR` 指向它），链接目标是宿主绝对路径：

```
.l2s.index0001 -> /data/.../rootfs/.l2s/.l2s.index0001.0001
```

`/.l2s` 于是既是 bind mount 点、又塞满这类链接。`find /` 照常枚举出这些路径（不在任何虚拟目录黑名单里），交给 tar 后，tar 解引用时撞上链接环，报 `Too many levels`。脚本原本排除了 `/proc`、`/sys`、`/dev`、`/sdcard` 等虚拟/挂载目录，**偏偏漏了 `/.l2s` 这个 proot 私有目录**。

**解法**：在 `ubox-backup` 的两处都补上 `/.l2s`——excludes 清单（供 awk 过滤）和 find 剪枝条件（`-o -path /.l2s`）。另加固 `trap ... EXIT INT TERM HUP`，中断时连半成品归档一起清理。

**验证**：修复后重跑，备份顺利完成，`sha256sum -c` 通过、`tar -t` 列出 76907 条目、归档内 0 条 `l2s` 记录。

> 教训：
> 1. **proot 环境里 `find /` 并不「干净」**。除常规虚拟文件系统（`/proc`、`/sys`、`/dev`），还要主动排除 proot 自建目录 `/.l2s`——它是符号链接环的重灾区，任何「遍历全盘打包/统计」的脚本都会踩。
> 2. **「成功跑很久再失败」比「立刻失败」更贵**。归档 2.9G 才报错，说明错误在打包末端才暴露。写遍历类脚本，先把必然要跳过的路径想全（挂载点、运行时私有目录），或先 `find` 干跑确认清单无异常路径，再进 tar。
> 3. **失败要能自清理**。长任务中途失败若留下半成品（这里是损坏的 2.9G 归档），既占空间又容易被误当有效备份。用 trap 兜底清理，产物只在校验通过后落地。

---

## 附录 A：arm64 SDK 安装操作要点

> 承接第二节，纯安装步骤（非坑），按序操作即可复现。

- **官方 zip 顶层目录名是 AOSP 平台号，不是版本号**。build-tools 36.0.0 的 zip 顶层是 `android-16`（API 36），platform 36 的 zip 顶层是 `android-36`。要重命名到 `build-tools/36.0.0`、`platforms/android-36`。
- **GitHub 资产国内下载慢 + 签名 URL 会过期**。`release-assets.githubusercontent.com` 直链国内实测仅 ~15KB/s（36MB 要约 40 分钟）；带 SAS 签名的直链 `se=` 过期后失效，**不能写死**。源顺序 = `api.github.com/repos/{owner}/{repo}/releases/assets/{id}`（每次换**新鲜**签名直链）→ `gh-proxy.com` → `ghfast.top` → GitHub 直连。实测 gh-proxy 拉 3.3MB 约 6 秒。
- **国内没有现成 arm64 SDK/NDK 包**。gitee / gitcode 上未找到 `android-sdk-linux-arm64` 之类仓库；Ubuntu apt 的 `aapt`/`aidl`/`zipalign` 是 Android 14 时代旧版、且**没有** `aapt2` 包 → 均不可用。

---

## 附录 B：验证产物的常用命令

```bash
# 检查 APK 是否含 manifest / 资源
python3 -c "import zipfile; z=zipfile.ZipFile('app/build/outputs/apk/release/app-release.apk'); \
  n=z.namelist(); print(len(n), 'AndroidManifest.xml' in n, 'resources.arsc' in n)"

# 检查签名 scheme（v1/v2/v3）
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```