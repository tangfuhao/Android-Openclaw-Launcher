package com.openclaw.android.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Handles tool execution approval requests from the OpenClaw agent.
 * When the agent wants to perform a sensitive operation (send email, run command, etc.),
 * it sends an approval request that must be resolved by the operator.
 */
class ApprovalApi(private val gateway: GatewayClient) {

    /** Observable stream of approval requests from the agent. */
    fun observeApprovalRequests(): Flow<ApprovalUiRequest> {
        return gateway.approvalRequests.map { payload ->
            ApprovalUiRequest(
                requestId = payload.requestId,
                tool = payload.tool ?: "unknown",
                description = payload.description ?: "The agent wants to execute a tool",
            )
        }
    }

    /** Approve or deny a pending tool execution request. */
    suspend fun resolve(requestId: String, approved: Boolean, reason: String? = null) {
        val response = gateway.request("exec.approval.resolve", buildJsonObject {
            put("requestId", requestId)
            put("approved", approved)
            reason?.let { put("reason", it) }
        })

        if (!response.ok) {
            throw ApprovalException(response.error?.message ?: "Failed to resolve approval")
        }
    }

    data class ApprovalUiRequest(
        val requestId: String,
        val tool: String,
        val description: String,
    )

    class ApprovalException(message: String) : RuntimeException(message)
}
