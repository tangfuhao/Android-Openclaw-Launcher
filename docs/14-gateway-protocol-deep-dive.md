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

当 Agent 被调用时（`agentRunStarted=true`），Pi Agent Runtime 直接通过 WebSocket 广播原始的 `chat` event。content 是完整的 Claude API content blocks 数组：

```json
{
  "state": "delta",
  "message": {
    "role": "assistant",
    "content": [
      {"type": "text", "text": "让我查看一下目录"},
      {"type": "tool_use", "id": "tu_1", "name": "bash", "input": {"command": "ls -la"}},
      {"type": "tool_result", "tool_use_id": "tu_1", "content": "total 48\ndrwxr-xr-x ..."}
    ]
  }
}
```

客户端收到 tool_use（Agent 调用了什么工具）、tool_result（工具返回了什么原始输出），需要自行决定如何渲染。

**Telegram 路径**：

Agent 的输出经过 ReplyDispatcher 的三层回调过滤：

```
onBlockReply(payload)   → 只包含 text，用于流式更新 Telegram 消息
onToolResult(payload)   → 工具结果摘要（群组中可被 suppress 掉）
sendFinalReply(payload) → 最终人类可读的回复文本
```

Telegram 用户看到的是 Agent "消化"后的输出——"我查看了目录，里面有以下文件：..."，而不是原始的 `ls -la` 输出。

**对 Android App 的含义**：

当前 `MessageBubble` 只渲染 `textContent`（拼接所有 `ContentBlock.Text`），`ToolUse` 和 `ToolResult` 块虽然收到了但没有良好的 UI 呈现。两个改进方向：

1. **Operator 视角**（推荐）：像 Claude Code CLI 那样，把 `tool_use` 渲染为可折叠的"正在执行: bash"卡片，`tool_result` 渲染为代码块。这利用了 WS 路径独有的工具可见性优势。
2. **IM 视角**：只渲染 `final` 事件中的文本，忽略中间过程。体验更接近 Telegram，但丢失了工具执行的实时反馈。

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
