# 05 - UI 架构与状态管理

## 概述

UI 层使用 Jetpack Compose + Material 3 构建，遵循单 Activity + Navigation Compose 架构。所有状态通过 Kotlin StateFlow/SharedFlow 从 ViewModel 单向传递到 Composable。

## 导航结构

```
MainActivity
  └── setContent { OpenClawTheme { MainScreen() } }

MainScreen
  │
  ├── isSetupCompleted == null  → 空白（加载中）
  ├── isSetupCompleted == false → SetupWizardScreen
  └── isSetupCompleted == true  → MainContent (底部导航)
                                    │
                                    ├── NavHost
                                    │   ├── "chat"     → ChatScreen
                                    │   ├── "terminal" → TerminalScreen
                                    │   └── "settings" → SettingsScreen
                                    │
                                    └── NavigationBar (3 个 Tab)
                                        ├── Chat     (💬)
                                        ├── Terminal  (⌨)
                                        └── Settings  (⚙)
```

### 导航路由

`Screen` 枚举定义三个 Tab 路由：

```kotlin
enum class Screen(val route: String, val title: String, val icon: ImageVector) {
    CHAT("chat", "Chat", Icons.AutoMirrored.Filled.Chat),
    TERMINAL("terminal", "Terminal", Icons.Default.Terminal),
    SETTINGS("settings", "Settings", Icons.Default.Settings),
}
```

导航行为：底部 Tab 切换使用 `popUpTo(startDestination) { saveState = true }` + `restoreState = true`，确保每个 Tab 的状态在切换时被保存和恢复。

## ViewModel 架构

每个页面有独立的 `@HiltViewModel`，通过 `hiltViewModel()` 在 Composable 中获取。

```
┌──────────────────┐     ┌──────────────────┐
│   ChatScreen     │     │   ChatViewModel  │
│   (Composable)   │────►│   (@HiltViewModel)│
│                  │     │                  │
│  collectAs-      │     │  messages: SF    │
│  StateWith-      │     │  isLoading: SF   │
│  Lifecycle()     │     │  connectionState │
│                  │     │  pendingApproval  │
│  sendMessage()───┼────►│  sendMessage()   │
│  resolveApproval ┼────►│  resolveApproval │
└──────────────────┘     └────────┬─────────┘
                                  │
                         ┌────────┴─────────┐
                         │   GatewayClient  │
                         │   (Singleton)    │
                         │                  │
                         │   ChatApi        │
                         │   ApprovalApi    │
                         └──────────────────┘

SF = StateFlow（UI 通过 collectAsStateWithLifecycle 观察）
```

### 数据流规则

1. **单向数据流**：状态只从 ViewModel → UI，用户操作通过方法调用回到 ViewModel
2. **生命周期感知**：使用 `collectAsStateWithLifecycle()` 收集 Flow，自动在后台停止收集
3. **惰性初始化**：`ChatApi` 和 `ApprovalApi` 在 ViewModel 构造时创建（不是 Hilt 注入）

## 各页面详解

### ChatScreen

**状态来源：**

| StateFlow | 类型 | 说明 |
|-----------|------|------|
| `messages` | `List<ChatMessage>` | 聊天消息列表 |
| `connectionState` | `GatewayState` | WebSocket 连接状态 |
| `isLoading` | `Boolean` | 是否正在加载历史 |
| `pendingApproval` | `ApprovalUiRequest?` | 待审批的工具执行请求 |

**UI 组成：**

```
┌─────────────────────────────────┐
│ ConnectionStatusBar             │  ← 非 Connected 时显示
│ (彩色圆点 + 状态文字)             │
├─────────────────────────────────┤
│                                 │
│  LazyColumn                     │
│  ├── MessageBubble (user)   ──►│  ← 右对齐，深蓝色
│  ├── MessageBubble (assistant) │  ← 左对齐，灰色
│  ├── MessageBubble (streaming) │  ← 带旋转加载指示器
│  └── ...                       │
│                                 │
│  EmptyState (无消息时)           │
│  "🦞 Welcome to OpenClaw"       │
│                                 │
├─────────────────────────────────┤
│ [OutlinedTextField          ] [→]│  ← 输入框 + 发送按钮
│  placeholder: "Message OpenClaw" │
└─────────────────────────────────┘

┌─ ApprovalDialog ─────────────────┐
│ Tool Approval Required           │  ← 弹出对话框
│                                  │
│ send_email                       │
│ Send email to user@example.com   │
│                                  │
│ [Deny]              [Approve]    │
└──────────────────────────────────┘
```

**消息气泡 (MessageBubble) 设计：**

| 属性 | 用户消息 | 助手消息 |
|------|----------|----------|
| 对齐 | 右对齐 | 左对齐 |
| 圆角 | 右下角 4dp（其余 16dp） | 左下角 4dp（其余 16dp） |
| 亮色背景 | `#0D7377` (teal) | `#F0F0F0` (light gray) |
| 暗色背景 | `#14A3A8` | `#2C2C2E` |
| 最大宽度 | 80% 屏幕宽度 | 80% 屏幕宽度 |
| 时间戳 | HH:mm, 右下角 | HH:mm, 右下角 |

**流式消息处理：**

```
ChatViewModel.handleStreamingChunk(chunk):
  │
  ├── 获取/创建 StringBuilder(messageId)
  ├── buffer.append(chunk.delta)
  │
  ├── 消息已存在? → 更新 content = buffer.toString(), isStreaming = !done
  └── 消息不存在? → 创建新 ASSISTANT 消息, isStreaming = !done
  
  ├── done == true → 移除 streamingMessages[messageId]
```

### TerminalScreen

**核心机制：通过 AndroidView 嵌入 Termux 的 TerminalView**

```
TerminalScreen
  │
  ├── bootstrapInstalled == false
  │   └── 显示 "请先完成安装" 提示
  │
  └── bootstrapInstalled == true
      └── EmbeddedTerminalView
          │
          └── AndroidView(factory = {
                  TerminalView(ctx, null).apply {
                      isFocusable = true
                      isFocusableInTouchMode = true
                      viewModel.attachView(this)  ← 关键：绑定 PTY 会话
                  }
              })
```

**TerminalViewModel 职责：**

1. **创建 PTY 会话**：`TerminalSession(shellPath, cwd, args, env, transcriptRows, sessionClient)`
   - `shellPath` = `$PREFIX/bin/bash`
   - `cwd` = `$HOME`
   - `env` = 完整 Linux 环境变量数组
   - `transcriptRows` = 5000（回滚缓冲区行数）

2. **实现 TerminalSessionClient**：处理文本变更、标题变更、剪贴板、光标样式等回调

3. **实现 TerminalViewClient**：处理缩放手势（字体大小 8-32sp）、键盘事件、长按等

4. **生命周期管理**：`onCleared()` 时 `finishIfRunning()` 终止 shell 进程

### SetupWizardScreen

五步向导，使用 `AnimatedContent` 实现步骤间的动画切换：

```
WELCOME
  │ "Get Started"
  ▼
DEVICE_CHECK
  │ 检查 RAM (≥4GB), 存储 (≥2GB), 网络
  │ "Continue" / "Continue Anyway"
  ▼
DOWNLOAD
  │ 下载 + 解压 bootstrap
  │ 进度条 + MB 计数器
  ▼
API_KEY
  │ 输入 Anthropic/OpenAI API key
  │ 自动识别 key 前缀: sk-ant- → Anthropic, sk- → OpenAI
  │ "Save & Continue" / "Skip for Now"
  ▼
COMPLETE
  │ "Start Chatting"
  │ → preferencesManager.setSetupCompleted(true)
```

### SettingsScreen

滚动布局，分为四个卡片段落：

| 段落 | 内容 |
|------|------|
| Gateway | 状态显示 (从 ProcessManager.processState 映射)、后台模式开关 |
| API Keys | Anthropic key 输入框、OpenAI key 输入框（密码遮罩） |
| Storage | 环境大小信息 |
| About | 版本号 (v0.1.0)、许可证 (GPLv3) |

## 主题系统

### 配色方案

| 角色 | 亮色 | 暗色 |
|------|------|------|
| Primary | `#0D7377` (深海蓝绿) | `#4DD9DD` |
| Secondary | `#E85D3A` (暖珊瑚) | `#FFB4A1` |
| Tertiary | `#7C5CBF` (淡紫) | — |
| Background | `#F8FAFA` | `#1A1C1E` |

### 动态颜色

在 Android 12+ (API 31) 上自动使用 Material You 动态颜色（从壁纸提取），低版本回退到自定义配色。

### 连接状态指示色

| 状态 | 颜色 | 色值 |
|------|------|------|
| Running/Connected | 绿色 | `#4CAF50` |
| Connecting | 蓝色 | `#42A5F5` |
| Warning/Reconnecting | 橙色 | `#FFA726` |
| Error/Disconnected | 红色 | `#E53935` |

## 通用 UI 组件

### ConnectionStatusBar

当 WebSocket 未连接时，在 ChatScreen 顶部显示一个紧凑的状态条：

- 彩色圆点（8dp，根据状态变色，有动画过渡）
- 状态文字（如 "Connecting...", "Reconnecting...", "Error: timeout"）
- 连接成功后自动隐藏
