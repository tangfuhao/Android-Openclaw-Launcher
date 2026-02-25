# Android OpenClaw

A self-contained Android app that runs [OpenClaw](https://openclaw.ai/) — the open-source personal AI assistant — entirely on your phone.

## Architecture

```
┌─────────────────────────────────────────────────┐
│  Android OpenClaw APK                           │
│                                                 │
│  ┌─────────────────────────────────────┐       │
│  │  Jetpack Compose UI                 │       │
│  │  Chat | Terminal | Settings         │       │
│  └──────────────┬──────────────────────┘       │
│                 │ WebSocket                     │
│  ┌──────────────┴──────────────────────┐       │
│  │  Embedded Linux Environment         │       │
│  │  (Termux-based)                     │       │
│  │                                     │       │
│  │  Node.js 22+ → OpenClaw Gateway     │       │
│  │  ws://127.0.0.1:18789              │       │
│  └─────────────────────────────────────┘       │
└─────────────────────────────────────────────────┘
         │
         │ HTTPS (LLM API calls)
         ▼
   Claude / GPT / Gemini
```

- **No remote server required** — the OpenClaw Gateway runs on your phone
- **Native Android chat UI** — communicates with the local gateway via WebSocket
- **Built-in terminal** — for advanced users to interact with the Linux environment
- **24/7 background mode** — optional foreground service keeps the gateway alive

## Building

Requires:
- Android Studio Ladybug (2024.2+) or later
- JDK 17+
- Android SDK (API 35 for compilation)

```bash
git clone https://github.com/nicebug/android-openclaw.git
cd android-openclaw
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## First Run

1. Install the APK on an Android 7+ device (8GB+ RAM recommended)
2. The setup wizard will download the Linux runtime (~300MB)
3. Enter your AI provider API key (Anthropic/OpenAI/Google)
4. Start chatting!

## Tech Stack

- **Kotlin 2.1** + **Jetpack Compose** + **Material 3**
- **Hilt** for dependency injection
- **OkHttp** WebSocket for Gateway protocol
- **Termux** libraries for terminal emulation
- **kotlinx.serialization** for JSON
- **DataStore** for preferences

## Project Structure

```
app/src/main/kotlin/com/openclaw/android/
├── core/          # Constants, paths
├── bootstrap/     # Linux environment download & setup
├── service/       # Foreground service, process management
├── gateway/       # OpenClaw WebSocket protocol client
├── data/          # Preferences, data models
├── di/            # Hilt dependency injection
└── ui/
    ├── chat/      # Chat interface
    ├── terminal/  # Terminal tab
    ├── settings/  # Configuration
    ├── setup/     # First-run wizard
    └── theme/     # Material 3 theming
```

## License

GPLv3 — see [LICENSE](LICENSE) for details.
