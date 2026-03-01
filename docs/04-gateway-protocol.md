# 04 - Gateway WebSocket 协议

## 概述

Android 端（operator 角色）通过 WebSocket 与本地 OpenClaw Gateway（Node.js 进程）通信。协议版本为 v3，传输格式为 JSON 文本帧。

## 连接地址

```
ws://127.0.0.1:18789/
```

所有通信都在设备回环网络内完成，零网络延迟，不需要加密（TLS）。

## 帧类型

所有帧共享一个 `type` 字段用于区分：

| type | 方向 | 用途 |
|------|------|------|
| `"req"` | Client → Gateway | 请求（方法调用） |
| `"res"` | Gateway → Client | 响应（方法结果） |
| `"event"` | Gateway → Client | 推送事件 |

### Request 帧

```json
{
  "type": "req",
  "id": "uuid-xxx",
  "method": "chat.send",
  "params": { "text": "Hello", "sessionKey": "main" }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | string | 固定 `"req"` |
| `id` | string | 唯一请求 ID（UUID），用于匹配响应 |
| `method` | string | 方法名 |
| `params` | object | 方法参数 |

### Response 帧

```json
{
  "type": "res",
  "id": "uuid-xxx",
  "ok": true,
  "payload": { "messageId": "msg-123" }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | string | 固定 `"res"` |
| `id` | string | 对应请求的 ID |
| `ok` | boolean | 是否成功 |
| `payload` | any? | 成功时的返回数据 |
| `error` | object? | 失败时的错误信息 `{code, message}` |

### Event 帧

```json
{
  "type": "event",
  "event": "chat",
  "payload": { "chunk": { "messageId": "m1", "delta": "Hello ", "done": false } },
  "seq": 42
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | string | 固定 `"event"` |
| `event` | string | 事件名称 |
| `payload` | any | 事件数据 |
| `seq` | number? | 序列号（可选） |

## 连接握手流程

```
Client                                   Gateway
  │                                         │
  │───── WebSocket Connect ────────────────►│
  │                                         │
  │◄──── event: connect.challenge ─────────│
  │      {nonce: "abc", ts: 17xxxxx}       │
  │                                         │
  │───── req: connect ─────────────────────►│
  │      {                                  │
  │        minProtocol: 3,                  │
  │        maxProtocol: 3,                  │
  │        client: {id, version, platform}, │
  │        role: "operator",                │
  │        scopes: ["operator.read",        │
  │                 "operator.write",        │
  │                 "operator.approvals",    │
  │                 "operator.admin"],       │
  │        auth: {token: <gateway token>}   │
  │      }                                  │
  │                                         │
  │◄──── res: ok ──────────────────────────│
  │      {protocol: 3, policy: {...}}       │
  │                                         │
  │◄──── 就绪，开始接收 chat/approval 事件 ─│
```

### 握手参数详解

```kotlin
ConnectParams(
    minProtocol = 3,             // 最低支持协议版本
    maxProtocol = 3,             // 最高支持协议版本
    client = ClientInfo(
        id = "openclaw-android", // 客户端标识
        version = "0.1.0",       // App 版本
        platform = "android",    // 平台
        mode = "cli",            // 客户端模式
    ),
    role = "operator",           // 连接角色
    scopes = listOf(             // 请求的权限范围
        "operator.read",         // 读取消息
        "operator.write",        // 发送消息
        "operator.approvals",    // 审批工具执行
        "operator.admin",        // 管理权限
    ),
    auth = AuthInfo(
        token = "<从 openclaw.json 读取的 gateway auth token>",
    ),
    device = null,               // Android 客户端不使用设备签名
)
```

**Auth Token 机制：** Gateway 首次启动时会在 `root/.openclaw/openclaw.json` 中生成并写入一个 auth token。`GatewayClient` 在握手前读取该文件中的 `gateway.auth.token` 字段，并将其作为认证凭据发送。

## 方法清单

### 聊天相关

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| `chat.send` | `{text, sessionKey}` | `{messageId}` | 发送用户消息 |
| `chat.history` | `{sessionKey, limit, before?}` | `{messages: [...]}` | 获取历史消息 |
| `chat.subscribe` | `{sessionKey}` | (none) | 订阅聊天事件流 |

### 审批相关

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| `exec.approval.resolve` | `{requestId, approved, reason?}` | (none) | 审批或拒绝工具执行 |

## 事件清单

### `connect.challenge`

连接后 Gateway 发送的第一个事件，包含握手 challenge。

```json
{"type":"event", "event":"connect.challenge", "payload":{"nonce":"abc123", "ts":1708000000}}
```

### `chat`

聊天消息事件，通过 `state` 字段区分阶段：

**流式 delta（流式生成中，state = "delta"）：**
```json
{
  "type": "event",
  "event": "chat",
  "payload": {
    "runId": "run-123",
    "sessionKey": "main",
    "seq": 5,
    "state": "delta",
    "message": {
      "role": "assistant",
      "content": "Hello"
    }
  }
}
```

**流式完成（state = "final"）：**
```json
{
  "type": "event",
  "event": "chat",
  "payload": {
    "runId": "run-123",
    "sessionKey": "main",
    "state": "final",
    "message": {
      "role": "assistant",
      "content": "Hello! How can I help?",
      "timestamp": 1708000000
    }
  }
}
```

**错误（state = "error"）：**
```json
{
  "type": "event",
  "event": "chat",
  "payload": {
    "runId": "run-123",
    "state": "error",
    "errorMessage": "LLM API rate limit exceeded"
  }
}
```

`ChatApi.observeChatEvents()` 将这些原始 payload 映射为密封类 `ChatEvent.Delta`、`ChatEvent.Final`、`ChatEvent.Error`。

### `exec.approval.requested`

Agent 需要执行敏感操作时的审批请求：

```json
{
  "type": "event",
  "event": "exec.approval.requested",
  "payload": {
    "requestId": "apr-456",
    "tool": "send_email",
    "description": "Send email to user@example.com",
    "params": {"to": "user@example.com", "subject": "..."}
  }
}
```

## GatewayClient 实现细节

### 请求-响应匹配

每个 `request()` 调用会：
1. 生成唯一 UUID 作为 `id`
2. 创建 `CompletableDeferred<GatewayResponse>` 存入 `pendingRequests` 哈希表
3. 发送 JSON 帧
4. `await()` 等待响应

当收到 `type:"res"` 帧时，用 `id` 从 `pendingRequests` 取出对应的 `Deferred` 并 `complete()`。

```
pendingRequests: ConcurrentHashMap<String, CompletableDeferred<GatewayResponse>>

request("chat.send", params)         收到 res {id: "uuid-xxx", ok: true}
  │                                           │
  ├── id = UUID.random()                      │
  ├── pendingRequests["uuid-xxx"] = deferred  │
  ├── ws.send(json)                           │
  ├── deferred.await() ◄─────────────────────┘
  └── return response                   pendingRequests.remove("uuid-xxx")
```

### 自动重连

```
连接断开（非正常关闭 code != 1000）
  │
  ├── reconnectAttempt++
  ├── 超过 10 次? → state = Error, 停止重连
  │
  ├── state = Reconnecting
  ├── delay = 3000ms * min(attempt, 5)
  │   (3s, 6s, 9s, 12s, 15s, 15s, ...)
  │
  └── connect() 重新发起 WebSocket 连接
```

### 事件分发

`handleEvent()` 根据 `event` 名称将事件路由到不同的 SharedFlow：

| 事件 | 目标 Flow | 消费者 |
|------|-----------|--------|
| `connect.challenge` | (内部处理) | `performHandshake()` |
| `chat` | `chatEvents: SharedFlow<ChatEventPayload>` | `ChatApi.observeChatEvents()` |
| `exec.approval.requested` | `approvalRequests: SharedFlow<ApprovalRequestPayload>` | `ApprovalApi.observeApprovalRequests()` |
| (其他) | `events: SharedFlow<GatewayEvent>` | 通用事件监听 |

## 上层 API 封装

### ChatApi

`ChatApi` 在 `GatewayClient` 之上提供类型安全的聊天操作：

```kotlin
class ChatApi(private val gateway: GatewayClient) {
    // 发消息，返回消息 ID
    suspend fun sendMessage(text: String, sessionKey: String = "main"): String

    // 获取历史，返回 List<ChatMessage>（domain model）
    suspend fun getHistory(sessionKey: String, limit: Int, before: String?): List<ChatMessage>

    // 订阅实时事件流，转换为 domain ChatEvent
    fun observeChatEvents(): Flow<ChatEvent>
}
```

`ChatEvent` 密封接口：
- `Delta(runId, content)` — 流式增量（state = "delta"）
- `Final(runId, message)` — 流式完成（state = "final"）
- `Error(runId, message)` — 生成错误（state = "error"）

### ApprovalApi

```kotlin
class ApprovalApi(private val gateway: GatewayClient) {
    // 监听审批请求
    fun observeApprovalRequests(): Flow<ApprovalUiRequest>

    // 审批或拒绝
    suspend fun resolve(requestId: String, approved: Boolean, reason: String?)
}
```
