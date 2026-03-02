# 04 - Gateway WebSocket 协议

## 概述

Android 端（operator 角色）通过 WebSocket 与本地 OpenClaw Gateway（Node.js 进程）通信。协议版本为 v3，传输格式为 JSON 文本帧。

所有数据类与官方 TypeBox schema 严格对齐，参考文件位于 `reference/openclaw-protocol/`。

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
  "params": { "message": "Hello", "sessionKey": "main", "idempotencyKey": "uuid-yyy" }
}
```

### Response 帧

```json
{
  "type": "res",
  "id": "uuid-xxx",
  "ok": true,
  "payload": { "runId": "run-123", "status": "started" }
}
```

### Event 帧

```json
{
  "type": "event",
  "event": "chat",
  "payload": { ... },
  "seq": 42,
  "stateVersion": { "presence": 1, "health": 1 }
}
```

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
  │        client: {                        │
  │          id: "openclaw-android",        │
  │          version: "0.1.0",              │
  │          platform: "android",           │
  │          mode: "ui"                     │
  │        },                               │
  │        role: "operator",                │
  │        scopes: ["operator.read",        │
  │                 "operator.write",        │
  │                 "operator.approvals",    │
  │                 "operator.admin"],       │
  │        caps: ["tool-events"],           │
  │        auth: {token: <gateway token>}   │
  │      }                                  │
  │                                         │
  │◄──── res: ok ──────────────────────────│
  │      {                                  │
  │        type: "hello-ok",                │
  │        protocol: 3,                     │
  │        server: {version, connId},       │
  │        features: {methods, events},     │
  │        policy: {                        │
  │          tickIntervalMs: 15000,         │
  │          maxPayload, maxBufferedBytes   │
  │        }                                │
  │      }                                  │
  │                                         │
  │◄──── 就绪，开始接收事件 ───────────────│
```

## 方法清单

### 聊天相关

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| `chat.send` | `{message, sessionKey, idempotencyKey, thinking?, attachments?}` | `{runId, status}` | 发送用户消息 |
| `chat.history` | `{sessionKey, limit?}` | `{messages: [...]}` | 获取历史消息 |
| `chat.abort` | `{sessionKey, runId?}` | - | 中止当前 run（内部 API） |
| `chat.inject` | `{sessionKey, message, label?}` | - | 注入系统消息到 transcript |

### 审批相关

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| `exec.approval.resolve` | `{id, decision}` | - | 审批或拒绝（decision: "allow"\|"deny"） |

### Session 管理

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| `sessions.list` | `{limit?, activeMinutes?, search?}` | `{sessions: [...]}` | 列出 session |
| `sessions.reset` | `{key, reason?}` | - | 重置 session（reason: "new"\|"reset"） |
| `sessions.delete` | `{key, deleteTranscript?}` | - | 删除 session |
| `sessions.compact` | `{key, maxLines?}` | - | 压缩 session 历史 |
| `sessions.patch` | `{key, model?, thinkingLevel?, ...}` | - | 修改 session 配置 |
| `sessions.usage` | `{key?, startDate?, endDate?}` | `{...usage data}` | Token 用量统计 |

> **关键**: Session 方法的参数字段名是 `key`（非 `sessionKey`），通过 `@SerialName` 注解对齐。

## 事件清单

### `connect.challenge`

连接后 Gateway 发送的第一个事件，包含握手 challenge。

### `chat`

聊天消息事件，通过 `state` 字段区分阶段：

| state | 含义 |
|-------|------|
| `"delta"` | 流式生成中 |
| `"final"` | 流式完成 |
| `"aborted"` | 已中止 |
| `"error"` | 生成错误 |

内容为 Claude API content blocks 数组（text / tool_use / tool_result），App 以 Operator 视角渲染。

### `agent`

Agent 工具执行事件（需要 caps: `["tool-events"]`）。

### `exec.approval.requested`

Agent 需要执行敏感操作时的审批请求：

```json
{
  "type": "event",
  "event": "exec.approval.requested",
  "payload": {
    "id": "apr-456",
    "command": "bash",
    "commandArgv": ["-c", "rm -rf /tmp/test"],
    "cwd": "/root"
  }
}
```

> **关键**: Approval 事件使用 `id` + `command`（非 `requestId` + `tool`），resolve 使用 `id` + `decision`（非 `requestId` + `approved`）。

### `tick`

心跳事件，包含 `{ts}` 时间戳。客户端监控 tick 间隔，超时未收到则主动断连重连。间隔从 `HelloOk.policy.tickIntervalMs` 获取。

### `shutdown`

Gateway 即将关闭事件：`{reason, restartExpectedMs?}`。客户端据此安排重连延迟。

## 设计决策

### Queue Mode: steer

App 全局配置 `messages.queue.mode: "steer"`。用户发送的新消息在 Agent 运行期间自动注入当前 run，输入框永远不阻塞。

### 无显式 Stop 按钮

中断通过发送文本实现（`/stop`、`stop`、`停止`、`abort` 等），Gateway 内置中断词检测自动处理。`chat.abort` 仅作为内部异常恢复 API。

### Operator 视角渲染

WebSocket 路径收到的是 Agent 原始思维过程（含 tool_use/tool_result），App 以 Operator 视角渲染：
- `tool_use` → 可折叠工具执行卡片
- `tool_result` → 代码块（可折叠）

### 未来远程网关

`GatewayConnectionConfig` 抽象了连接配置（Local/Remote），协议层完全不变，切换远程只需注入 `Remote` 配置。
