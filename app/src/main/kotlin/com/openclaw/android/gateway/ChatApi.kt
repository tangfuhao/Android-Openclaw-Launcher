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
                    result.add(ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.USER,
                        contentBlocks = msg.blocks,
                        timestamp = msg.timestamp,
                        sessionKey = sessionKey,
                    ))
                }
                "assistant" -> {
                    val textBlocks = msg.blocks.filterIsInstance<ContentBlock.Text>()
                    val toolCalls = msg.blocks.filterIsInstance<ContentBlock.ToolUse>()

                    if (toolCalls.isNotEmpty()) {
                        pendingToolUses.addAll(toolCalls)
                    }

                    val hasTextContent = textBlocks.any { it.text.isNotBlank() }
                    if (hasTextContent) {
                        val activities = buildToolActivities(pendingToolUses, pendingToolResults)
                        val runPhase = if (activities.isNotEmpty()) RunPhase.DONE else RunPhase.IDLE

                        result.add(ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = ChatMessage.Role.ASSISTANT,
                            contentBlocks = textBlocks,
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
            result.add(ChatMessage(
                id = UUID.randomUUID().toString(),
                role = ChatMessage.Role.ASSISTANT,
                contentBlocks = emptyList(),
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
            )
        }
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
