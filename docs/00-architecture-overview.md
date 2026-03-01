# 00 - 系统架构总览

## 一句话描述

Android OpenClaw 是一个自包含的 Android 应用，内嵌完整 Linux 运行环境，在手机上本地运行 OpenClaw AI Agent Gateway，通过 WebSocket 协议与原生 Compose 聊天界面通信。

## 核心架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                     Android OpenClaw APK                        │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │              UI 层 (Jetpack Compose + Material 3)         │ │
│  │                                                           │ │
│  │  ┌─────────┐   ┌──────────┐   ┌──────────┐              │ │
│  │  │  Chat   │   │ Terminal │   │ Settings │   ← Tab 导航  │ │
│  │  │  Tab    │   │   Tab    │   │   Tab    │              │ │
│  │  └────┬────┘   └────┬─────┘   └──────────┘              │ │
│  │       │              │                                    │ │
│  │       │ WebSocket    │ PTY (伪终端)                       │ │
│  └───────┼──────────────┼────────────────────────────────────┘ │
│          │              │                                       │
│  ┌───────┴──────────────┴────────────────────────────────────┐ │
│  │              Service 层 (Foreground Service)               │ │
│  │                                                           │ │
│  │  ┌────────────────┐  ┌──────────────┐  ┌──────────────┐ │ │
│  │  │ ProcessManager │  │ GatewayClient│  │ HealthMonitor│ │ │
│  │  │ (启动/管理     │  │ (WebSocket   │  │ (健康检查    │ │ │
│  │  │  Node.js 进程) │  │  协议通信)   │  │  自动恢复)   │ │ │
│  │  └────────┬───────┘  └──────────────┘  └──────────────┘ │ │
│  └───────────┼───────────────────────────────────────────────┘ │
│              │                                                  │
│  ┌───────────┴───────────────────────────────────────────────┐ │
│  │         proot + Debian Linux 环境                          │ │
│  │                                                           │ │
│  │  /data/data/com.openclaw.android/files/                   │ │
│  │  ├── rootfs/              ← Debian rootfs (proot --rootfs)│ │
│  │  │   ├── usr/bin/node     ← Node.js 22+                  │ │
│  │  │   ├── usr/bin/bash     ← Shell                         │ │
│  │  │   ├── usr/lib/node_modules/openclaw/ ← Gateway         │ │
│  │  │   └── root/.openclaw/  ← Agent 配置与数据              │ │
│  │  └── proot-tmp/           ← proot 临时目录                │ │
│  └───────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
         │                              ▲
         │ HTTPS (LLM API 调用)          │ 用户交互
         ▼                              │
   Claude / GPT / Gemini             Android 设备
```

## 五层架构

| 层级 | 职责 | 关键组件 |
|------|------|----------|
| **UI 层** | 用户界面渲染与交互 | `ChatScreen`, `TerminalScreen`, `SettingsScreen`, `SetupWizardScreen` |
| **ViewModel 层** | 状态管理与业务逻辑 | `ChatViewModel`, `TerminalViewModel`, `SettingsViewModel`, `SetupViewModel` |
| **Gateway 层** | WebSocket 协议通信 | `GatewayClient`, `ChatApi`, `ApprovalApi`, `GatewayProtocol` |
| **Service 层** | 后台进程生命周期管理 | `OpenClawService`, `ProcessManager`, `HealthMonitor`, `BootReceiver` |
| **Proot 层** | Debian Linux 环境安装与 proot 执行 | `RootfsInstaller`, `ProotExecutor`, `FileDownloader` |

## 关键设计决策

### 1. proot + Debian（完整 Linux 环境）

通过 proot（用户空间 ptrace 路径重映射）运行完整的 Debian rootfs。进程看到标准 FHS 路径（`/usr/bin/node`），proot 透明地将其映射到 `filesDir/rootfs/usr/bin/node`。AI Agent 可通过 apt 安装任意 Linux 工具。

### 2. targetSdk = 28（绕过 W^X）

Android 10+ (API 29) 引入了 W^X (Write XOR Execute) 限制，禁止从 `/data/data/` 执行二进制文件。将 `targetSdk` 设为 28 使 proot 及 rootfs 中的二进制可以正常执行。仅适用于 sideload 分发。

### 3. 单进程自包含

整个 OpenClaw 技术栈（Gateway + Agent Runtime）以 proot 包装的 Node.js 子进程形式运行。UI 通过 `ws://127.0.0.1:18789` 本地回环连接，零网络延迟、零外部依赖。

### 4. Termux 库嵌入（非独立 Termux 应用）

不需要用户安装 Termux。`terminal-emulator`（PTY 管理）和 `terminal-view`（终端渲染 View）通过 JitPack 以 AAR/JAR 依赖编译进 APK。终端会话以 proot 二进制为 Shell 入口，在 proot 包装的 Debian bash 中创建。

### 5. Foreground Service + WakeLock

为保证 Gateway 7x24 运行，使用 Foreground Service 持有通知 + Partial WakeLock 防止 CPU 休眠。配合 `BootReceiver` 实现开机自启。

## 数据流简图

```
用户输入
  │
  ▼
ChatScreen (Compose UI)
  │
  ▼ sendMessage(text)
ChatViewModel
  │
  ▼ chatApi.sendMessage()
ChatApi
  │
  ▼ gateway.request("chat.send", {text, sessionKey})
GatewayClient ──── WebSocket ────► OpenClaw Gateway (Node.js)
                                         │
                                         ▼
                                    LLM API (Claude/GPT)
                                         │
                                         ▼
GatewayClient ◄── WebSocket ──── 流式 chunk 事件
  │
  ▼ chatEvents SharedFlow
ChatViewModel (handleStreamingChunk)
  │
  ▼ _messages StateFlow
ChatScreen (LazyColumn 实时更新)
  │
  ▼
用户看到 AI 回复（支持流式渲染）
```

## 技术栈清单

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.1.0 |
| UI 框架 | Jetpack Compose | BOM 2024.12.01 |
| 设计系统 | Material 3 | (via Compose BOM) |
| 依赖注入 | Hilt (Dagger) | 2.53.1 |
| 注解处理 | KSP | 2.1.0-1.0.29 |
| 网络 | OkHttp | 4.12.0 |
| 序列化 | kotlinx-serialization | 1.7.3 |
| 协程 | kotlinx-coroutines | 1.9.0 |
| 存储 | DataStore Preferences | 1.1.1 |
| 导航 | Navigation Compose | 2.8.5 |
| 终端 | Termux terminal-view / terminal-emulator | 0.118.1 |
| 构建 | AGP / Gradle | 8.7.3 / 8.11.1 |
