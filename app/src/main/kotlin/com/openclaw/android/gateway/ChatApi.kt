package com.openclaw.android.gateway

import com.openclaw.android.data.ChatMessage
import com.openclaw.android.data.ContentBlock
import com.openclaw.android.data.RunPhase
import com.openclaw.android.data.ToolActivity
import com.openclaw.android.data.ToolPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Chat API built on top of [GatewayClient].
 * Handles message sending, history, abort, inject, and event observation.
 *
 * Session management methods have been moved to [SessionApi].
 */
class ChatApi(private val gateway: GatewayClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun sendMessage(
        text: String,
        sessionKey: String = "main",
        attachments: List<ChatAttachment>? = null,
        thinking: String? = null,
    ): String {
        val idempotencyKey = UUID.randomUUID().toString()
        val params = ChatSendParams(
            message = text,
            sessionKey = sessionKey,
            idempotencyKey = idempotencyKey,
            attachments = attachments?.takeIf { it.isNotEmpty() },
            thinking = thinking,
        )
        val paramsJson = json.encodeToJsonElement(ChatSendParams.serializer(), params).jsonObject
        val response = gateway.request("chat.send", paramsJson)

        if (!response.ok) {
            val errorMsg = response.error?.message ?: "Failed to send message"
            throw ChatApiException(errorMsg)
        }

        return response.payload
            ?.jsonObject
            ?.get("runId")
            ?.jsonPrimitive
            ?.content
            ?: idempotencyKey
    }

    suspend fun abortRun(sessionKey: String = "main", runId: String? = null) {
        val params = ChatAbortParams(sessionKey = sessionKey, runId = runId)
        val paramsJson = json.encodeToJsonElement(ChatAbortParams.serializer(), params).jsonObject
        gateway.request("chat.abort", paramsJson)
    }

    suspend fun getHistory(
        sessionKey: String = "main",
        limit: Int = 50,
    ): List<ChatMessage> {
        val params = ChatHistoryParams(sessionKey = sessionKey, limit = limit)
        val paramsJson = json.encodeToJsonElement(ChatHistoryParams.serializer(), params).jsonObject
        val response = gateway.request("chat.history", paramsJson)

        if (!response.ok) {
            throw ChatApiException(response.error?.message ?: "Failed to fetch history")
        }

        val messages = response.payload
            ?.jsonObject
            ?.get("messages")
            ?.jsonArray
            ?: return emptyList()

        val parsed = messages.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                val roleStr = obj["role"]?.jsonPrimitive?.content ?: "unknown"
                val blocks = parseContentBlocks(obj["content"])
                val timestamp = obj["timestamp"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: System.currentTimeMillis()
                val toolCallId = obj["toolCallId"]?.jsonPrimitive?.content
                val toolNameField = obj["toolName"]?.jsonPrimitive?.content
                val isError = obj["isError"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

                ParsedMsg(roleStr, blocks, timestamp, toolCallId, toolNameField, isError)
            } catch (_: Exception) {
                null
            }
        }

        val result = mutableListOf<ChatMessage>()
        var pendingToolUses = mutableListOf<ContentBlock.ToolUse>()
        var pendingToolResults = mutableMapOf<String, ParsedMsg>()

        for (msg in parsed) {
            when (msg.role) {
                "user" -> {
                    val cleanedBlocks = msg.blocks.map { block ->
                        if (block is ContentBlock.Text) {
                            ContentBlock.Text(stripTranscriptPrefix(block.text))
                        } else block
                    }
                    result.add(ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.USER,
                        contentBlocks = cleanedBlocks,
                        timestamp = msg.timestamp,
                        sessionKey = sessionKey,
                    ))
                }
                "assistant" -> {
                    val textAndMediaBlocks = msg.blocks.filter {
                        it is ContentBlock.Text || it is ContentBlock.Image || it is ContentBlock.MediaRef
                    }
                    val toolCalls = msg.blocks.filterIsInstance<ContentBlock.ToolUse>()

                    if (toolCalls.isNotEmpty()) {
                        pendingToolUses.addAll(toolCalls)
                    }

                    val hasTextContent = textAndMediaBlocks.any {
                        it is ContentBlock.Text && it.text.isNotBlank()
                    }
                    if (hasTextContent) {
                        val activities = buildToolActivities(pendingToolUses, pendingToolResults)
                        val mediaFromTools = activities.flatMap { it.mediaBlocks }
                        val runPhase = if (activities.isNotEmpty()) RunPhase.DONE else RunPhase.IDLE

                        result.add(ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = ChatMessage.Role.ASSISTANT,
                            contentBlocks = textAndMediaBlocks + mediaFromTools,
                            toolActivities = activities,
                            runPhase = runPhase,
                            timestamp = msg.timestamp,
                            sessionKey = sessionKey,
                        ))
                        pendingToolUses = mutableListOf()
                        pendingToolResults = mutableMapOf()
                    }
                }
                "toolResult" -> {
                    val toolCallId = msg.toolCallId ?: ""
                    pendingToolResults[toolCallId] = msg
                }
                else -> {
                    result.add(ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.SYSTEM,
                        contentBlocks = msg.blocks,
                        timestamp = msg.timestamp,
                        sessionKey = sessionKey,
                    ))
                }
            }
        }

        if (pendingToolUses.isNotEmpty()) {
            val activities = buildToolActivities(pendingToolUses, pendingToolResults)
            val mediaFromTools = activities.flatMap { it.mediaBlocks }
            result.add(ChatMessage(
                id = UUID.randomUUID().toString(),
                role = ChatMessage.Role.ASSISTANT,
                contentBlocks = mediaFromTools,
                toolActivities = activities,
                runPhase = if (activities.isNotEmpty()) RunPhase.DONE else RunPhase.IDLE,
                timestamp = System.currentTimeMillis(),
                sessionKey = sessionKey,
            ))
        }

        return result
    }

    suspend fun inject(sessionKey: String = "main", message: String, label: String? = null) {
        val params = ChatInjectParams(sessionKey = sessionKey, message = message, label = label)
        val paramsJson = json.encodeToJsonElement(ChatInjectParams.serializer(), params).jsonObject
        val response = gateway.request("chat.inject", paramsJson)
        if (!response.ok) {
            throw ChatApiException(response.error?.message ?: "Failed to inject message")
        }
    }

    fun observeChatEvents(): Flow<ChatEvent> {
        return gateway.chatEvents.map { payload ->
            val runId = payload.runId ?: ""
            val sk = payload.sessionKey ?: "main"
            when (payload.state) {
                "delta" -> {
                    val blocks = payload.message?.content?.let { parseContentBlocks(it) } ?: emptyList()
                    ChatEvent.Delta(runId = runId, sessionKey = sk, contentBlocks = blocks)
                }
                "final" -> {
                    val blocks = payload.message?.content?.let { parseContentBlocks(it) }
                    ChatEvent.Final(
                        runId = runId,
                        sessionKey = sk,
                        contentBlocks = blocks,
                        usage = payload.usage,
                        stopReason = payload.stopReason,
                    )
                }
                "aborted" -> {
                    ChatEvent.Aborted(runId = runId, sessionKey = sk)
                }
                "error" -> {
                    ChatEvent.Error(
                        runId = runId,
                        sessionKey = sk,
                        errorMessage = payload.errorMessage ?: "Unknown error",
                    )
                }
                else -> ChatEvent.Unknown
            }
        }
    }

    fun observeAgentToolEvents(): Flow<AgentToolEvent> {
        return gateway.agentEvents
            .map { payload ->
                val runId = payload.runId ?: ""
                val sk = payload.sessionKey ?: "main"
                val data = payload.data?.jsonObject

                when (payload.stream) {
                    "tool" -> {
                        val toolName = data?.get("name")?.jsonPrimitive?.content
                        val phase = data?.get("phase")?.jsonPrimitive?.content
                        AgentToolEvent.ToolStream(
                            runId = runId,
                            sessionKey = sk,
                            toolName = toolName,
                            phase = phase,
                            data = payload.data,
                        )
                    }
                    "lifecycle" -> {
                        val phase = data?.get("phase")?.jsonPrimitive?.content
                        AgentToolEvent.Lifecycle(runId = runId, sessionKey = sk, phase = phase)
                    }
                    else -> AgentToolEvent.Other(runId = runId, sessionKey = sk, stream = payload.stream)
                }
            }
    }

    /**
     * Parses a content [JsonElement] into structured [ContentBlock]s.
     * Handles plain strings and arrays of Claude API content blocks (text, tool_use, tool_result).
     */
    internal fun parseContentBlocks(element: JsonElement?): List<ContentBlock> {
        if (element == null) return emptyList()

        try {
            val text = element.jsonPrimitive.content
            return if (text.isNotEmpty()) listOf(ContentBlock.Text(text)) else emptyList()
        } catch (_: Exception) { /* not a primitive */ }

        try {
            return element.jsonArray.mapNotNull { block ->
                try {
                    val obj = block.jsonObject
                    when (obj["type"]?.jsonPrimitive?.content) {
                        "text" -> {
                            val text = obj["text"]?.jsonPrimitive?.content ?: ""
                            if (text.isNotEmpty()) ContentBlock.Text(text) else null
                        }
                        "tool_use" -> ContentBlock.ToolUse(
                            toolId = obj["id"]?.jsonPrimitive?.content ?: "",
                            name = obj["name"]?.jsonPrimitive?.content ?: "unknown",
                            input = obj["input"]?.jsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap()),
                        )
                        "toolCall" -> ContentBlock.ToolUse(
                            toolId = obj["id"]?.jsonPrimitive?.content ?: "",
                            name = obj["name"]?.jsonPrimitive?.content ?: "unknown",
                            input = obj["arguments"]?.jsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap()),
                        )
                        "tool_result" -> ContentBlock.ToolResult(
                            toolUseId = obj["tool_use_id"]?.jsonPrimitive?.content ?: "",
                            content = extractTextFromElement(obj["content"]),
                            isError = obj["is_error"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                        )
                        "image" -> {
                            val mimeType = obj["mimeType"]?.jsonPrimitive?.content ?: "image/png"
                            val omitted = obj["omitted"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                            val bytes = obj["bytes"]?.jsonPrimitive?.content?.toLongOrNull()
                            val data = obj["data"]?.jsonPrimitive?.content
                            ContentBlock.Image(
                                source = data,
                                mediaType = mimeType,
                                omitted = omitted,
                                bytes = bytes,
                            )
                        }
                        "file", "audio", "video" -> {
                            val mimeType = obj["mimeType"]?.jsonPrimitive?.content ?: "application/octet-stream"
                            val fileName = obj["fileName"]?.jsonPrimitive?.content
                                ?: obj["name"]?.jsonPrimitive?.content
                                ?: "file"
                            val path = obj["path"]?.jsonPrimitive?.content
                                ?: obj["filePath"]?.jsonPrimitive?.content
                                ?: ""
                            val size = obj["bytes"]?.jsonPrimitive?.content?.toLongOrNull()
                                ?: obj["size"]?.jsonPrimitive?.content?.toLongOrNull()
                            ContentBlock.MediaRef(
                                prootPath = path,
                                mimeType = mimeType,
                                fileName = fileName,
                                size = size,
                            )
                        }
                        "thinking" -> null
                        else -> null
                    }
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { /* not an array */ }

        return emptyList()
    }

    private fun extractTextFromElement(element: JsonElement?): String {
        if (element == null) return ""
        try { return element.jsonPrimitive.content } catch (_: Exception) {}
        try {
            return element.jsonArray
                .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                .joinToString("\n")
        } catch (_: Exception) {}
        return element.toString()
    }

    private data class ParsedMsg(
        val role: String,
        val blocks: List<ContentBlock>,
        val timestamp: Long,
        val toolCallId: String?,
        val toolName: String?,
        val isError: Boolean,
    )

    private fun buildToolActivities(
        toolUses: List<ContentBlock.ToolUse>,
        toolResults: Map<String, ParsedMsg>,
    ): List<ToolActivity> {
        if (toolUses.isEmpty()) return emptyList()
        return toolUses.map { use ->
            val result = toolResults[use.toolId]
            val resultText = result?.blocks
                ?.filterIsInstance<ContentBlock.Text>()
                ?.joinToString("\n") { it.text }
            val mediaBlocks = result?.blocks
                ?.filter { it is ContentBlock.Image || it is ContentBlock.MediaRef }
                ?.map { block -> inferProotPath(block, use.input) }
                ?: emptyList()
            ToolActivity(
                toolId = use.toolId,
                toolName = use.name,
                phase = when {
                    result?.isError == true -> ToolPhase.ERROR
                    result != null -> ToolPhase.COMPLETED
                    else -> ToolPhase.COMPLETED
                },
                input = use.input,
                output = resultText?.take(4000),
                isError = result?.isError ?: false,
                mediaBlocks = mediaBlocks,
            )
        }
    }

    /**
     * OpenClaw's transcript normalizes user messages by prepending system events
     * and timestamps. Observed formats from runtime logs:
     *
     *   "System: [2026-03-02 10:32:52 UTC] Exec completed ...\n\n[Mon 2026-03-02 10:33 UTC] actual user text"
     *
     * Timestamp patterns seen:
     *   [Mon 2026-03-02 10:33 UTC]         — day-of-week + ISO date + HH:MM + timezone
     *   [2026-03-02 10:32:52 UTC]          — ISO date + HH:MM:SS + timezone (system events)
     *   [2026-03-02T10:32:52Z]             — ISO 8601 compact
     *   [Mon Mar  2 10:33:00 UTC 2026]     — ctime-style (potential)
     *
     * Strategy: find the last bracket-enclosed timestamp and return everything after it.
     * Also strips leading "System: ..." lines if no timestamp match is found.
     */
    internal fun stripTranscriptPrefix(text: String): String {
        val timestampRegex = Regex(
            """\[""" +
            """(?:(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\s+)?""" +       // optional day-of-week
            """(?:""" +
            """\d{4}-\d{2}-\d{2}[\sT]\d{2}:\d{2}(?::\d{2})?""" + // ISO: 2026-03-02 10:33 or 2026-03-02T10:33:00
            """|""" +
            """(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+\d{1,2}\s+\d{2}:\d{2}(?::\d{2})?""" + // ctime: Mar  2 10:33:00
            """)""" +
            """(?:\s*[A-Z]{1,5})?""" +                            // optional timezone (UTC, CST, etc.)
            """(?:\s+\d{4})?""" +                                 // optional trailing year (ctime)
            """Z?""" +                                             // optional Z (ISO 8601)
            """\]\s*"""
        )

        val lastMatch = timestampRegex.findAll(text).lastOrNull()
        if (lastMatch != null) {
            val extracted = text.substring(lastMatch.range.last + 1).trim()
            if (extracted.isNotBlank()) return extracted
        }

        // Fallback: strip leading "System: ..." lines (e.g. exec notifications)
        val lines = text.lines()
        val userLines = lines.dropWhile { line ->
            line.startsWith("System:") || line.isBlank()
        }
        val fallback = userLines.joinToString("\n").trim()
        return fallback.ifBlank { text }
    }

    /**
     * For omitted images, try to infer the proot filesystem path from tool call arguments.
     * Scans common argument keys (file_path, path, filePath, filename) for absolute paths.
     */
    private fun inferProotPath(block: ContentBlock, toolInput: JsonObject): ContentBlock {
        if (block !is ContentBlock.Image) return block
        if (!block.omitted || block.prootPath != null) return block

        val candidateKeys = listOf("file_path", "path", "filePath", "filename", "file")
        val inferredPath = candidateKeys.firstNotNullOfOrNull { key ->
            toolInput[key]?.jsonPrimitive?.content?.takeIf { it.startsWith("/") }
        }
        return if (inferredPath != null) block.copy(prootPath = inferredPath) else block
    }

    sealed interface ChatEvent {
        data class Delta(val runId: String, val sessionKey: String, val contentBlocks: List<ContentBlock>) : ChatEvent
        data class Final(val runId: String, val sessionKey: String, val contentBlocks: List<ContentBlock>?, val usage: JsonElement? = null, val stopReason: String? = null) : ChatEvent
        data class Aborted(val runId: String, val sessionKey: String) : ChatEvent
        data class Error(val runId: String, val sessionKey: String, val errorMessage: String) : ChatEvent
        data object Unknown : ChatEvent
    }

    sealed interface AgentToolEvent {
        data class ToolStream(val runId: String, val sessionKey: String, val toolName: String?, val phase: String?, val data: JsonElement?) : AgentToolEvent
        data class Lifecycle(val runId: String, val sessionKey: String, val phase: String?) : AgentToolEvent
        data class Other(val runId: String, val sessionKey: String, val stream: String?) : AgentToolEvent
    }

    class ChatApiException(message: String) : RuntimeException(message)
}
