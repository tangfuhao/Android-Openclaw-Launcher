# OpenClaw Launcher

[![Android Build](https://github.com/tangfuhao/Android-Openclaw-Launcher/actions/workflows/android-build.yml/badge.svg)](https://github.com/tangfuhao/Android-Openclaw-Launcher/actions/workflows/android-build.yml)
[![License: GPLv3](https://img.shields.io/badge/License-GPLv3-blue.svg)](../LICENSE)

一个**完全自包含**的 Android 应用，在手机上本地运行 [OpenClaw](https://openclaw.ai/) AI 助手的完整服务端（Gateway + Agent），并提供原生聊天界面和嵌入式终端。

**无需服务器、无需安装 Termux、无需 Root。**

[**English**](../README.md)

## 工作原理

```
┌──────────────────────────────────────────────────────┐
│              OpenClaw Launcher APK                    │
│                                                      │
│  ┌──────────────────────────────────────────────┐   │
│  │      Jetpack Compose UI (Material 3)         │   │
│  │      聊天  |  终端  |  设置                    │   │
│  └────────────────┬─────────────────────────────┘   │
│                   │ WebSocket ws://127.0.0.1:18789  │
│  ┌────────────────┴─────────────────────────────┐   │
│  │      proot + Debian Linux 环境                │   │
│  │      Node.js 22+ → OpenClaw Gateway          │   │
│  └──────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────┘
                    │ HTTPS
                    ▼
            Claude / GPT / Gemini
```

- **完整 Linux 环境** — 通过 proot 运行完整 Debian，支持 `apt` 安装任意软件包
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
2. 安装向导自动下载 Debian Linux 环境（~300MB rootfs）
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
| 首次向导 | 设备检测、Debian rootfs 下载进度、API Key 配置 |
| Material You | Android 12+ 动态配色，完整 Dark Mode 支持 |

## 架构

应用采用**五层架构**，职责分离清晰：

```
┌─────────────────────────────────────────────────┐
│  UI 层            Jetpack Compose 界面           │
├─────────────────────────────────────────────────┤
│  ViewModel 层     状态管理 + 业务逻辑             │
├─────────────────────────────────────────────────┤
│  Gateway 层       WebSocket 协议 v3 客户端       │
├─────────────────────────────────────────────────┤
│  Service 层       前台服务 + 进程管理             │
├─────────────────────────────────────────────────┤
│  Proot 层         Debian rootfs + proot 执行器   │
└─────────────────────────────────────────────────┘
```

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
├── core/            常量、路径管理
├── proot/           proot 执行器、Debian rootfs 安装与管理
├── service/         前台服务、进程管理、健康检查
├── gateway/         OpenClaw WebSocket 协议客户端
├── data/            数据模型、DataStore 持久化
├── di/              Hilt 依赖注入配置
└── ui/
    ├── chat/        聊天界面 + 消息气泡
    ├── terminal/    终端 Tab (Termux TerminalView)
    ├── settings/    设置页面
    ├── setup/       首次运行向导
    ├── components/  通用 UI 组件
    ├── navigation/  Tab 路由定义
    └── theme/       Material 3 主题
```

## 技术文档

项目包含 11 份详细的技术文档，覆盖架构、协议、数据流等各个方面。适合新加入的开发者快速理解和上手。

> **新手入门推荐阅读顺序：** 架构总览 → 开发指南 → 项目结构 → 按需阅读其他

| 文档 | 内容 |
|------|------|
| [系统架构总览](00-architecture-overview.md) | 五层架构、设计决策、数据流全景、技术栈 |
| [项目结构与构建系统](01-project-structure.md) | 目录结构、Gradle 配置、Manifest、构建命令 |
| [内嵌 Linux 环境](02-bootstrap-linux-environment.md) | proot + Debian rootfs 安装流程、文件系统布局 |
| [服务与进程生命周期](03-service-process-lifecycle.md) | Foreground Service、进程状态机、指数退避重启 |
| [Gateway WebSocket 协议](04-gateway-protocol.md) | 协议 v3 帧格式、握手时序、方法/事件清单 |
| [UI 架构与状态管理](05-ui-architecture.md) | Compose 导航、ViewModel 架构、消息气泡设计 |
| [依赖注入](06-dependency-injection.md) | Hilt 配置、依赖关系图、扩展步骤 |
| [数据流与通信链路](07-data-flow.md) | 五条核心链路的逐步数据流跟踪 |
| [终端集成](08-terminal-integration.md) | Termux 库 API、PTY 会话、Compose 互操作 |
| [开发指南](09-development-guide.md) | 环境搭建、编码约定、常见任务、调试技巧 |
| [API 参考](10-api-reference.md) | 所有类/接口/方法的完整速查表 |

完整文档索引见 [docs/README.md](README.md)。

## 关键设计决策

**为什么用 proot + Debian？**
proot 是一个用户空间的 ptrace 路径重映射工具。通过运行完整的 Debian rootfs，AI Agent 可以使用 `apt` 安装任意 Linux 工具（git、python、make 等），提供真正的完整 Linux 环境而非受限的定制运行时。

**为什么 `targetSdk = 28`？**
Android 10+ 引入 W^X 限制，禁止从 app 数据目录执行二进制。`targetSdk = 28` 绕过此限制，使 proot 及 rootfs 中的二进制可以正常执行。这意味着应用仅通过 sideload 分发（不上架 Google Play）。

**为什么不需要安装 Termux？**
Termux 的 `terminal-emulator` 和 `terminal-view` 以 JitPack 依赖编译进 APK，不依赖外部应用。Linux 运行环境通过 Debian rootfs 压缩包在首次运行时下载。

**为什么用 WebSocket 而不是直接调用？**
复用 OpenClaw 标准的 Gateway Protocol v3，Android 端作为 `operator` 角色通过 `ws://127.0.0.1:18789` 本地回环连接。这种方式保持了与 OpenClaw 生态的兼容性，未来可以无缝切换为远程 Gateway。

## 参与贡献

1. Fork 本仓库
2. 创建特性分支（`git checkout -b feature/amazing-feature`）
3. 提交更改（`git commit -m 'Add amazing feature'`）
4. 推送到分支（`git push origin feature/amazing-feature`）
5. 创建 Pull Request

请阅读[开发指南](09-development-guide.md)了解编码规范和项目配置。

## 许可证

GPLv3 — 详见 [LICENSE](../LICENSE)。
