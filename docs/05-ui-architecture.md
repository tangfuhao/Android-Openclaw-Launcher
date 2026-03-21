# 05 - UI 架构与状态管理

## 概述

UI 层使用 Jetpack Compose + Material 3 构建，遵循单 Activity + Navigation Compose 架构。当前聊天前端已经收敛为**极简文本聊天**：只保留文本发送、流式文本渲染、历史加载、连接状态展示和最小审批弹窗。

## 导航结构

```
MainActivity
  └── setContent { OpenClawTheme { MainScreen() } }

MainScreen
  │
  ├── isSetupCompleted == null  → 空白（加载中）
  ├── isSetupCompleted == false → SetupWizardScreen
  └── isSetupCompleted == true  → MainContent
                                    │
                                    └── NavHost
                                        ├── "chat"     → ChatScreen
                                        ├── "settings" → SettingsScreen
                                        └── "terminal" → TerminalScreen
```

说明：
- `ChatScreen` 是默认入口。
- `SettingsScreen` 和 `TerminalScreen` 仍保留，但都作为次级页面进入，不再承担聊天页中的复杂功能入口。
- 不存在聊天页内的命令面板、附件抽屉、语音面板等二级 UI。

## ViewModel 架构

```
┌──────────────────┐     ┌──────────────────┐
│   ChatScreen     │     │   ChatViewModel  │
│   (Composable)   │────►│   (@HiltViewModel)│
│                  │     │                  │
│ collectAsState-  │     │ messages         │
│ WithLifecycle()  │     │ isLoading        │
│                  │     │ connectionState  │
│ sendMessage() ───┼────►│ sendMessage()    │
│ resolveApproval ─┼────►│ resolveApproval()│
└──────────────────┘     └────────┬─────────┘
                                  │
                         ┌────────┴─────────┐
                         │   GatewayClient  │
                         │   (Singleton)    │
                         │                  │
                         │   ChatApi        │
                         │   ApprovalApi    │
                         └──────────────────┘
```

### 数据流规则

1. **单向数据流**：状态从 `ChatViewModel` 推送到 `ChatScreen`，用户输入再回调到 ViewModel。
2. **文本优先**：UI 只处理文本消息；Gateway 中出现的图片、文件、工具活动等非文本 payload 一律忽略，不渲染也不在本地状态中聚合。
3. **审批独立**：`exec.approval.requested` 是唯一保留的非文本交互路径，由独立弹窗处理。
4. **流式更新**：assistant 占位消息会在 `delta / final / aborted / error` 事件中被逐步更新。

## ChatScreen

### 状态来源

| StateFlow | 类型 | 说明 |
|-----------|------|------|
| `messages` | `List<ChatMessage>` | 当前聊天消息列表（纯文本） |
| `connectionState` | `GatewayState` | WebSocket 连接状态 |
| `processState` | `ProcessManager.ProcessState` | Gateway 进程状态 |
| `isLoading` | `Boolean` | 是否正在加载历史 |
| `pendingApproval` | `ApprovalUiRequest?` | 待审批命令 |
| `activeRunId` | `String?` | 当前活跃 run |

### UI 组成

```
┌─────────────────────────────────┐
│ TopAppBar                       │
│  ● 状态点 + OpenClaw + Settings │
├─────────────────────────────────┤
│                                 │
│  ServiceStartupView             │  ← 未 ready 时显示
│  或                              │
│  LazyColumn                     │
│   ├── user bubble               │
│   ├── assistant bubble          │
│   ├── system bubble             │
│   └── loading indicator         │
│                                 │
│  EmptyState                     │  ← 无历史时显示简单提示
│                                 │
├─────────────────────────────────┤
│ [OutlinedTextField          ] [→]│
└─────────────────────────────────┘

┌─ ApprovalDialog ─────────────────┐
│ Approval Required                │
│ bash                             │
│ bash -c ls -la (in /root)        │
│ [Deny]               [Allow]     │
└──────────────────────────────────┘
```

### 输入策略

输入框只支持两类内容：
- 普通文本：直接走 `chat.send`
- 3 个本地命令：
  - `/reset`
  - `/new`（等价于 `/reset`）
  - `/clear`

不会再解析或展示：
- slash command 建议浮层
- 命令面板
- 语音录制
- 图片/文件附件
- 工具活动明细
- 分享 / 删除 / 重试操作

## MessageBubble

### 设计原则

- **用户消息**：右对齐，使用 `primaryContainer`
- **助手 / 系统消息**：左对齐，使用中性色 surface
- **Markdown**：仅助手/系统消息使用 Markdown 渲染
- **长按菜单**：只保留 Copy
- **状态信息**：用户消息底部展示 `Sending` / `Failed`，所有消息展示时间戳
- **流式状态**：assistant 在 `THINKING` 时展示 `Thinking...`，在响应中显示小型 spinner

### 消息模型

聊天 UI 只依赖以下字段：
- `role`
- `contentBlocks`（当前只存 `Text`）
- `runPhase`
- `timestamp`
- `isStreaming`
- `status`
- `runId`

## 其他页面

### TerminalScreen

- 仍通过 `AndroidView` 嵌入 Termux `TerminalView`
- 与聊天页解耦，不再从 ChatScreen 提供快捷入口

### SettingsScreen

- 继续负责模型/API key/后台运行等配置
- 是聊天页右上角唯一保留的扩展入口

### SetupWizardScreen

- 首次安装流程不变
- 完成后进入 `ChatScreen`，但默认只看到极简文本聊天界面
