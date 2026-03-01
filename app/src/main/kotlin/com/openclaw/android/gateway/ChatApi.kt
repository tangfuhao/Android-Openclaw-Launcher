package com.openclaw.android.gateway

import com.openclaw.android.data.ChatMessage
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
 * High-level chat API built on top of [GatewayClient].
 * Translates between domain [ChatMessage] and gateway wire protocol.
 */
class ChatApi(private val gateway: GatewayClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Send a user message to the agent. Returns the runId for tracking. */
    suspend fun sendMessage(text: String, sessionKey: String = "main"): String {
        val idempotencyKey = UUID.randomUUID().toString()
        val params = ChatSendParams(message = text, sessionKey = sessionKey, idempotencyKey = idempotencyKey)
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

    /** Fetch chat history. Returns messages in chronological order. */
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
                val content = extractText(obj["content"])
                val timestamp = obj["timestamp"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: System.currentTimeMillis()
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = role,
                    content = content,
                    timestamp = timestamp,
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Observable stream of chat events (streaming deltas, finals, errors).
     */
    fun observeChatEvents(): Flow<ChatEvent> {
        return gateway.chatEvents.map { payload ->
            when (payload.state) {
                "delta" -> {
                    val text = payload.message?.content?.let { extractText(it) } ?: ""
                    ChatEvent.Delta(
                        runId = payload.runId ?: "",
                        sessionKey = payload.sessionKey ?: "main",
                        text = text,
                    )
                }
                "final" -> {
                    val text = payload.message?.content?.let { extractText(it) }
                    ChatEvent.Final(
                        runId = payload.runId ?: "",
                        sessionKey = payload.sessionKey ?: "main",
                        text = text,
                    )
                }
                "error" -> {
                    ChatEvent.Error(
                        runId = payload.runId ?: "",
                        sessionKey = payload.sessionKey ?: "main",
                        errorMessage = payload.errorMessage ?: "Unknown error",
                    )
                }
                else -> ChatEvent.Unknown
            }
        }
    }

    /**
     * Extracts text from a content [JsonElement] that may be either a plain string
     * or an array of content blocks like `[{"type":"text","text":"..."}]`.
     */
    private fun extractText(element: JsonElement?): String {
        if (element == null) return ""

        try {
            return element.jsonPrimitive.content
        } catch (_: Exception) { /* not a primitive */ }

        try {
            return element.jsonArray
                .filter { block ->
                    block.jsonObject["type"]?.jsonPrimitive?.content == "text"
                }
                .mapNotNull { block ->
                    block.jsonObject["text"]?.jsonPrimitive?.content
                }
                .joinToString("\n")
        } catch (_: Exception) { /* not an array */ }

        return ""
    }

    sealed interface ChatEvent {
        /** Streaming delta: accumulated text so far for this run */
        data class Delta(val runId: String, val sessionKey: String, val text: String) : ChatEvent
        /** Final response for a run (text may be null if suppressed) */
        data class Final(val runId: String, val sessionKey: String, val text: String?) : ChatEvent
        /** Error during a run */
        data class Error(val runId: String, val sessionKey: String, val errorMessage: String) : ChatEvent
        data object Unknown : ChatEvent
    }

    class ChatApiException(message: String) : RuntimeException(message)
}
