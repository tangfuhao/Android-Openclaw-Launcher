# 07 - 数据流与通信链路

## 概述

本文档详细描述数据在各层之间的流动方式，包括：用户消息的发送、AI 回复的接收（含流式）、工具审批、进程状态同步、偏好设置的读写。

## 核心通信链路

### 链路 1：用户发送消息

```
用户点击发送按钮
       │
       ▼
ChatScreen.onClick { viewModel.sendMessage(inputText) }
       │
       ▼
ChatViewModel.sendMessage(text)
  │
  ├── 1. 创建 UserMessage(status=SENDING)，追加到 _messages
  │      → UI 立即显示用户消息（乐观更新）
  │
  ├── 2. viewModelScope.launch {
  │      chatApi.sendMessage(text)
  │   }
  │      │
  │      ▼
  │   ChatApi.sendMessage(text)
  │      │
  │      ▼
  │   GatewayClient.request("chat.send", {text, sessionKey})
  │      │
  │      ├── 生成 UUID id
  │      ├── 创建 CompletableDeferred
  │      ├── pendingRequests[id] = deferred
  │      ├── ws.send(json)  ──────► OpenClaw Gateway (Node.js)
  │      └── deferred.await()
  │                                        │
  │                                        ▼
  │                               Gateway 处理消息
  │                               调用 LLM API
  │                                        │
  │      ◄── ws.receive(res) ──────────────┘
  │      pendingRequests[id].complete(response)
  │      │
  │      ▼
  │   response.ok ? → 返回 messageId
  │
  └── 3. 更新 message.status = SENT (或 ERROR)
         → UI 更新消息状态
```

### 链路 2：接收 AI 流式回复

```
Gateway 开始生成 AI 回复
       │
       ▼ (WebSocket 推送一系列 event 帧)
       
GatewayClient.onMessage(text)
  │
  ├── handleFrame(text) → type=="event", event=="chat"
  │
  ├── dispatchChatEvent(event)
  │   └── json.decode → ChatEventPayload
  │   └── _chatEvents.emit(payload)
  │
  ▼ (SharedFlow)
  
ChatApi.observeChatEvents().collect { ... }
  │
  ├── payload.chunk != null → ChatEvent.Chunk(messageId, delta, done)
  └── payload.message != null → ChatEvent.Message(chatMessage)
  
  ▼ (Flow<ChatEvent>)
  
ChatViewModel (init 时 observeChatEvents().collect)
  │
  ├── ChatEvent.Chunk:
  │   ├── streamingMessages[messageId].append(delta)
  │   ├── _messages 中该 messageId 的消息:
  │   │   ├── 存在 → update(content = buffer, isStreaming = !done)
  │   │   └── 不存在 → 创建新 ASSISTANT 消息(isStreaming = !done)
  │   └── done == true → streamingMessages.remove(messageId)
  │
  └── ChatEvent.Message:
      ├── 已有相同 id → 替换
      └── 没有 → 追加
  
  ▼ (_messages StateFlow 更新)
  
ChatScreen
  └── LazyColumn 重新 compose
      └── MessageBubble(content = "逐字增长", isStreaming = true)
          └── 显示内容 + 小圆形进度条
```

**时间线示例：**

```
t=0ms   : Gateway 推送 chunk {delta: "Hello", done: false}
t=0ms   : _messages 新增 ASSISTANT 消息 "Hello" (isStreaming=true)
t=50ms  : chunk {delta: " world", done: false}
t=50ms  : _messages 更新为 "Hello world" (isStreaming=true)
t=100ms : chunk {delta: "!", done: true}
t=100ms : _messages 更新为 "Hello world!" (isStreaming=false)
t=100ms : streamingMessages 清除该 messageId
```

### 链路 3：工具审批

```
Agent 需要执行敏感操作
       │
       ▼
Gateway 推送 event: exec.approval.requested
  │
  ▼
GatewayClient.handleEvent
  └── dispatchApproval(event)
      └── _approvalRequests.emit(payload)
  
  ▼ (SharedFlow)
  
ApprovalApi.observeApprovalRequests()
  └── map → ApprovalUiRequest(requestId, tool, description)
  
  ▼ (Flow<ApprovalUiRequest>)
  
ChatViewModel (init 时 collect)
  └── _pendingApproval.value = request
  
  ▼ (StateFlow)
  
ChatScreen
  └── pendingApproval != null → 弹出 ApprovalDialog
      │
      ├── [Approve] → viewModel.resolveApproval(id, true)
      │   └── approvalApi.resolve(id, approved=true)
      │       └── gateway.request("exec.approval.resolve", ...)
      │
      └── [Deny] → viewModel.resolveApproval(id, false)
          └── approvalApi.resolve(id, approved=false)
              └── gateway.request("exec.approval.resolve", ...)
```

### 链路 4：进程状态同步

```
ProcessManager.processState (StateFlow<ProcessState>)
       │
       ├──► OpenClawService.observeProcessState()
       │    └── Running → 更新通知 "OpenClaw is running"
       │    └── Crashed → disconnect + restartWithBackoff
       │    └── Error → 更新通知显示错误
       │
       └──► SettingsViewModel.processState (mapped to String)
            └── SettingsScreen 显示状态文字
```

```
GatewayClient.connectionState (StateFlow<GatewayState>)
       │
       ├──► ChatViewModel.connectionState
       │    └── ChatScreen → ConnectionStatusBar
       │    └── 发送按钮启用/禁用
       │
       └──► HealthMonitor.checkHealth()
            └── Connected → markHealthy
            └── Disconnected > 60s → onUnhealthy callback
```

### 链路 5：偏好设置

```
PreferencesManager (DataStore Preferences)
       │
       ├──► isSetupCompleted (Flow<Boolean>)
       │    └── MainScreen → 显示 Setup 或 MainContent
       │
       ├──► isBackgroundEnabled (Flow<Boolean>)
       │    └── SettingsScreen → 后台模式开关
       │    └── BootReceiver → 开机自启判断
       │
       ├──► anthropicApiKey (Flow<String>)
       │    └── SettingsScreen → API key 输入框
       │
       └──► isBootstrapInstalled (Flow<Boolean>)
            └── TerminalScreen → 显示终端或 "未安装" 提示
            └── BootReceiver → 开机自启判断
```

## StateFlow vs SharedFlow

| 类型 | 使用场景 | 特点 |
|------|----------|------|
| `StateFlow` | 状态（有初始值，UI 需要当前值） | 总是有最新值，新订阅者立即收到当前值 |
| `SharedFlow` | 事件（一次性的，不需要重播给新订阅者） | 可配置 `extraBufferCapacity`，防止丢失 |

**本项目的使用：**

| 组件 | StateFlow | SharedFlow |
|------|-----------|------------|
| ProcessManager | `processState`, `logLines` | — |
| GatewayClient | `connectionState` | `events`, `chatEvents`, `approvalRequests` |
| BootstrapInstaller | `state` | — |
| ChatViewModel | `messages`, `isLoading`, `pendingApproval` | — |
| TerminalViewModel | `bootstrapInstalled`, `fontSize`, `sessionTitle` | — |

## 线程模型

| 操作 | 线程 | 原因 |
|------|------|------|
| WebSocket 读写 | OkHttp I/O 线程池 | OkHttp 内部管理 |
| GatewayClient 帧解析 | OkHttp I/O → coroutine (Dispatchers.IO) | 避免阻塞 UI |
| ProcessBuilder.start() | Dispatchers.IO | 文件 I/O |
| Gateway 日志读取 | 专用 daemon thread ("gateway-log-reader") | 持续阻塞式 readline |
| Bootstrap 下载/解压 | Dispatchers.IO | 长时间 I/O |
| UI 状态更新 | Main (via StateFlow) | Compose 必须在主线程 |
| DataStore 读写 | DataStore 内部线程 | 自动管理 |
