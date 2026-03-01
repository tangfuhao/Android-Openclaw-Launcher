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
| Rootfs | `ROOTFS_FILE_NAME` | `"rootfs-aarch64.tar.xz"` |
| Inner paths | `INNER_NODE_BINARY` | `"/usr/bin/node"` |
| Inner paths | `INNER_OPENCLAW_ENTRY` | `"/usr/lib/node_modules/openclaw/bin/openclaw.js"` |
| Inner paths | `INNER_SHELL_BINARY` | `"/usr/bin/bash"` |
| Inner paths | `INNER_HOME` | `"/root"` |
| Service | `HEALTH_CHECK_INTERVAL_MS` | `15000` |
| Service | `PROCESS_RESTART_MAX_RETRIES` | `5` |
| Service | `PROCESS_RESTART_BASE_DELAY_MS` | `2000` |

### OpenClawConstants.Paths
- **类型:** `class`（需要 `filesDir: File` 参数）
- **职责:** 根据 app 数据目录派生所有文件路径（proot 架构）

| 属性 | 相对路径 | 说明 |
|------|----------|------|
| `root` | `files/` | App 文件根目录 |
| `rootfs` | `files/rootfs/` | Debian rootfs (proot --rootfs) |
| `prootTmp` | `files/proot-tmp/` | proot 临时目录 |
| `hostNodeBinary` | `files/rootfs/usr/bin/node` | Node.js (host 路径) |
| `hostShellBinary` | `files/rootfs/usr/bin/bash` | Shell (host 路径) |
| `hostOpenclawEntry` | `files/rootfs/usr/lib/node_modules/openclaw/bin/openclaw.js` | OpenClaw (host 路径) |
| `hostInnerHome` | `files/rootfs/root` | /root (host 路径) |
| `hostOpenclawConfig` | `files/rootfs/root/.openclaw/` | 配置 |
| `hostOpenclawData` | `files/rootfs/root/.openclaw/data/` | 数据 |

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
| `isRootfsInstalled` | `Flow<Boolean>` | Rootfs 是否安装 |
| `isGatewayAutostart` | `Flow<Boolean>` | 是否自动启动 Gateway |
| `isBackgroundEnabled` | `Flow<Boolean>` | 是否启用后台模式 |
| `allApiKeys` | `Flow<Map<ApiProvider, String>>` | 所有 Provider 的 API key map |
| `selectedModel` | `Flow<String>` | 选中的模型 ID |
| `isSetupCompleted` | `Flow<Boolean>` | 安装向导是否完成 |

| 挂起方法 | 签名 | 说明 |
|----------|------|------|
| `setApiKey` | `suspend (provider: ApiProvider, key: String)` | 保存指定 Provider 的 API key |
| `setSelectedModel` | `suspend (model: String)` | 保存选中模型 |
| `setBackgroundEnabled` | `suspend (value: Boolean)` | 设置后台模式 |
| `setSetupCompleted` | `suspend (value: Boolean)` | 标记 Setup 完成 |
| `setRootfsInstalled` | `suspend (value: Boolean)` | 标记 Rootfs 已安装 |

| 同步方法 | 返回值 | 使用场景 |
|----------|--------|----------|
| `isRootfsInstalledSync()` | Boolean | Service / BroadcastReceiver |
| `isBackgroundEnabledSync()` | Boolean | BroadcastReceiver |
| `getProviderConfigsSync()` | `Map<ApiProvider, ProviderConfig>` | `OpenClawConfigWriter`（IO 线程） |
| `getSelectedModelSync()` | String | `OpenClawConfigWriter`（IO 线程） |

**`ApiProvider` 枚举：** `ANTHROPIC`, `OPENAI`, `GOOGLE`, `OPENROUTER`, `MINIMAX_CN`, `ZAI`, `KIMI_CODING`，每个 Provider 持有 `envVarName`、`displayName`、`keyHint`、`defaultModel`、`availableModels`。

---

## `com.openclaw.android.proot`

### OpenClawConfigWriter
- **类型:** `class`
- **职责:** 在 Gateway 启动前后将用户配置（API Key、模型选择）写入 rootfs 内的 `openclaw.json` 和 `auth-profiles.json`

| 方法 | 签名 | 说明 |
|------|------|------|
| `writeConfig` | `()` | 写入 `openclaw.json` 和 `auth-profiles.json`（应在 IO Dispatcher 调用） |
| `getApiKeyEnvVars` | `() → Map<String, String>` | 返回已配置 Provider 的环境变量 map（供 `ProotExecutor` 注入） |

**两次写入机制：** `ProcessManager.startGateway()` 在启动前和启动后各调用一次 `writeConfig()`，因为 Gateway 初始化时会覆盖 `openclaw.json` 的部分字段（如 auth token）。

### RootfsState (sealed interface)

| 子类型 | 属性 | 说明 |
|--------|------|------|
| `NotInstalled` | — | 未安装 |
| `Checking` | — | 检查中 |
| `Downloading` | `progress: Float`, `bytesDownloaded: Long`, `totalBytes: Long` | 下载中 |
| `Extracting` | `progress: Float` | 解压中 |
| `Configuring` | — | 配置中 |
| `Verifying` | — | 验证中 |
| `Installed` | — | 已安装 |
| `Error` | `message: String`, `cause: Throwable?` | 错误 |

### RootfsInstaller

| 成员 | 类型 | 说明 |
|------|------|------|
| `state` | `StateFlow<RootfsState>` | 安装状态 |
| `isInstalled()` | Boolean | 快速检查 node + bash + openclaw 是否存在 |
| `install(url, force)` | suspend | 执行完整安装流程 |

### FileDownloader

| 方法 | 签名 | 说明 |
|------|------|------|
| `download` | `suspend (url, destination, onProgress) → File` | HTTP 下载，报告进度 |

### ProotExecutor

| 成员 | 类型 | 说明 |
|------|------|------|
| `prootBinaryPath` | `String` | proot 二进制路径 (`nativeLibraryDir/libproot.so`) |
| `isAvailable()` | Boolean | 检查 proot 二进制是否存在 |
| `buildCommand(innerCommand, cwd)` | `List<String>` | 构建完整 proot 命令行 |
| `buildEnvironment()` | `Map<String, String>` | proot 宿主进程环境变量 |
| `execute(innerCommand, cwd)` | `Process` | 构建并启动 proot 进程 |
| `executeShell()` | `Process` | 启动 proot 包装的 bash 会话 |

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
- **行为:** 检查 rootfs + background 设置，满足则启动 Service

---

## `com.openclaw.android.ui`

### ViewModel 汇总

| ViewModel | 注入依赖 | 暴露 StateFlow |
|-----------|----------|---------------|
| `MainViewModel` | `PreferencesManager` | （暴露 `preferencesManager` 引用） |
| `ChatViewModel` | `GatewayClient` | `messages`, `connectionState`, `isLoading`, `pendingApproval` |
| `TerminalViewModel` | `Context`, `ProotExecutor`, `RootfsInstaller`, `Paths` | `rootfsInstalled`, `fontSize`, `sessionTitle` |
| `SetupViewModel` | `Context`, `RootfsInstaller`, `PreferencesManager`, `OpenClawConfigWriter` | `currentStep`, `rootfsState`, `deviceCheck` |
| `SettingsViewModel` | `PreferencesManager`, `ProcessManager`, `OpenClawConfigWriter` | `processState`, `backgroundEnabled`, `apiKeys`, `selectedModel` |

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
