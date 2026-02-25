# OpenClaw Launcher

一个**完全自包含**的 Android 应用，在手机上本地运行 [OpenClaw](https://openclaw.ai/) AI 助手的完整服务端（Gateway + Agent），并提供原生聊天界面和嵌入式终端。

**无需服务器、无需安装 Termux、无需 Root。**

## 它是怎么工作的

```
┌──────────────────────────────────────────────────────┐
│              OpenClaw Launcher APK                    │
│                                                      │
│  ┌──────────────────────────────────────────────┐   │
│  │      Jetpack Compose UI (Material 3)         │   │
│  │      Chat  |  Terminal  |  Settings          │   │
│  └────────────────┬─────────────────────────────┘   │
│                   │ WebSocket ws://127.0.0.1:18789  │
│  ┌────────────────┴─────────────────────────────┐   │
│  │      内嵌 Linux 环境 (Termux 库)              │   │
│  │      Node.js 22+ → OpenClaw Gateway          │   │
│  └──────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────┘
                    │ HTTPS
                    ▼
            Claude / GPT / Gemini
```

- **全部本地运行** — OpenClaw Gateway 以 Node.js 子进程运行在 app 私有目录中
- **原生聊天界面** — Compose 构建，支持流式消息渲染、工具审批对话框
- **内嵌终端** — 集成 Termux 的 terminal-view，提供真实 PTY bash 会话
- **后台常驻** — Foreground Service + WakeLock，支持开机自启

## 快速开始

### 环境要求

| 工具 | 版本 |
|------|------|
| Android Studio | Ladybug (2024.2+) |
| JDK | 17+ |
| Android SDK | API 35（compileSdk） |

### 构建

```bash
git clone git@github.com:tangfuhao/Android-Openclaw-Launcher.git
cd Android-Openclaw-Launcher
./gradlew assembleDebug
```

APK 产出：`app/build/outputs/apk/debug/app-debug.apk`

### 首次运行

1. 在 Android 7+ 设备上安装 APK（推荐 8GB+ RAM）
2. 安装向导自动下载 Linux 运行环境（~300MB）
3. 输入 AI 提供商的 API Key（Anthropic / OpenAI / Google）
4. 开始对话

## 功能特性

| 特性 | 说明 |
|------|------|
| 聊天界面 | 流式 AI 回复、消息气泡、自动滚动、历史记录加载 |
| 工具审批 | Agent 执行敏感操作前弹出确认对话框 |
| 终端 Tab | 真实 PTY 终端，直接访问内嵌 Linux 环境 |
| 后台运行 | Foreground Service 保活，开机自启（可关闭） |
| 进程管理 | 指数退避自动重启、健康检查、崩溃恢复 |
| 首次向导 | 设备检测、Bootstrap 下载进度、API Key 配置 |
| Material You | Android 12+ 动态配色，完整 Dark Mode 支持 |

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.1.0 |
| UI | Jetpack Compose + Material 3 | BOM 2024.12.01 |
| DI | Hilt (Dagger) | 2.53.1 |
| 网络 | OkHttp WebSocket | 4.12.0 |
| 序列化 | kotlinx-serialization | 1.7.3 |
| 存储 | DataStore Preferences | 1.1.1 |
| 终端 | Termux terminal-view / terminal-emulator | 0.118.1 |
| 构建 | AGP / Gradle | 8.7.3 / 8.11.1 |

## 项目结构

```
app/src/main/kotlin/com/openclaw/android/
├── core/          # 常量、路径管理
├── bootstrap/     # Linux 环境下载、解压、配置
├── service/       # 前台服务、进程管理、健康检查
├── gateway/       # OpenClaw WebSocket 协议客户端
├── data/          # 数据模型、DataStore 持久化
├── di/            # Hilt 依赖注入配置
└── ui/
    ├── chat/      # 聊天界面 + 消息气泡
    ├── terminal/  # 终端 Tab (Termux TerminalView)
    ├── settings/  # 设置页面
    ├── setup/     # 首次运行向导
    ├── components/# 通用 UI 组件
    ├── navigation/# Tab 路由定义
    └── theme/     # Material 3 主题
```

## 技术文档

项目包含 11 份详细的技术文档，覆盖架构、协议、数据流等各个方面。适合新加入的开发者快速理解和上手。

> **新手入门推荐阅读顺序：** 架构总览 → 开发指南 → 项目结构 → 按需阅读其他

| 文档 | 内容 |
|------|------|
| [系统架构总览](docs/00-architecture-overview.md) | 五层架构、设计决策、数据流全景、技术栈 |
| [项目结构与构建系统](docs/01-project-structure.md) | 目录结构、Gradle 配置、Manifest、构建命令 |
| [内嵌 Linux 环境](docs/02-bootstrap-linux-environment.md) | Bootstrap 下载/解压流程、文件系统布局、环境变量 |
| [服务与进程生命周期](docs/03-service-process-lifecycle.md) | Foreground Service、进程状态机、指数退避重启 |
| [Gateway WebSocket 协议](docs/04-gateway-protocol.md) | 协议 v3 帧格式、握手时序、方法/事件清单 |
| [UI 架构与状态管理](docs/05-ui-architecture.md) | Compose 导航、ViewModel 架构、消息气泡设计 |
| [依赖注入](docs/06-dependency-injection.md) | Hilt 配置、依赖关系图、扩展步骤 |
| [数据流与通信链路](docs/07-data-flow.md) | 五条核心链路的逐步数据流跟踪 |
| [终端集成](docs/08-terminal-integration.md) | Termux 库 API、PTY 会话、Compose 互操作 |
| [开发指南](docs/09-development-guide.md) | 环境搭建、编码约定、常见任务、调试技巧 |
| [API 参考](docs/10-api-reference.md) | 所有类/接口/方法的完整速查表 |

完整文档索引见 [docs/README.md](docs/README.md)。

## 关键设计决策

**为什么 `targetSdk = 28`？**
Android 10+ 引入 W^X 限制，禁止从 app 数据目录执行二进制。`targetSdk = 28` 绕过此限制，使 Node.js/bash 等预编译二进制可以直接运行。仅适用于 sideload 分发。

**为什么不需要安装 Termux？**
Termux 的 `terminal-emulator` 和 `terminal-view` 以 JitPack 依赖编译进 APK，不依赖外部应用。Linux 运行环境通过 Bootstrap 压缩包首次运行时下载。

**为什么用 WebSocket 而不是直接调用？**
复用 OpenClaw 标准的 Gateway Protocol v3，Android 端作为 `operator` 角色通过 `ws://127.0.0.1:18789` 本地回环连接。这种方式保持了与 OpenClaw 生态的兼容性，未来可以无缝切换为远程 Gateway。

## License

GPLv3 — see [LICENSE](LICENSE) for details.
