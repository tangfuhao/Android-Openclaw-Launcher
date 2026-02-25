package com.openclaw.android.gateway

import com.openclaw.android.data.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * High-level chat API built on top of [GatewayClient].
 * Translates between domain [ChatMessage] and gateway wire protocol.
 */
class ChatApi(private val gateway: GatewayClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Send a user message to the agent. Returns the message ID. */
    suspend fun sendMessage(text: String, sessionKey: String = "main"): String {
        val response = gateway.request("chat.send", buildJsonObject {
            put("text", text)
            put("sessionKey", sessionKey)
        })

        if (!response.ok) {
            val errorMsg = response.error?.message ?: "Failed to send message"
            throw ChatApiException(errorMsg)
        }

        return response.payload
            ?.jsonObject
            ?.get("messageId")
            ?.jsonPrimitive
            ?.content
            ?: UUID.randomUUID().toString()
    }

    /** Fetch chat history. Returns messages in chronological order. */
    suspend fun getHistory(
        sessionKey: String = "main",
        limit: Int = 50,
        before: String? = null,
    ): List<ChatMessage> {
        val response = gateway.request("chat.history", buildJsonObject {
            put("sessionKey", sessionKey)
            put("limit", limit)
            before?.let { put("before", it) }
        })

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
                ChatMessage(
                    id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(),
                    role = when (obj["role"]?.jsonPrimitive?.content) {
                        "user" -> ChatMessage.Role.USER
                        "assistant" -> ChatMessage.Role.ASSISTANT
                        else -> ChatMessage.Role.SYSTEM
                    },
                    content = obj["content"]?.jsonPrimitive?.content ?: "",
                    timestamp = obj["timestamp"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: System.currentTimeMillis(),
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Observable stream of chat events (new messages, streaming chunks, etc).
     * Combine with [gateway.chatEvents] for reactive UI updates.
     */
    fun observeChatEvents(): Flow<ChatEvent> {
        return gateway.chatEvents.map { payload ->
            when {
                payload.chunk != null -> ChatEvent.Chunk(
                    messageId = payload.chunk.messageId ?: "",
                    delta = payload.chunk.delta ?: "",
                    done = payload.chunk.done,
                )
                payload.message != null -> ChatEvent.Message(
                    ChatMessage(
                        id = payload.message.id ?: UUID.randomUUID().toString(),
                        role = when (payload.message.role) {
                            "user" -> ChatMessage.Role.USER
                            "assistant" -> ChatMessage.Role.ASSISTANT
                            else -> ChatMessage.Role.SYSTEM
                        },
                        content = payload.message.content ?: "",
                        timestamp = payload.message.timestamp ?: System.currentTimeMillis(),
                    )
                )
                else -> ChatEvent.Unknown
            }
        }
    }

    sealed interface ChatEvent {
        data class Message(val message: ChatMessage) : ChatEvent
        data class Chunk(val messageId: String, val delta: String, val done: Boolean) : ChatEvent
        data object Unknown : ChatEvent
    }

    class ChatApiException(message: String) : RuntimeException(message)
}
