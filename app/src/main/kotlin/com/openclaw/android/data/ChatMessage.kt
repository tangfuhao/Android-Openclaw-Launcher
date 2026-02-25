package com.openclaw.android.data

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String,
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val status: Status = Status.SENT,
) {
    @Serializable
    enum class Role { USER, ASSISTANT, SYSTEM }

    @Serializable
    enum class Status { SENDING, SENT, ERROR }
}

/**
 * Approval request from the agent when it wants to execute a tool.
 */
@Serializable
data class ApprovalRequest(
    val id: String,
    val tool: String,
    val description: String,
    val params: Map<String, String> = emptyMap(),
)
