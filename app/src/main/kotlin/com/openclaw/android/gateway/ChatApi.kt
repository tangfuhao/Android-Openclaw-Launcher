package com.openclaw.android.gateway

import com.openclaw.android.data.ChatMessage
import com.openclaw.android.data.ContentBlock
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

        return messages.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                val role = when (obj["role"]?.jsonPrimitive?.content) {
                    "user" -> ChatMessage.Role.USER
                    "assistant" -> ChatMessage.Role.ASSISTANT
                    else -> ChatMessage.Role.SYSTEM
                }
                val blocks = parseContentBlocks(obj["content"])
                val timestamp = obj["timestamp"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: System.currentTimeMillis()
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = role,
                    contentBlocks = blocks,
                    timestamp = timestamp,
                    sessionKey = sessionKey,
                )
            } catch (_: Exception) {
                null
            }
        }
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
                        val toolName = data?.get("toolName")?.jsonPrimitive?.content
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
                        "tool_result" -> ContentBlock.ToolResult(
                            toolUseId = obj["tool_use_id"]?.jsonPrimitive?.content ?: "",
                            content = extractTextFromElement(obj["content"]),
                            isError = obj["is_error"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                        )
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
