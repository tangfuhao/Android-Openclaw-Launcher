package com.openclaw.android.data

import kotlinx.serialization.Serializable

@Serializable
enum class RunPhase { IDLE, THINKING, RESPONDING, DONE }

@Serializable
data class ChatMessage(
    val id: String,
    val role: Role,
    val contentBlocks: List<ContentBlock> = emptyList(),
    val runPhase: RunPhase = RunPhase.IDLE,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val status: Status = Status.SENT,
    val sessionKey: String = "main",
    val runId: String? = null,
) {
    val textContent: String
        get() = contentBlocks
            .filterIsInstance<ContentBlock.Text>()
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
            runPhase: RunPhase = RunPhase.IDLE,
        ) = ChatMessage(
            id = id,
            role = role,
            contentBlocks = listOf(ContentBlock.Text(text)),
            timestamp = timestamp,
            isStreaming = isStreaming,
            status = status,
            sessionKey = sessionKey,
            runId = runId,
            runPhase = runPhase,
        )
    }
}

@Serializable
sealed interface ContentBlock {
    @Serializable
    data class Text(val text: String) : ContentBlock
}
