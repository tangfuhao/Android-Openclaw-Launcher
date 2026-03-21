package com.openclaw.android.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire-format types for the OpenClaw Gateway WebSocket protocol v3.
 *
 * All field names are aligned with the official TypeBox schemas at:
 *   reference/openclaw-protocol/schema/
 *
 * Where Kotlin property names differ from JSON wire names,
 * @SerialName is used to ensure correct serialization.
 */

// ── Frame types ─────────────────────────────────────────────────────

@Serializable
data class GatewayRequest(
    val type: String = "req",
    val id: String,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class GatewayResponse(
    val type: String = "res",
    val id: String,
    val ok: Boolean,
    val payload: JsonElement? = null,
    val error: JsonError? = null,
)

@Serializable
data class GatewayEvent(
    val type: String = "event",
    val event: String,
    val payload: JsonElement = JsonObject(emptyMap()),
    val seq: Long? = null,
    val stateVersion: JsonElement? = null,
)

@Serializable
data class GatewayFrame(
    val type: String,
    val id: String? = null,
    val ok: Boolean? = null,
    val method: String? = null,
    val event: String? = null,
    val payload: JsonElement? = null,
    val error: JsonElement? = null,
    val params: JsonObject? = null,
    val seq: Long? = null,
    val stateVersion: JsonElement? = null,
)

@Serializable
data class JsonError(
    val code: String? = null,
    val message: String? = null,
    val details: JsonElement? = null,
    val retryable: Boolean? = null,
    val retryAfterMs: Int? = null,
)

// ── Connect handshake (frames.ts) ───────────────────────────────────

@Serializable
data class ConnectChallenge(
    val nonce: String,
    val ts: Long,
)

@Serializable
data class ConnectParams(
    val minProtocol: Int = 3,
    val maxProtocol: Int = 3,
    val client: ClientInfo,
    val role: String = "operator",
    val scopes: List<String> = listOf(
        "operator.read", "operator.write",
        "operator.approvals", "operator.admin",
    ),
    val caps: List<String> = listOf("tool-events"),
    val commands: List<String> = emptyList(),
    val permissions: JsonObject = JsonObject(emptyMap()),
    val auth: AuthInfo = AuthInfo(),
    val locale: String = "en-US",
    val userAgent: String = "android-openclaw/0.1.0",
    val device: DeviceInfo? = null,
)

@Serializable
data class ClientInfo(
    val id: String = "openclaw-android",
    val version: String = "0.1.0",
    val platform: String = "android",
    val mode: String = "ui",
    val displayName: String? = null,
    val deviceFamily: String? = null,
    val modelIdentifier: String? = null,
    val instanceId: String? = null,
)

@Serializable
data class AuthInfo(
    val token: String? = null,
    val deviceToken: String? = null,
    val password: String? = null,
)

@Serializable
data class DeviceInfo(
    val id: String,
    val publicKey: String,
    val signature: String,
    val signedAt: Long,
    val nonce: String,
)

@Serializable
data class HelloOk(
    val type: String = "hello-ok",
    val protocol: Int = 3,
    val server: ServerInfo? = null,
    val features: HelloFeatures? = null,
    val snapshot: JsonElement? = null,
    val canvasHostUrl: String? = null,
    val policy: PolicyInfo? = null,
    val auth: HelloAuth? = null,
)

@Serializable
data class ServerInfo(
    val version: String,
    val connId: String,
)

@Serializable
data class HelloFeatures(
    val methods: List<String> = emptyList(),
    val events: List<String> = emptyList(),
)

@Serializable
data class PolicyInfo(
    val maxPayload: Long? = null,
    val maxBufferedBytes: Long? = null,
    val tickIntervalMs: Long = 15000,
)

@Serializable
data class HelloAuth(
    val deviceToken: String? = null,
    val role: String? = null,
    val scopes: List<String>? = null,
    val issuedAtMs: Long? = null,
)

// ── Server push events (frames.ts) ─────────────────────────────────

@Serializable
data class TickEventPayload(
    val ts: Long,
)

@Serializable
data class ShutdownEventPayload(
    val reason: String,
    val restartExpectedMs: Long? = null,
)

// ── Chat methods (logs-chat.ts) ─────────────────────────────────────

@Serializable
data class ChatSendParams(
    val message: String,
    val sessionKey: String = "main",
    val idempotencyKey: String,
    val thinking: String? = null,
    val deliver: Boolean? = null,
    val timeoutMs: Int? = null,
)

@Serializable
data class ChatHistoryParams(
    val sessionKey: String = "main",
    val limit: Int = 50,
)

@Serializable
data class ChatAbortParams(
    val sessionKey: String = "main",
    val runId: String? = null,
)

@Serializable
data class ChatInjectParams(
    val sessionKey: String,
    val message: String,
    val label: String? = null,
)

/**
 * Chat event payload broadcast by the gateway.
 * States: "delta" (streaming), "final" (complete), "aborted", "error"
 */
@Serializable
data class ChatEventPayload(
    val runId: String? = null,
    val sessionKey: String? = null,
    val seq: Int? = null,
    val state: String? = null,
    val message: ChatMessagePayload? = null,
    val errorMessage: String? = null,
    val usage: JsonElement? = null,
    val stopReason: String? = null,
)

@Serializable
data class ChatMessagePayload(
    val role: String? = null,
    val content: JsonElement? = null,
    val timestamp: Long? = null,
)

// ── Agent events (agent.ts, requires caps: ["tool-events"]) ─────────

@Serializable
data class AgentEventPayload(
    val runId: String? = null,
    val sessionKey: String? = null,
    val stream: String? = null,
    val seq: Int = 0,
    val ts: Long? = null,
    val data: JsonElement? = null,
)

// ── Exec approvals (exec-approvals.ts) ──────────────────────────────

@Serializable
data class ApprovalRequestPayload(
    val id: String,
    val command: String,
    val commandArgv: List<String>? = null,
    val cwd: String? = null,
    val nodeId: String? = null,
    val sessionKey: String? = null,
    val timeoutMs: Int? = null,
)

@Serializable
data class ApprovalResolveParams(
    val id: String,
    val decision: String,
)

// ── Session management (sessions.ts) ────────────────────────────────
// Wire field is "key"; @SerialName keeps Kotlin property readable.

@Serializable
data class SessionsListParams(
    val limit: Int? = null,
    val activeMinutes: Int? = null,
    val includeDerivedTitles: Boolean? = null,
    val includeLastMessage: Boolean? = null,
    val label: String? = null,
    val search: String? = null,
)

@Serializable
data class SessionsResetParams(
    @SerialName("key") val sessionKey: String,
    val reason: String? = null,
)

@Serializable
data class SessionsDeleteParams(
    @SerialName("key") val sessionKey: String,
    val deleteTranscript: Boolean? = null,
)

@Serializable
data class SessionsCompactParams(
    @SerialName("key") val sessionKey: String,
    val maxLines: Int? = null,
)

@Serializable
data class SessionsPatchParams(
    @SerialName("key") val sessionKey: String,
    val model: String? = null,
    val thinkingLevel: String? = null,
    val verboseLevel: String? = null,
    val reasoningLevel: String? = null,
    val responseUsage: String? = null,
    val elevatedLevel: String? = null,
    val execHost: String? = null,
    val execSecurity: String? = null,
    val execAsk: String? = null,
    val execNode: String? = null,
    val sendPolicy: String? = null,
    val groupActivation: String? = null,
    val label: String? = null,
)

@Serializable
data class SessionsUsageParams(
    @SerialName("key") val sessionKey: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val limit: Int? = null,
    val includeContextWeight: Boolean? = null,
)
