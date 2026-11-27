# 概要设计文档（High-Level Design Document）

> PocketEnv 的概要设计：分层、模块职责、关键设计决策与不变式。
> 命令与技术栈见 `docs/RID.md`；流程协作见 `AGENTS.md`；典型坑见 `docs/pitfalls.md`。

---

## 一、架构总览

PocketEnv 是单模块 Android 应用，在 arm64 设备上通过 **proot** 运行完整 Ubuntu 24.04 容器。宿主 App 只负责「组装 proot 命令行 + 管理会话生命周期 + 暴露文件给 SAF」，真正的 Linux 环境是解压到应用内部存储的 rootfs。

```
┌───────────────────────────── UI 层 ─────────────────────────────┐
│ MainActivity(终端)  SettingsActivity(设置)  LicensesActivity      │
│ ui/SshSection  ui/Theme  ui/UiViews  ui/EdgeToEdge               │
└───────────────┬─────────────────────────────────┬───────────────┘
                │ 绑定/启动                          │ 读写
┌───────────────▼──────────── runtime 层 ─────────▼───────────────┐
│ TerminalService(前台服务/会话容器)                                │
│   ├─ TerminalSession 列表（termux terminal-view）                 │
│   └─ SshManager(sshd 生命周期)                                    │
│ UbuntuProcess(guest 命令统一入口) → Proot(argv/env 组装)           │
│ Rootfs(目录布局/解压/启动前修正)                                   │
└───────────────┬─────────────────────────────────┬───────────────┘
                │                                  │
┌───────────────▼───── provider 层 ────────────────▼─── model 层 ──┐
│ RootfsProvider(SAF DocumentsProvider)      Toolbox(工具安装声明)   │
│                                            MirrorOption(软件源)   │
└──────────────────────────────────────────────────────────────────┘
                        底层：解压到 filesDir/ubox 的 Ubuntu rootfs
```

---

## 二、分层与模块职责

### runtime（核心，与 Android 强耦合）
| 类 | 职责 |
|---|---|
| `Rootfs` | 容器目录布局、首次解压安装、每次启动前刷新随版本更新的 guest 脚本、生成增量备份基线清单 |
| `Proot` | 组装进入容器的命令行与环境：硬链接探测与降级、bind 挂载宿主目录、重建干净的 guest 环境 |
| `UbuntuProcess` | 进入容器执行命令的统一入口：非交互一次性执行、后台长驻进程两种形态 |
| `TerminalService` | 前台服务，持有所有终端会话；保证应用退到后台后会话仍运行；SSH 委托给 `SshManager` |
| `SshManager` | sshd 进程的启动/停止/守候/兜底清理，独立于终端会话；状态变化回调刷新前台通知 |

### provider
- `RootfsProvider`（`DocumentsProvider`）：把 rootfs 通过 SAF 暴露给系统文件管理器。除 `/root` 外只读（见下方「关键决策 3」）。

### model（纯数据 + 纯函数，无 Android 依赖，可单测）
- `Toolbox`：可安装工具的声明与安装/卸载/状态检测命令生成；新增工具只需声明一条配置。
- `MirrorOption`：APT/NPM/PyPI/GO 四套软件源的「描述/选择/应用」同构逻辑收敛。

### ui
- `MainActivity`（终端界面）、`SettingsActivity`（设置分区）、`LicensesActivity`（许可）。
- 辅助：`SshSection`（SSH 分区 UI）、`Theme`/`UiViews`/`EdgeToEdge`（外观与系统栏）。

### util
- `Async`：后台任务与 UI 回调的轻量封装。

---

## 三、关键设计决策

1. **rootfs 存应用内部存储，经 SAF 暴露，而非放共享存储**。
   共享存储的 FUSE 不支持符号链接和执行权限，proot 无法在共享存储上运行。故容器文件保存在应用私有目录，另开 `RootfsProvider` 供文件管理器按需浏览。

2. **容器运行时用 proot，非 root、非内核级虚拟化**。
   以普通 App 权限获得「假 root」的 Ubuntu 体验，免刷机、免 Termux。代价是 ptrace 带来的性能开销与运行时产物（见 `docs/pitfalls.md`）。

3. **SAF 暴露面最小化且只读**。
   文件管理器改写容器系统文件会破坏 apt/dpkg 状态，故只放开用户目录 `/root` 可写。

4. **所有 guest 执行收敛到统一入口**。
   终端会话、一次性命令、后台 sshd 三条路径共用同一套「组装容器命令行 + 注入环境」逻辑，避免各自手写导致环境不一致。

5. **模型层保持无 Android 依赖**。
   工具安装声明、软件源选项等是纯数据 + 纯函数，UI 只消费其产出的命令，便于单元测试与复用。

6. **guest 脚本随版本刷新，rootfs 数据不重置**。
   随 APK 更新的辅助脚本在每次会话启动前刷新；用户环境与数据保持不动，兼顾「可升级」与「不丢数据」。

---

## 四、关键流程

### 4.1 首次安装
```
打开应用 → 解压 Ubuntu rootfs 到应用私有目录 → 初始化基础配置 → 标记安装完成
```
解压先在独立的暂存目录完成，全部成功后再启用，中途失败不会留下半套环境。

### 4.2 启动终端会话
```
终端界面 → 绑定前台服务 → 准备容器环境（刷新脚本、组装启动命令）→ 创建会话 → 更新前台通知
```

### 4.3 执行一次性命令（设置页安装工具/切换软件源）
```
设置页发起 → 组装容器命令 → 非交互执行并合并输出 → 结果回传 UI
```

### 4.4 SSH
```
设置页开启 SSH → 后台启动 sshd（独立于终端会话）→ 输出写日志 → 持有唤醒/WiFi 锁保活
```

---

## 五、不变式与约束

架构层面必须维持的约束（构建配置级细节见 `docs/RID.md` 第九节）：

- **ABI 仅 arm64-v8a**：只面向 arm64 Android 设备，不维护多架构。
- **proot 原生库必须解压到 `nativeLibraryDir` 才能执行**（W^X 约束）：这决定了打包方式与 rootfs 必须落在应用私有存储。
- **容器与宿主的边界只在 proot 一处**：所有进入 guest 的执行（终端会话、一次性命令、sshd）都必须经过 `Proot` 组装，禁止绕过它直接 spawn。
- **SAF 暴露面最小化**：对外只暴露 rootfs，且除 `/root` 外只读，防止文件管理器破坏容器内部一致性。
- **guest 环境与宿主隔离**：guest 环境变量由 `env -i` 重建，不继承宿主 App 进程环境。
- **rootfs 一旦安装即归用户管理**：宿主只在首次安装时做初始化配置，之后不再改动用户数据；仅 guest 脚本随版本刷新。

---

## 六、演进方向 / 已知取舍

- **单模块**：当前所有代码在 `:app`，规模尚小（16 个 Java 文件）。若 runtime 层继续膨胀，可考虑拆分 `:runtime` 为独立 module，但现阶段 YAGNI。
- **proot 性能**：ptrace 带来开销，I/O 密集任务（如 `find /`、大 tar 打包）明显；已在 `ubox-backup` 等脚本中通过精确排除目录缓解。
- **Android 版本适配**：前台服务类型 `specialUse`、MANAGE_EXTERNAL_STORAGE 等受版本与厂商策略影响，需真机持续验证（见 `TODO.md`）。
