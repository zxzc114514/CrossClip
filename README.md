# CrossClip 跨端轻量极速剪贴板互传系统 - 项目完工描述文档

## 一、项目定位与交付综述

CrossClip 是一套面向个人多设备协同场景打造的高性能、低延迟、去中心化跨端剪贴板双向同步系统。系统专注于解决 Windows 电脑与 Android 手机在局域网内剪贴板纯文本的高频即时流转痛点（系统专注于字符、代码与链接文本，明确不支持图片与文件传输），在保障绝对隐私与传输安全的前提下，实现了无感秒级同步、极低系统资源占用与稳定可靠的后台常驻。

项目最终交付的核心可执行产物包括：
1. **Windows 电脑端**：[CrossClip.exe](CrossClip.exe)（Rust 原生单文件静态编译，零外部运行环境依赖）；
2. **Android 手机端**：[CrossClip.apk](CrossClip.apk)（v1.2.0，集成官方 V1/V2 开发者签名的正式发布包）。

---

## 二、核心架构与技术实现

系统整体采用去中心化对等通信架构，在无任何第三方公网服务器中转的纯局域网环境下完成自发现与加密互传，杜绝隐私数据泄露。

### 1. Windows 电脑端（Rust 原生工程）
- **开发语言与运行时**：采用 Rust 语言与 Win32 原生 API 实现，无 Electron 庞大依赖，内存常驻仅约 5~8 MB；
- **原生剪贴板事件监听**：基于 Windows 剪贴板观察者机制（`AddClipboardFormatListener`），结合 15 毫秒原生事件让渡，在极速捕获剪贴板变更的同时，避免与 Office、浏览器等高频操作软件产生锁竞争；
- **单实例系统互斥保障**：入口处绑定系统全局命名互斥体 `Local\CrossClipSingleInstanceMutex`，重复双击启动的新实例在 1 毫秒内静默退出，根除多次启动导致的托盘图标堆叠与端口冲突；
- **系统关机广播即时放行**：消息循环搭载顶层无父无边框隐藏窗口，接收系统关机广播 `WM_QUERYENDSESSION` 即时返回 `1` 同意关机，收到 `WM_ENDSESSION` 毫秒级有序退出；
- **后台托盘与自省管理**：系统托盘菜单支持实时动态刷新本机局域网 IP、持久化记忆 6 位配对 PIN 码、一键重新生成随机 PIN 码、开机自启配置与安全退出。

### 2. Android 手机端（Kotlin 原生工程）
- **UID 2000 独立 Shell 守护进程（`ClipShellDaemon`）**：开机启动一次 Shizuku 后，自动通过 `nohup app_process` 启动脱离父进程组的独立守护进程 `ClipShellDaemon`（运行于 `com.android.shell` UID 2000，监听 `127.0.0.1:18239`）。即便手机离开局域网导致系统自动关闭无线调试、Shizuku 自身关机退出，`ClipShellDaemon` 依然常驻存活并保持 100% 后台无感剪贴板监听、读取与带回读比对强校验的写入；
- **Wi-Fi 断开与连回毫秒级重连**：通过 `ConnectivityManager.NetworkCallback` 实时捕获局域网状态变更。离开局域网瞬间立即切断半开 SSE 连接并保留历史电脑 IP（`lastSavedPcIp`）；回到局域网瞬间优先对历史电脑 IP 发起单点密文挑战握手，并同步触发双发 UDP 广播脉冲与子网扫描，结合电脑端无活跃客户端时每 8 秒一次的 `/wakeup` 主动唤醒，实现秒级无感重连；
- **写入回读强校验机制**：无论是通过 `ClipShellDaemon` 还是 Shizuku Binder 反射写入系统剪贴板，每次调用 `setPrimaryClip` 后均立即执行回读比对，确认系统剪贴板真实文本与写入内容一致才返回成功，杜绝伪成功漏写；
- **出站长连接保活设计**：移动端主动向电脑端发起出站 Server-Sent Events（SSE）长连接通道（服务端 15 秒心跳、客户端 25 秒读超时快速识别半开断线），规避定制 Android 系统在息屏待机状态下对局域网入站 TCP 握手包的拦截。

### 3. 全链路密文挑战鉴权与端到端加密
- **零明文/零哈希泄露自发现**：UDP 局域网广播（端口 `18234`）与 `/ping` 接口仅返回设备名、设备 ID 与服务端口，绝不暴露 `pin_hash`；
- **AES-256-GCM 挑战令牌鉴权**：`/auth`、`/heartbeat` 与 `/events`（`X-Auth-Cipher` 请求头）全链路废除明文 PIN 与静态哈希比对，强制校验包含毫秒级时间戳的 AES-256-GCM 加密挑战令牌（`CROSSCLIP_AUTH:<timestamp>`，5 分钟防重放窗口）；
- **SHA-256 双向防回环校验**：双端均内置近期内容哈希环形队列，对刚接收并写入本地剪贴板的内容进行瞬时过滤，杜绝双向无限反射循环。

---

## 三、手机端界面设计（v1.2.0 极简精密仪表台风格）

1. **一体化主控卡片**：融合品牌标题、实时连接状态、在线电脑选择槽位与动态配对栏。配对成功后自动收起 PIN 码输入框与手动 IP 输入区，切换为清爽的加密同步就绪横幅与断开连接按钮。
2. **同步引擎与系统特权面板**：集中展示全自动无感互传开关及 Shell 剪贴板守护状态，清晰区分展示“特权直连 + 独立守护双激活”与“独立守护运行中（免疫断网）”状态。
3. **低频设置与诊断抽屉折叠**：将后台省电策略、系统自启动、多任务卡片加锁指引、最近任务隐藏、常驻通知静默以及运行日志诊断工具收纳进两个可展开/收起的折叠抽屉，保持首屏清爽克制。

---

## 四、最终交付文件结构清单

```text
cross_clip/
├── CrossClip.exe                   # Windows 电脑端正式可执行程序 (Rust 单文件，单实例互斥，平滑关机)
├── CrossClip.apk                   # Android 手机端正式安装包 (v1.2.0，versionCode 19，V1/V2 签名校验通过)
├── README.md                       # 本交付总结与系统架构说明文档
├── PROJECT_HANDOVER.md             # 跨会话项目交接与技术阐述文档
├── config.json                     # 电脑端本地运行配置文件 (设备 ID、持久化 PIN 码、历史手机 IP 与偏好)
│
├── rust_desktop/                   # Windows 电脑端 Rust 完整源代码
│   ├── Cargo.toml                  # 依赖清单 (tiny_http, windows-sys, aes-gcm, mdns-sd 等)
│   └── src/
│       ├── main.rs                 # 程序入口、Win32 托盘循环、单实例互斥锁与动态 IP 刷新
│       ├── server.rs               # 多线程 HTTP/SSE 服务端、AES-GCM 挑战令牌校验与 8 秒主动唤醒守护
│       ├── clipboard.rs            # 原生剪贴板监听器与防泄漏内容写入
│       ├── crypto.rs               # AES-256-GCM 加密解密与 SHA-256 计算
│       ├── config.rs               # 本地配置持久化载入与 PIN 码生成
│       ├── ip_util.rs              # 物理局域网 IP 过滤与解析工具
│       ├── udp_discovery.rs        # UDP 广播自发现响应服务 (零敏感字段泄露)
│       └── mdns.rs                 # mDNS 网络服务广播
│
├── android/                        # Android 手机端 Kotlin 原生工程源代码
│   ├── build.gradle.kts            # 根工程构建配置
│   ├── settings.gradle.kts         # 子模块配置
│   └── app/
│       ├── build.gradle.kts        # 应用模块构建脚本 (v1.2.0 / versionCode 19 / Release 签名)
│       └── src/main/
│           ├── AndroidManifest.xml # 清单配置 (前台服务、网络权限、BootReceiver 开机自启)
│           ├── java/com/crossclip/app/
│           │   ├── CrossClipApp.kt                   # 全局 Application 入口与异常监控
│           │   ├── receiver/BootReceiver.kt          # 开机广播自启接收器
│           │   ├── service/SyncForegroundService.kt  # 核心前台服务、NetworkCallback 秒连与剪贴板调度
│           │   ├── shizuku/ClipShellDaemon.kt        # UID 2000 独立常驻剪贴板守护进程 (127.0.0.1:18239)
│           │   ├── shizuku/ShizukuClipboardManager.kt# 双通道剪贴板静默读写与回读强校验引擎
│           │   ├── shizuku/ShizukuPrivilegeHelper.kt # Shizuku 权限注入与 ClipShellDaemon 进程管理
│           │   ├── network/HttpUploader.kt           # 密文挑战令牌构造与 HTTP 同步执行器
│           │   ├── network/SseClient.kt              # 带动态密文 Header 与 25 秒超时自愈的 SSE 客户端
│           │   ├── network/LanDiscovery.kt           # UDP 双发脉冲与单点优先探测引擎
│           │   ├── network/LocalHttpServer.kt        # 本地 18237 端口唤醒与守护直推接收服务
│           │   ├── network/NsdHelper.kt              # 原生 mDNS 发现辅助
│           │   ├── crypto/CryptoUtil.kt              # AES-256-GCM 手机端加解密实现
│           │   ├── util/PermissionHelper.kt          # 系统省电与自启权限跳转工具
│           │   ├── util/DebugLogger.kt               # 本地日志记录与导出工具
│           │   └── ui/MainActivity.kt                # 极简主控台界面与折叠抽屉交互逻辑
│           └── res/
│               └── layout/activity_main.xml          # 三层视觉层级主界面布局
│
└── .agents/rules/
    └── crossclip_engineering.md    # 跨端工程质量与签名互斥确定性规范
```
