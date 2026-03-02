package com.openclaw.android.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Handles tool execution approval requests from the OpenClaw agent.
 *
 * Wire format aligned with exec-approvals.ts:
 * - Request event payload: {id, command, commandArgv?, cwd?, ...}
 * - Resolve params: {id, decision}  (decision: "allow" | "deny")
 */
class ApprovalApi(private val gateway: GatewayClient) {

    fun observeApprovalRequests(): Flow<ApprovalUiRequest> {
        return gateway.approvalRequests.map { payload ->
            ApprovalUiRequest(
                requestId = payload.id,
                command = payload.command,
                commandArgv = payload.commandArgv,
                cwd = payload.cwd,
            )
        }
    }

    suspend fun resolve(requestId: String, approved: Boolean, reason: String? = null) {
        val response = gateway.request("exec.approval.resolve", buildJsonObject {
            put("id", requestId)
            put("decision", if (approved) "allow" else "deny")
        })

        if (!response.ok) {
            throw ApprovalException(response.error?.message ?: "Failed to resolve approval")
        }
    }

    data class ApprovalUiRequest(
        val requestId: String,
        val command: String,
        val commandArgv: List<String>? = null,
        val cwd: String? = null,
    ) {
        val displayDescription: String
            get() = buildString {
                append(command)
                if (!commandArgv.isNullOrEmpty()) {
                    append(" ")
                    append(commandArgv.joinToString(" "))
                }
                if (cwd != null) append(" (in $cwd)")
            }
    }

    class ApprovalException(message: String) : RuntimeException(message)
}
