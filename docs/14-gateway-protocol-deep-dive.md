# 14 - Gateway 协议深度分析：WebSocket 通道 vs IM 通道

## 概述

本文档基于对 OpenClaw 官方源码的逐行分析，记录 Android 端通过 WebSocket `chat.send` 对接 Gateway 与 Telegram 等 IM 通道在**程序逻辑层面**的本质区别，以及 Session、Queue Mode、中断机制等关键概念的工作原理。

## 1. 两条路径的汇合点

无论消息来自 WebSocket `chat.send` 还是 Telegram Bot API，最终都调用同一个函数：

```
dispatchInboundMessage(ctx: MsgContext, cfg, dispatcher, replyOptions)
```

两条路径的区别在于：**MsgContext 如何构建**、**ReplyDispatcher 如何投递回复**。

```
WebSocket chat.send               Telegram grammY
       │                                │
 构建精简 MsgContext               构建丰富 MsgContext
 Provider="internal"              Provider="telegram"
 CommandAuthorized=true           CommandAuthorized=基于allowFrom
       │                                │
       └──────────┬─────────────────────┘
                  │
        dispatchInboundMessage()
                  │
           ┌──────┴──────┐
           │             │
      / 命令检测      Agent Run (Pi)
           │             │
     命令处理器      流式生成回复
           │             │
      ReplyDispatcher    │
           │             │
    ┌──────┴──────┐   ┌──┴───────────────┐
    │             │   │                  │
  WS broadcast  Telegram API  WS broadcast  Telegram API
  chat event    sendMessage   chat event    editMessage
```

## 2. 六个维度的逻辑差异

### 2.1 消息上下文（MsgContext）

chat.send 构建的 MsgContext 只有约 15 个字段，而 Telegram 通道构建的有 50+ 个字段。

| 字段 | chat.send | Telegram |
|------|-----------|----------|
| `Provider` / `Surface` | `"internal"` 固定值 | `"telegram"` |
| `ChatType` | 永远 `"direct"` | `"direct"` 或 `"group"` |
| `CommandAuthorized` | 永远 `true` | 基于 allowFrom 列表检查 |
| `From` / `To` | 无 | Telegram user ID / chat ID |
| `WasMentioned` | 无 | 群组中 @bot 提及检测 |
| `InboundHistory` | 无 | 群组最近 N 条消息历史 |
| `ReplyToBody` / `ReplyToSender` | 无 | 用户回复引用的消息内容 |
| `ForwardedFrom` | 无 | 转发来源信息 |
| `MediaPath` / `MediaType` | 附件以 base64 传入 | 自动下载到本地的媒体文件路径 |
| `Sticker` | 无 | 贴纸 emoji、set name、描述 |
| `MessageThreadId` | 无 | Telegram 论坛话题 ID |

**影响**：Agent 收到的系统提示不同。Telegram 路径会注入群组历史、回复上下文、转发信息、媒体理解结果等，Agent 拥有更丰富的上下文。chat.send 路径只有纯文本和可选附件。

### 2.2 回复内容差异（最重要的差异）

这是体验差异最大的地方：WebSocket 客户端看到的是 Agent 的**原始思维过程**，而 Telegram 用户看到的是**经过处理的人类友好回复**。

**WebSocket 路径（chat.send）**：

当 Agent 被调用时（`agentRunStarted=true`），Pi Agent Runtime 通过 WebSocket 广播 `chat` event 和 `agent` event。

**重要**：消息体格式是 OpenClaw Transcript 的归一化格式，**不是** Claude API 的原始格式。具体差异见第 8 节。

`chat` event (delta/final) 的 `message.content` 中，文本以 `{"type":"text"}` 块传递。工具调用过程通过独立的 `agent` event（`stream: "tool"`）实时推送。

`chat.history` 返回的历史消息中，工具调用以 `{"type":"toolCall"}` 块存在于 assistant 消息的 content 数组中，工具结果是独立的 `role: "toolResult"` 消息。

客户端需要自行解析这些结构并决定如何渲染。

**Telegram 路径**：

Agent 的输出经过 ReplyDispatcher 的三层回调过滤：

```
onBlockReply(payload)   → 只包含 text，用于流式更新 Telegram 消息
onToolResult(payload)   → 工具结果摘要（群组中可被 suppress 掉）
sendFinalReply(payload) → 最终人类可读的回复文本
```

Telegram 用户看到的是 Agent "消化"后的输出——"我查看了目录，里面有以下文件：..."，而不是原始的 `ls -la` 输出。

**对 Android App 的含义**：

采用 **Operator 视角**：将工具调用渲染为可折叠的 `AgentActivitySection`（"Used N tools"），支持展开查看每个 tool 的名称、输入参数、执行结果。Live 执行和历史加载使用统一的 `ToolActivity` 数据模型和 UI 组件。

### 2.3 Session Key 计算

| 来源 | Session Key |
|------|-------------|
| chat.send(sessionKey="main") | 客户端指定，规范化为 `"agent:default:main"` |
| Telegram 私聊 | **同上** — 所有非群组直接对话折叠到 main session |
| Telegram 群组 -100123 | `"agent:default:telegram:group:-100123"` |
| Telegram 群组 -100123 话题 42 | `"agent:default:telegram:group:-100123:topic:42"` |

Telegram 私聊和 chat.send 的 main session **是同一个 session**。它们共享对话历史和配置。

### 2.4 媒体处理

| 能力 | chat.send | Telegram |
|------|-----------|----------|
| 输入 | base64 附件 | 自动下载 → 格式检测 → OCR/转录 → 上下文注入 |
| 输出 | content block 中的文本/URL | bot.api.sendPhoto / sendDocument / sendVoice |
| TTS | 不处理 | 可选 ElevenLabs 语音合成 |
| 贴纸 | 不支持 | 完整处理（emoji、set name、缓存描述） |

### 2.5 群组逻辑（chat.send 完全不涉及）

Telegram 有大量 chat.send 不存在的群组特有逻辑：@提及检测、群组激活模式（mention/always）、群组历史注入、话题隔离、顺序化处理、打字指示器、消息确认反应、4096 字符分块。

chat.send 的 `ChatType` 永远是 `"direct"`，不涉及任何群组逻辑。

### 2.6 Thinking 级别注入

chat.send 支持通过 `thinking` 参数透明注入思考级别。Gateway 自动在消息前添加 `/think <level>` 前缀：

```typescript
// chat.ts
const injectThinking = Boolean(p.thinking && trimmedMessage && !trimmedMessage.startsWith("/"));
const commandBody = injectThinking ? `/think ${p.thinking} ${parsedMessage}` : parsedMessage;
```

Telegram 用户必须手动输入 `/think high`。

## 3. Session 概念详解

### 3.1 三层结构

```
Session = SessionEntry（元数据） + Transcript（对话记录） + Runtime State（运行时状态）
```

**SessionEntry** 存储在 `~/.openclaw/sessions.json`：

```json
{
  "agent:default:main": {
    "sessionId": "uuid-xxx",
    "updatedAt": 1708000000000,
    "sessionFile": "transcript.jsonl",
    "thinkingLevel": "high",
    "model": "claude-opus-4-6",
    "modelProvider": "anthropic",
    "inputTokens": 15000,
    "outputTokens": 8000,
    "totalTokens": 23000,
    "compactionCount": 2,
    "queueMode": null,
    "sendPolicy": "allow"
  }
}
```

SessionEntry 包含：模型选择、thinking/verbose/reasoning 级别、token 统计、压缩计数、队列模式、执行安全策略、投递上下文等。每个 `/think` 或 `/model` 命令本质上是修改这个 entry。

**Transcript** 是 JSONL 格式的对话记录文件，每行一个消息。Agent 每次被调用时读取此文件作为 LLM 对话历史。

### 3.2 Session 生命周期

| 操作 | 触发方式 | 效果 |
|------|---------|------|
| 创建 | 首次收到消息 | 生成 sessionId + transcript 文件 |
| 重置 | `/reset`、`/new`、`sessions.reset` | 生成新 sessionId，原 transcript 归档，开始全新对话 |
| 压缩 | `/compact`、`sessions.compact` | Agent 总结历史，压缩 transcript，减少 token 消耗 |
| 删除 | `sessions.delete` | 删除 entry + 归档 transcript |
| 老化清理 | 自动 | 30 天未活动自动修剪，最多保留 500 个 session |

### 3.3 对 Android App 的含义

对于本地自包含场景，只需要 `"main"` session。清空上下文用 `/reset`，压缩上下文用 `/compact`。不需要多 session 管理 UI。

## 4. Queue Mode 与并发消息

### 4.1 协议层不阻塞

chat.send 的处理是**立即返回 ACK + 异步执行**：

```typescript
// 立即返回
respond(true, { runId: clientRunId, status: "started" });
// 异步执行（fire-and-forget）
void dispatchInboundMessage({...}).then(...).catch(...);
```

客户端可以连续发送多个 chat.send 请求，每个都会立即收到 `{ runId, status: "started" }` 响应。

### 4.2 Agent 层排队

虽然协议不阻塞，但 Pi Agent Runtime 对同一 session 串行处理。当 Agent 正在运行时发送新消息，新消息的处理方式由 Queue Mode 决定。

### 4.3 Queue Mode 详解

Queue Mode 的解析优先级：

```
消息内联指定 > SessionEntry.queueMode > 按通道配置 > 全局配置 > 默认值（"collect"）
```

默认值是 `"collect"`。各模式行为：

| 模式 | 行为 | 适用场景 |
|------|------|---------|
| **`collect`**（默认） | 收集所有排队消息，Agent 当前 run 结束后合并为一个批次处理 | 用户快速连续发多条消息 |
| `steer` | 新消息作为"引导"注入当前运行中的 Agent，不启动新 run | 用户想补充/修正正在执行的任务 |
| `steer+backlog` | steer 当前 run + 保留 backlog 在 run 结束后处理 | steer 和 followup 的混合 |
| `followup` | 等当前 run 结束后逐条处理排队消息 | 每条消息需要独立回复 |
| `queue` | 严格排队，逐条串行处理 | 严格顺序执行 |
| `interrupt` | 中断当前 run，立即处理新消息 | 需要即时响应 |

**Android App 策略**：`OpenClawConfigWriter` 在 `openclaw.json` 中全局写入 `"steer"` 模式。客户端不阻塞输入框，用户随时可发新消息，新消息自动注入当前运行中的 Agent。

```json
{ "messages": { "queue": { "mode": "steer" } } }
```

Steer 注入原理：Pi Agent 的工具循环中包含多次 LLM 调用，`queueMessage(text)` 将新消息追加到 session transcript，模型在下一次 LLM 推理时看到它，在同一个 run 内调整行为。如果 run 恰好结束（`isStreaming=false`），消息自动回退到 followup 队列。

### 4.4 可配置性

用户可通过 `/queue` 命令切换模式（如 `/queue collect`、`/queue interrupt`）。

## 5. 中断（Abort）机制

Android App **不提供显式 Stop 按钮**。中断通过以下方式实现：

**方式一：发送中断触发词**

Gateway 内置中断词检测，用户直接在输入框发送即可：

```
/stop, stop, 停止, abort, wait, exit, interrupt, esc ...
```

Gateway 自动中断所有活跃 run，保存部分输出到 transcript（不丢失），返回 `{ ok: true, aborted: true }`。

**方式二：`/stop` 命令**

作为 App 命令系统的一部分，`/stop` 是最明确的中断方式。

**方式三：`chat.abort` WS 方法（保留为内部 API）**

```json
{"type": "req", "id": "uuid", "method": "chat.abort", "params": {"sessionKey": "main"}}
```

App 内部可在异常恢复等场景调用，不暴露为 UI 按钮。

## 6. 协议合规性问题

经与官方 TypeBox schema 逐字段对比，当前 Android 端有以下不匹配：

| 问题 | 严重程度 | 详情 |
|------|---------|------|
| Session 方法字段名 | 致命 | `sessions.patch/reset/delete/compact` 官方用 `key`，当前用 `sessionKey` |
| Approval Resolve 参数 | 致命 | 官方 `{id, decision}`，当前 `{requestId, approved}` |
| Approval Request payload | 高 | 官方 `{id, command}`，当前 `{requestId, tool, description}` |
| Tick 心跳监控 | 中 | 官方客户端监控 tick 事件并超时断连，当前未实现 |
| Seq 间隙检测 | 低 | 官方客户端检测事件序号跳跃，当前未实现 |

### 6.1 字段映射（关键修复项）

**sessions.ts schema**:

```
SessionsPatchParamsSchema:   { key, thinkingLevel?, verboseLevel?, reasoningLevel?, responseUsage?, model?, label?, ... }
SessionsResetParamsSchema:   { key, reason? }
SessionsDeleteParamsSchema:  { key, deleteTranscript? }
SessionsCompactParamsSchema: { key, maxLines? }
```

**exec-approvals.ts schema**:

```
ExecApprovalResolveParamsSchema: { id, decision }     // decision: "allow" | "deny"
ExecApprovalRequestParams:       { id, command, commandArgv?, cwd?, ... }
```

## 7. 官方协议参考文件

以下 TypeBox schema 文件是协议的权威来源，已提取到 `reference/openclaw-protocol/`：

| 文件 | 定义内容 |
|------|---------|
| `frames.ts` | 帧格式、ConnectParams、HelloOk、ErrorShape |
| `sessions.ts` | Session 方法参数（key 字段） |
| `logs-chat.ts` | ChatSend/History/Abort/Event schema |
| `agent.ts` | Agent 事件 schema |
| `exec-approvals.ts` | Approval 参数（id + decision） |
| `client-info.ts` | 客户端 ID/Mode/Caps 枚举 |

来源：`https://github.com/openclaw/openclaw/tree/main/src/gateway/protocol/schema/`

## 8. 消息体实测格式（Transcript 归一化层）

### 8.1 两层协议与抽象泄漏

Gateway 协议存在两个抽象层：

| 层级 | 覆盖范围 | 是否有 schema | 稳定性 |
|------|---------|-------------|--------|
| Gateway 帧协议 | 帧格式、RPC、事件信封 | TypeBox schema（严格） | 稳定 |
| 消息体内容格式 | tool 调用/结果的字段名和结构 | `Type.Unknown()`（不定义） | 无契约 |

`AgentEventSchema.data` 和 `ChatEventSchema.message` 均为 `Type.Unknown()`，意味着协议层**故意不承诺**消息体的内部结构。

Telegram 路径不受影响——ReplyDispatcher 将 Agent 输出过滤为纯文本后投递，Telegram Bot 不接触内部格式。WebSocket 路径（Android App）选择了 Operator 视角，必须直接消费这些无契约的内部格式。

### 8.2 实测字段映射

以下映射基于 adb logcat 实测（OpenClaw v1.x，2026-03），非 Claude API 原始格式：

**chat.history 返回的历史消息**：

```
消息结构：扁平数组，每条消息有 role 字段
├── role: "user"        → content: [{"type":"text", "text":"..."}]
├── role: "assistant"   → content: [{"type":"thinking", ...}, {"type":"toolCall", ...}] 或 [{"type":"text", ...}]
├── role: "toolResult"  → 独立消息，keys: [role, toolCallId, toolName, content, isError, timestamp]
└── role: "assistant"   → content: [{"type":"text", "text":"最终回复"}]
```

toolCall 块字段：

| 字段 | Claude API 格式 | OpenClaw Transcript 实际格式 |
|------|----------------|---------------------------|
| 类型标识 | `"type": "tool_use"` | `"type": "toolCall"` |
| 工具 ID | `"id"` | `"id"` |
| 工具名 | `"name"` | `"name"` |
| 输入参数 | `"input": {}` | `"arguments": {}` |

toolResult 消息字段：

| 字段 | Claude API 格式 | OpenClaw Transcript 实际格式 |
|------|----------------|---------------------------|
| 存在形式 | 嵌入 assistant content 数组 | 独立消息 `role: "toolResult"` |
| 关联 ID | `"tool_use_id"` | `"toolCallId"` |
| 结果内容 | `"content"` (text/array) | `"content"` (array) |
| 错误标记 | `"is_error"` | `"isError"` |

**agent event（stream: "tool"）实时工具事件**：

```json
// start 阶段
{"phase":"start", "name":"exec", "toolCallId":"call_function_xxx", "args":{...}}

// result 阶段
{"phase":"result", "name":"exec", "toolCallId":"call_function_xxx", "meta":"摘要文本", "isError":false}
```

| 字段 | 之前假设 | 实际值 |
|------|---------|-------|
| 工具名 | `data.toolName` | `data.name` |
| 工具 ID | `data.toolId` / `data.id` | `data.toolCallId` |
| 输入参数 | `data.input` | `data.args` |
| 结果内容 | `data.content` / `data.output` | `data.meta` |

### 8.3 用户消息中的 Transcript 前缀注入

`chat.history` 返回的 `role: "user"` 消息内容可能被 Gateway 注入系统事件和时间戳前缀。这是 OpenClaw Transcript 归一化层的行为——它将系统通知（如命令执行结果）和消息时间戳直接拼接进消息正文。

**实测示例**（2026-03）：

用户发送：`用skills的方法，访问macaron.im 然后截图给我`

`chat.history` 返回的 content：

```json
[{"type":"text","text":"System: [2026-03-02 10:32:52 UTC] Exec completed (gentle-b, code 1) :: Gateway service check failed: Error: systemctl --user unavailable: Failed to connect to bus: No medium found\n\n[Mon 2026-03-02 10:33 UTC] 用skills的方法，访问macaron.im 然后截图给我"}]
```

结构拆解：

```
┌─ System 事件注入 ─────────────────────────────────────────────────┐
│ "System: [2026-03-02 10:32:52 UTC] Exec completed (gentle-b, code 1) :: ..." │
├─ 空行分隔 ────────────────────────────────────────────────────────┤
│ "\n\n"                                                           │
├─ 时间戳前缀 + 用户原文 ──────────────────────────────────────────┤
│ "[Mon 2026-03-02 10:33 UTC] 用skills的方法，访问macaron.im 然后截图给我"  │
└──────────────────────────────────────────────────────────────────┘
```

**观察到的时间戳格式**：

| 格式 | 示例 | 出现场景 |
|------|------|---------|
| Day + ISO + TZ | `[Mon 2026-03-02 10:33 UTC]` | 用户消息时间戳 |
| ISO + seconds + TZ | `[2026-03-02 10:32:52 UTC]` | 系统事件时间戳 |
| ISO 8601 compact | `[2026-03-02T10:32:52Z]` | 可能的变体 |

**Android App 处理**：`ChatApi.stripTranscriptPrefix()` 在解析 user 消息时剥离注入的前缀，通过正则匹配最后一个时间戳标记提取用户原文。兜底逻辑会丢弃以 `"System:"` 开头的行。

### 8.4 多媒体内容块

`toolResult` 消息的 `content` 数组中可能包含多媒体类型，实测确认的类型：

```json
// role: "toolResult" 的 content 数组
[
  {"type": "text", "text": "Read image file [image/png]"},
  {"type": "image", "mimeType": "image/png", "omitted": true, "bytes": 357080}
]
```

| type | 字段 | 说明 |
|------|------|------|
| `"image"` | `mimeType`, `omitted`, `bytes`, `data` | 图片。`omitted: true` 表示二进制数据被剥离，`bytes` 为原始大小，`data` 在未 omit 时为 base64 |
| `"file"` | `mimeType`, `fileName`, `path`, `bytes` | 通用文件引用 |
| `"audio"` | 同上 | 音频（预期格式，待实测确认） |
| `"video"` | 同上 | 视频（预期格式，待实测确认） |

**Android App 处理**：`parseContentBlocks()` 解析这些类型为 `ContentBlock.Image` / `ContentBlock.MediaRef`。对 `omitted` 图片，`inferProotPath()` 从关联工具调用的输入参数（`file_path`、`path` 等）推断 proot 文件路径，UI 层优先尝试本地加载，失败则显示占位卡片。

### 8.5 防御策略

由于消息体格式没有稳定契约，采用以下防御措施：

1. **多字段 fallback 解析**：`toolCallId || toolId || id`、`args || input`、`meta || content || output`
2. **Transcript 前缀剥离**：`stripTranscriptPrefix()` 覆盖多种时间戳格式，兜底剥离 `System:` 行
3. **Omitted 媒体本地推断**：`inferProotPath()` 从工具参数推断文件路径，降级为占位卡片
4. **本文档作为实测基准**：每次 OpenClaw 版本升级后对照验证
5. **集成测试**：直接连接本地 Gateway 断言字段结构，纳入 CI
