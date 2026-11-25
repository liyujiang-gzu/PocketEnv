# UbuntuBox 构建踩坑记录

> 在 aarch64（ARM64）离线工具链上构建本项目时遇到的坑，逐条记录现象、根因与解法，供后续排查参考。
> 环境：aarch64 Linux + Pocket Dev Env 离线工具链（`/opt/android-portable`）、AGP 9.1.0、Gradle 9.3.1、compileSdk 36。

---

## 一、Gradle 版本不匹配：AGP 9.1.0 要求 Gradle 9.3.1

**现象**：首次构建直接失败：

```
Minimum supported Gradle version is 9.3.1. Current version is 8.14.3.
```

**根因**：项目用 AGP 9.1.0，它要求 Gradle ≥ 9.3.1。但 `gradle/wrapper/gradle-wrapper.properties` 里 9.3.1 那行被注释掉、指向了 8.14.3（本地缓存里也只有 8.x）。

**解法**：把 `distributionUrl` 改回 9.3.1。国内下载慢可换腾讯镜像：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-9.3.1-bin.zip
```

> 引申：Pocket Dev Env 原本只内置 Gradle 8.14.3。现已把 9.3.1 一并打包，通过 `POCKET_GRADLE` 环境变量切换（默认 9，`POCKET_GRADLE=8` 回退 8.14.3）。

---

## 二、Gradle 8 自带 init 脚本强制注入仓库

**现象**：用 Gradle 8.14.3 构建时失败：

```
Build was configured to prefer settings repositories over project repositories
but repository 'maven' was added by initialization script '.../gradle-8.14.3/init.d/init.gradle'
```

**根因**：本机 Gradle 8.14.3 被额外塞了一个 `init.d/init.gradle`（上游环境作者加的 Aliyun 镜像注入），它向**所有**工程强制注入仓库，与本项目 `settings.gradle` 里的 `repositoriesMode=FAIL_ON_PROJECT_REPOS` 冲突。Gradle 9.3.1 无此脚本。

**解法**：删除 `gradle-8.14.3/init.d/init.gradle`。镜像仓库应由各项目 `settings.gradle` 或全局 `gradle.properties` 自行配置，不该靠 init 脚本硬注入。

> 注：`FAIL_ON_PROJECT_REPOS` 是 Gradle 的「禁止 init/插件偷偷加仓库」机制，属于安全特性，不该为迁就 init 脚本而关掉。

---

## 三、Release APK 静默丢资源（ARM64 aapt2 bug）★ 最坑

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

## 附：验证产物的常用命令

```bash
# 检查 APK 是否含 manifest / 资源
python3 -c "import zipfile; z=zipfile.ZipFile('app/build/outputs/apk/release/app-release.apk'); \
  n=z.namelist(); print(len(n), 'AndroidManifest.xml' in n, 'resources.arsc' in n)"

# 检查签名 scheme（v1/v2/v3）
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```