# OpenClaw Launcher

[![Android Build](https://github.com/tangfuhao/Android-Openclaw-Launcher/actions/workflows/android-build.yml/badge.svg)](https://github.com/tangfuhao/Android-Openclaw-Launcher/actions/workflows/android-build.yml)
[![License: GPLv3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

A **fully self-contained** Android app that runs the complete [OpenClaw](https://openclaw.ai/) AI assistant server (Gateway + Agent) locally on your phone, with a native minimal text chat interface and an embedded terminal.

**No server required. No Termux installation. No root.**

[**中文文档 (Chinese)**](docs/README.zh-CN.md)

## How It Works

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
│  │      proot + Debian Linux Environment        │   │
│  │      Node.js 22+ → OpenClaw Gateway          │   │
│  └──────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────┘
                    │ HTTPS
                    ▼
            Claude / GPT / Gemini
```

- **Full Linux environment** — Runs complete Debian via proot; install any package with `apt`
- **Minimal text chat UI** — Built with Compose; text send, streaming Markdown rendering, approval dialogs
- **Embedded terminal** — Integrates Termux's terminal-view for real PTY bash sessions
- **Background persistence** — Foreground Service + WakeLock with optional boot autostart

## Quick Start

### Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | Ladybug (2024.2+) |
| JDK | 17+ |
| Android SDK | API 35 (compileSdk) |

### Build

```bash
git clone git@github.com:tangfuhao/Android-Openclaw-Launcher.git
cd Android-Openclaw-Launcher
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### First Run

1. Install the APK on an Android 7+ device (8 GB+ RAM recommended)
2. The setup wizard automatically downloads the Debian Linux environment (~300 MB rootfs)
3. Enter your AI provider API key (Anthropic / OpenAI / Google)
4. Start chatting

## Features

| Feature | Description |
|---------|-------------|
| Chat | Text send, streaming AI responses, Markdown rendering, history |
| Tool Approval | Confirmation dialog before the agent executes sensitive operations |
| Terminal | Real PTY terminal with direct access to the embedded Linux environment |
| Background Mode | Foreground Service keep-alive with optional boot autostart |
| Process Management | Exponential-backoff auto-restart, health checks, crash recovery |
| Setup Wizard | Device checks, rootfs download progress, API key configuration |
| Material You | Dynamic color on Android 12+, full dark mode support |

## Architecture

The app follows a **five-layer architecture** with clear separation of concerns:

```
┌─────────────────────────────────────────────────┐
│  UI Layer         Jetpack Compose screens        │
├─────────────────────────────────────────────────┤
│  ViewModel Layer  State management + business    │
├─────────────────────────────────────────────────┤
│  Gateway Layer    WebSocket protocol v3 client   │
├─────────────────────────────────────────────────┤
│  Service Layer    Foreground service + process   │
├─────────────────────────────────────────────────┤
│  Proot Layer      Debian rootfs + proot executor │
└─────────────────────────────────────────────────┘
```

## Tech Stack

| Category | Technology | Version |
|----------|-----------|---------|
| Language | Kotlin | 2.1.0 |
| UI | Jetpack Compose + Material 3 | BOM 2024.12.01 |
| DI | Hilt (Dagger) | 2.53.1 |
| Network | OkHttp WebSocket | 4.12.0 |
| Serialization | kotlinx-serialization | 1.7.3 |
| Storage | DataStore Preferences | 1.1.1 |
| Terminal | Termux terminal-view / terminal-emulator | 0.118.1 |
| Build | AGP / Gradle | 8.7.3 / 8.11.1 |

## Project Structure

```
app/src/main/kotlin/com/openclaw/android/
├── core/            Constants, path management
├── proot/           Proot executor, Debian rootfs installation
├── service/         Foreground service, process manager, health monitor
├── gateway/         OpenClaw WebSocket protocol client
├── data/            Data models, DataStore persistence
├── di/              Hilt dependency injection
└── ui/
    ├── chat/        Minimal text chat screen + message bubbles
    ├── terminal/    Terminal tab (Termux TerminalView)
    ├── settings/    Settings screen
    ├── setup/       First-run setup wizard
    ├── components/  Shared UI components
    ├── navigation/  Tab routing
    └── theme/       Material 3 theming
```

## Documentation

The project includes 11 detailed technical docs covering architecture, protocols, and data flows. Ideal for onboarding new contributors.

> **Recommended reading order:** Architecture Overview > Development Guide > Project Structure > others as needed

| Document | Contents |
|----------|----------|
| [Architecture Overview](docs/00-architecture-overview.md) | Five-layer architecture, design decisions, data flow overview |
| [Project Structure](docs/01-project-structure.md) | Directory layout, Gradle config, Manifest, build commands |
| [Embedded Linux Environment](docs/02-bootstrap-linux-environment.md) | proot + Debian rootfs installation, filesystem layout |
| [Service & Process Lifecycle](docs/03-service-process-lifecycle.md) | Foreground Service, process state machine, backoff restart |
| [Gateway WebSocket Protocol](docs/04-gateway-protocol.md) | Protocol v3 frame format, handshake sequence, methods/events |
| [UI Architecture](docs/05-ui-architecture.md) | Compose navigation, ViewModel architecture, message bubbles |
| [Dependency Injection](docs/06-dependency-injection.md) | Hilt configuration, dependency graph, extension guide |
| [Data Flow](docs/07-data-flow.md) | Step-by-step tracing of five core data pipelines |
| [Terminal Integration](docs/08-terminal-integration.md) | Termux library API, PTY sessions, Compose interop |
| [Development Guide](docs/09-development-guide.md) | Environment setup, coding conventions, common tasks, debugging |
| [API Reference](docs/10-api-reference.md) | Complete class/interface/method quick reference |

Full documentation index: [docs/README.md](docs/README.md)

## Design Decisions

**Why proot + Debian?**
proot is a user-space ptrace-based path remapping tool. Running a full Debian rootfs lets the AI Agent install arbitrary Linux tools via `apt` (git, python, make, etc.), providing a truly complete Linux environment rather than a restricted custom runtime.

**Why `targetSdk = 28`?**
Android 10+ enforces W^X restrictions that prevent executing binaries from the app data directory. Setting `targetSdk = 28` bypasses this, allowing proot and all rootfs binaries to execute normally. This means the app is distributed via sideload only (not Google Play).

**Why no Termux dependency?**
Termux's `terminal-emulator` and `terminal-view` are compiled into the APK as JitPack dependencies. The Linux environment is provided by a Debian rootfs archive downloaded on first run.

**Why WebSocket instead of direct calls?**
Reuses the standard OpenClaw Gateway Protocol v3. The Android app connects as an `operator` role via `ws://127.0.0.1:18789` loopback. This maintains compatibility with the OpenClaw ecosystem and enables seamless switching to a remote gateway in the future.

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

Please read the [Development Guide](docs/09-development-guide.md) for coding conventions and project setup.

## License

GPLv3 -- see [LICENSE](LICENSE) for details.
