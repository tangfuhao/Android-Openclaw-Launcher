package com.openclaw.android.gateway

import com.openclaw.android.data.ChatMessage
import com.openclaw.android.data.ContentBlock
import com.openclaw.android.data.RunPhase
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
 * Text-first chat API built on top of [GatewayClient].
 * The Android UI only sends and renders text, while non-text payloads are ignored.
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
    ): String {
        val idempotencyKey = UUID.randomUUID().toString()
        val params = ChatSendParams(
            message = text,
            sessionKey = sessionKey,
            idempotencyKey = idempotencyKey,
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
                val rawRole = obj["role"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val blocks = parseContentBlocks(obj["content"])
                if (blocks.isEmpty()) return@mapNotNull null

                val cleanedBlocks = if (rawRole == "user") {
                    blocks.map { ContentBlock.Text(stripTranscriptPrefix(it.text)) }
                        .filter { it.text.isNotBlank() }
                } else {
                    blocks
                }
                if (cleanedBlocks.isEmpty()) return@mapNotNull null

                val timestamp = obj["timestamp"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: System.currentTimeMillis()

                when (rawRole) {
                    "user" -> ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.USER,
                        contentBlocks = cleanedBlocks,
                        timestamp = timestamp,
                        sessionKey = sessionKey,
                    )
                    "assistant" -> ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.ASSISTANT,
                        contentBlocks = cleanedBlocks,
                        runPhase = RunPhase.DONE,
                        timestamp = timestamp,
                        sessionKey = sessionKey,
                    )
                    "toolResult" -> null
                    else -> ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.SYSTEM,
                        contentBlocks = cleanedBlocks,
                        timestamp = timestamp,
                        sessionKey = sessionKey,
                    )
                }
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
                "delta" -> ChatEvent.Delta(
                    runId = runId,
                    sessionKey = sk,
                    contentBlocks = parseContentBlocks(payload.message?.content),
                )
                "final" -> ChatEvent.Final(
                    runId = runId,
                    sessionKey = sk,
                    contentBlocks = parseContentBlocks(payload.message?.content),
                    usage = payload.usage,
                    stopReason = payload.stopReason,
                )
                "aborted" -> ChatEvent.Aborted(runId = runId, sessionKey = sk)
                "error" -> ChatEvent.Error(
                    runId = runId,
                    sessionKey = sk,
                    errorMessage = payload.errorMessage ?: "Unknown error",
                )
                else -> ChatEvent.Unknown
            }
        }
    }

    /**
     * Parses gateway message content into text-only content blocks.
     * Non-text payloads are ignored on purpose.
     */
    internal fun parseContentBlocks(element: JsonElement?): List<ContentBlock.Text> {
        if (element == null) return emptyList()

        try {
            val text = element.jsonPrimitive.content
            return if (text.isBlank()) emptyList() else listOf(ContentBlock.Text(text))
        } catch (_: Exception) {
            // Continue with structured content.
        }

        try {
            return element.jsonArray.mapNotNull { block ->
                try {
                    val obj = block.jsonObject
                    val type = obj["type"]?.jsonPrimitive?.content
                    if (type != "text") return@mapNotNull null

                    val text = obj["text"]?.jsonPrimitive?.content.orEmpty()
                    if (text.isBlank()) null else ContentBlock.Text(text)
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            // Fall through.
        }

        try {
            val obj = element.jsonObject
            val text = obj["text"]?.jsonPrimitive?.content.orEmpty()
            if (text.isNotBlank()) {
                return listOf(ContentBlock.Text(text))
            }
        } catch (_: Exception) {
            // Ignore unsupported content shapes.
        }

        return emptyList()
    }

    /**
     * OpenClaw's transcript may prepend system events and timestamps to user turns.
     * Keep only the last user-visible segment.
     */
    internal fun stripTranscriptPrefix(text: String): String {
        val timestampRegex = Regex(
            """\[""" +
                """(?:(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\s+)?""" +
                """(?:""" +
                """\d{4}-\d{2}-\d{2}[\sT]\d{2}:\d{2}(?::\d{2})?""" +
                """|""" +
                """(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+\d{1,2}\s+\d{2}:\d{2}(?::\d{2})?""" +
                """)""" +
                """(?:\s*[A-Z]{1,5})?""" +
                """(?:\s+\d{4})?""" +
                """Z?""" +
                """\]\s*"""
        )

        val lastMatch = timestampRegex.findAll(text).lastOrNull()
        if (lastMatch != null) {
            val extracted = text.substring(lastMatch.range.last + 1).trim()
            if (extracted.isNotBlank()) return extracted
        }

        val lines = text.lines()
        val userLines = lines.dropWhile { line ->
            line.startsWith("System:") || line.isBlank()
        }
        val fallback = userLines.joinToString("\n").trim()
        return fallback.ifBlank { text }
    }

    sealed interface ChatEvent {
        data class Delta(
            val runId: String,
            val sessionKey: String,
            val contentBlocks: List<ContentBlock>,
        ) : ChatEvent

        data class Final(
            val runId: String,
            val sessionKey: String,
            val contentBlocks: List<ContentBlock>,
            val usage: JsonElement? = null,
            val stopReason: String? = null,
        ) : ChatEvent

        data class Aborted(val runId: String, val sessionKey: String) : ChatEvent
        data class Error(val runId: String, val sessionKey: String, val errorMessage: String) : ChatEvent
        data object Unknown : ChatEvent
    }

    class ChatApiException(message: String) : RuntimeException(message)
}
