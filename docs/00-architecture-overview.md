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
│  │         内嵌 Linux 环境 (app 私有目录)                     │ │
│  │                                                           │ │
│  │  /data/data/com.openclaw.android/files/                   │ │
│  │  ├── usr/                                                 │ │
│  │  │   ├── bin/node          ← Node.js 22+                 │ │
│  │  │   ├── bin/bash          ← Shell                        │ │
│  │  │   └── lib/node_modules/openclaw/  ← OpenClaw Gateway  │ │
│  │  └── home/                                                │ │
│  │      └── .openclaw/        ← Agent 配置与数据             │ │
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
| **Bootstrap 层** | Linux 环境安装与配置 | `BootstrapInstaller`, `BootstrapDownloader`, `EnvironmentSetup` |

## 关键设计决策

### 1. targetSdk = 28（绕过 W^X）

Android 10+ (API 29) 引入了 W^X (Write XOR Execute) 限制，禁止从 `/data/data/` 执行二进制文件。将 `targetSdk` 设为 28 使得在 API 29+ 的设备上仍然可以执行 Node.js 等二进制。这个策略仅适用于 sideload 分发，如果需要上架 Google Play 未来需要采用 `system_linker_exec` 方案。

### 2. 单进程自包含

整个 OpenClaw 技术栈（Gateway + Agent Runtime）以 Node.js 子进程的形式运行在 app 的进程空间内。UI 通过 `ws://127.0.0.1:18789` 本地回环连接，零网络延迟、零外部依赖。

### 3. Termux 库嵌入（非独立 Termux 应用）

不需要用户安装 Termux。`terminal-emulator`（PTY 管理）和 `terminal-view`（终端渲染 View）通过 JitPack 以 AAR/JAR 依赖编译进 APK。终端会话直接在 app 内嵌的 bash 中创建。

### 4. Foreground Service + WakeLock

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
