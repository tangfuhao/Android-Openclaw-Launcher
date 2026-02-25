# 10 - 源代码 API 参考

## 按包分类的类/接口完整清单

---

## `com.openclaw.android`

### OpenClawApp
- **类型:** `Application` (`@HiltAndroidApp`)
- **职责:** Hilt 根入口，创建通知渠道
- **调用时机:** App 进程启动时由系统创建

### MainActivity
- **类型:** `ComponentActivity` (`@AndroidEntryPoint`)
- **职责:** 唯一 Activity，设置 Compose 内容
- **调用时机:** 用户点击启动器图标或从通知进入

---

## `com.openclaw.android.core`

### OpenClawConstants
- **类型:** `object`（单例）
- **职责:** 集中管理所有常量

| 常量分组 | 关键常量 | 值 |
|----------|----------|-----|
| Gateway | `GATEWAY_HOST` | `"127.0.0.1"` |
| Gateway | `GATEWAY_PORT` | `18789` |
| Gateway | `GATEWAY_WS_URL` | `"ws://127.0.0.1:18789/"` |
| Gateway | `GATEWAY_PROTOCOL_VERSION` | `3` |
| Bootstrap | `BOOTSTRAP_FILE_NAME` | `"bootstrap-aarch64.tar.gz"` |
| Process | `NODE_BINARY` | `"bin/node"` |
| Process | `OPENCLAW_ENTRY` | `"lib/node_modules/openclaw/bin/openclaw.js"` |
| Process | `SHELL_BINARY` | `"bin/bash"` |
| Service | `HEALTH_CHECK_INTERVAL_MS` | `15000` |
| Service | `PROCESS_RESTART_MAX_RETRIES` | `5` |
| Service | `PROCESS_RESTART_BASE_DELAY_MS` | `2000` |

### OpenClawConstants.Paths
- **类型:** `class`（需要 `filesDir: File` 参数）
- **职责:** 根据 app 数据目录派生所有文件路径

| 属性 | 相对路径 | 说明 |
|------|----------|------|
| `root` | `files/` | 根目录 |
| `prefix` | `files/usr/` | $PREFIX |
| `home` | `files/home/` | $HOME |
| `bin` | `files/usr/bin/` | 二进制 |
| `lib` | `files/usr/lib/` | 库文件 |
| `tmp` | `files/usr/tmp/` | 临时 |
| `nodeBinary` | `files/usr/bin/node` | Node.js |
| `openclawEntry` | `files/usr/lib/node_modules/openclaw/bin/openclaw.js` | OpenClaw |
| `shellBinary` | `files/usr/bin/bash` | Shell |
| `openclawConfig` | `files/home/.openclaw/` | 配置 |
| `openclawData` | `files/home/.openclaw/data/` | 数据 |

- **方法:** `ensureDirectories()` — 创建所有必需目录

---

## `com.openclaw.android.data`

### ChatMessage
- **类型:** `@Serializable data class`

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `id` | String | — | 唯一标识 |
| `role` | Role | — | USER / ASSISTANT / SYSTEM |
| `content` | String | — | 消息文本 |
| `timestamp` | Long | `currentTimeMillis()` | 时间戳 |
| `isStreaming` | Boolean | `false` | 是否正在流式生成 |
| `status` | Status | `SENT` | SENDING / SENT / ERROR |

### PreferencesManager
- **类型:** `class`（使用 DataStore Preferences）

| Flow 属性 | 类型 | 说明 |
|-----------|------|------|
| `isBootstrapInstalled` | `Flow<Boolean>` | Bootstrap 是否安装 |
| `isGatewayAutostart` | `Flow<Boolean>` | 是否自动启动 Gateway |
| `isBackgroundEnabled` | `Flow<Boolean>` | 是否启用后台模式 |
| `anthropicApiKey` | `Flow<String>` | Anthropic API key |
| `openaiApiKey` | `Flow<String>` | OpenAI API key |
| `googleApiKey` | `Flow<String>` | Google API key |
| `selectedModel` | `Flow<String>` | 选中的模型 |
| `isSetupCompleted` | `Flow<Boolean>` | 安装向导是否完成 |

| 同步方法 | 返回值 | 使用场景 |
|----------|--------|----------|
| `isBootstrapInstalledSync()` | Boolean | Service / BroadcastReceiver |
| `isBackgroundEnabledSync()` | Boolean | BroadcastReceiver |

---

## `com.openclaw.android.bootstrap`

### BootstrapState (sealed interface)

| 子类型 | 属性 | 说明 |
|--------|------|------|
| `NotInstalled` | — | 未安装 |
| `Checking` | — | 检查中 |
| `Downloading` | `progress: Float`, `bytesDownloaded: Long`, `totalBytes: Long` | 下载中 |
| `Extracting` | `progress: Float` | 解压中 |
| `Configuring` | — | 配置中 |
| `Installed` | — | 已安装 |
| `Error` | `message: String`, `cause: Throwable?` | 错误 |

### BootstrapInstaller

| 成员 | 类型 | 说明 |
|------|------|------|
| `state` | `StateFlow<BootstrapState>` | 安装状态 |
| `isInstalled()` | Boolean | 快速检查 node + bash 是否存在 |
| `install(url, force)` | suspend | 执行完整安装流程 |

### BootstrapDownloader

| 方法 | 签名 | 说明 |
|------|------|------|
| `download` | `suspend (url, destination, onProgress) → File` | HTTP 下载，报告进度 |

### EnvironmentSetup

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `buildEnvironment()` | `Map<String, String>` | 完整环境变量映射 |
| `ensureEnvironment()` | Unit | 创建目录 + profile 脚本 |
| `verifyInstallation()` | `VerificationResult` | 检查关键二进制 |

---

## `com.openclaw.android.gateway`

### GatewayState (sealed interface)

| 子类型 | 属性 | 派生属性 |
|--------|------|----------|
| `Idle` | — | `isConnected=false` |
| `Connecting` | — | `isActive=true` |
| `Handshaking` | — | `isActive=true` |
| `Connected` | `protocol: Int` | `isConnected=true` |
| `Reconnecting` | — | `isActive=false` |
| `Disconnected` | `reason: String?` | `isConnected=false` |
| `Error` | `message: String`, `cause: Throwable?` | `isConnected=false` |

### GatewayClient

| 公开 API | 类型 | 说明 |
|----------|------|------|
| `connectionState` | `StateFlow<GatewayState>` | 连接状态 |
| `events` | `SharedFlow<GatewayEvent>` | 所有事件流 |
| `chatEvents` | `SharedFlow<ChatEventPayload>` | 聊天事件流 |
| `approvalRequests` | `SharedFlow<ApprovalRequestPayload>` | 审批请求流 |
| `connect(host, port)` | 方法 | 发起连接 |
| `disconnect()` | 方法 | 断开连接 |
| `request(method, params)` | suspend → `GatewayResponse` | 请求-响应 |
| `send(method, params)` | 方法 | 发后不管 |

### ChatApi

| 方法 | 签名 | 说明 |
|------|------|------|
| `sendMessage` | `suspend (text, sessionKey) → String` | 发消息，返回 messageId |
| `getHistory` | `suspend (sessionKey, limit, before?) → List<ChatMessage>` | 历史 |
| `observeChatEvents` | `() → Flow<ChatEvent>` | 事件流 |

### ApprovalApi

| 方法 | 签名 | 说明 |
|------|------|------|
| `observeApprovalRequests` | `() → Flow<ApprovalUiRequest>` | 审批请求流 |
| `resolve` | `suspend (requestId, approved, reason?)` | 审批/拒绝 |

---

## `com.openclaw.android.service`

### ProcessManager

| 公开 API | 类型 | 说明 |
|----------|------|------|
| `processState` | `StateFlow<ProcessState>` | 进程状态 |
| `logLines` | `StateFlow<List<String>>` | 日志缓冲 (max 500) |
| `isRunning` | Boolean | 进程是否存活 |
| `startGateway()` | suspend → Boolean | 启动 Gateway |
| `stopGateway()` | 方法 | 停止 Gateway |
| `restartWithBackoff()` | suspend → Boolean | 指数退避重启 |
| `resetRestartCount()` | 方法 | 重置重启计数 |
| `createShellSession()` | → `Process` | 创建 bash 会话 |

### ProcessState (sealed interface)

| 子类型 | 属性 | `displayText` |
|--------|------|---------------|
| `Stopped` | — | "Stopped" |
| `Starting` | — | "Starting..." |
| `Running` | — | "Running" |
| `Restarting` | `attempt: Int` | "Restarting (attempt N)..." |
| `Crashed` | `exitCode: Int` | "Crashed (exit code N)" |
| `Error` | `message: String` | "Error: ..." |

### HealthMonitor

| 方法 | 签名 | 说明 |
|------|------|------|
| `start` | `(scope, onUnhealthy: suspend () → Unit)` | 启动监控 |
| `stop` | `()` | 停止监控 |
| `markHealthy` | `()` | 标记当前时间为健康 |

### OpenClawService

| 静态方法 | 说明 |
|----------|------|
| `startIntent(context)` | 创建启动 Intent |
| `stopIntent(context)` | 创建停止 Intent |

### BootReceiver
- **触发条件:** `ACTION_BOOT_COMPLETED`
- **行为:** 检查 bootstrap + background 设置，满足则启动 Service

---

## `com.openclaw.android.ui`

### ViewModel 汇总

| ViewModel | 注入依赖 | 暴露 StateFlow |
|-----------|----------|---------------|
| `MainViewModel` | `PreferencesManager` | (仅暴露 preferencesManager) |
| `ChatViewModel` | `GatewayClient` | `messages`, `connectionState`, `isLoading`, `pendingApproval` |
| `TerminalViewModel` | `Context`, `BootstrapInstaller`, `Paths`, `EnvironmentSetup` | `bootstrapInstalled`, `fontSize`, `sessionTitle` |
| `SetupViewModel` | `Context`, `BootstrapInstaller`, `PreferencesManager` | `currentStep`, `bootstrapState`, `deviceCheck` |
| `SettingsViewModel` | `PreferencesManager`, `ProcessManager` | `processState`, `backgroundEnabled`, `anthropicKey`, `openaiKey` |

### Composable 汇总

| Composable | 所在包 | 参数 |
|------------|--------|------|
| `MainScreen` | `ui` | `preferencesManager` (默认从 ViewModel) |
| `ChatScreen` | `ui.chat` | `viewModel` (默认 hiltViewModel) |
| `MessageBubble` | `ui.chat` | `message: ChatMessage` |
| `TerminalScreen` | `ui.terminal` | `viewModel` |
| `SettingsScreen` | `ui.settings` | `viewModel` |
| `SetupWizardScreen` | `ui.setup` | `onSetupComplete`, `viewModel` |
| `ConnectionStatusBar` | `ui.components` | `state: GatewayState` |
| `OpenClawTheme` | `ui.theme` | `darkTheme`, `dynamicColor`, `content` |
