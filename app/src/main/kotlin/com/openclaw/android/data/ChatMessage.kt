package com.openclaw.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatMessage(
    val id: String,
    val role: Role,
    val contentBlocks: List<ContentBlock> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val status: Status = Status.SENT,
    val sessionKey: String = "main",
    val runId: String? = null,
) {
    val textContent: String
        get() = contentBlocks.filterIsInstance<ContentBlock.Text>()
            .joinToString("\n") { it.text }

    @Serializable
    enum class Role { USER, ASSISTANT, SYSTEM }

    @Serializable
    enum class Status { SENDING, SENT, ERROR }

    companion object {
        fun ofText(
            id: String,
            role: Role,
            text: String,
            timestamp: Long = System.currentTimeMillis(),
            isStreaming: Boolean = false,
            status: Status = Status.SENT,
            sessionKey: String = "main",
            runId: String? = null,
        ) = ChatMessage(
            id = id,
            role = role,
            contentBlocks = listOf(ContentBlock.Text(text)),
            timestamp = timestamp,
            isStreaming = isStreaming,
            status = status,
            sessionKey = sessionKey,
            runId = runId,
        )
    }
}

@Serializable
sealed interface ContentBlock {
    @Serializable
    data class Text(val text: String) : ContentBlock

    @Serializable
    data class ToolUse(
        val toolId: String,
        val name: String,
        val input: JsonObject,
    ) : ContentBlock

    @Serializable
    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean = false,
    ) : ContentBlock

    @Serializable
    data class FileRef(
        val path: String,
        val mimeType: String? = null,
        val fileName: String,
        val size: Long? = null,
    ) : ContentBlock

    @Serializable
    data class Image(
        val source: String,
        val mediaType: String,
    ) : ContentBlock

    @Serializable
    data class UserAttachment(
        val localUri: String,
        val prootPath: String,
        val mimeType: String,
        val fileName: String,
        val size: Long,
    ) : ContentBlock
}

@Serializable
data class ApprovalRequest(
    val id: String,
    val tool: String,
    val description: String,
    val params: Map<String, String> = emptyMap(),
)
