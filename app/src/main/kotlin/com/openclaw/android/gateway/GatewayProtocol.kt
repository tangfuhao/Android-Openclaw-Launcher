package com.openclaw.android.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire-format types for the OpenClaw Gateway WebSocket protocol v3.
 *
 * Three frame types:
 * - Request:  client -> gateway  (method calls)
 * - Response: gateway -> client  (method results)
 * - Event:    gateway -> client  (push notifications)
 *
 * All frames are JSON text, discriminated by the `type` field.
 */

// --- Outgoing (client -> gateway) ---

@Serializable
data class GatewayRequest(
    val type: String = "req",
    val id: String,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap()),
)

// --- Incoming (gateway -> client) ---

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

// --- Generic incoming frame (for initial parsing) ---

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
data class JsonError(val code: String? = null, val message: String? = null)

// --- Connect handshake types ---

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
    val scopes: List<String> = listOf("operator.read", "operator.write", "operator.approvals", "operator.admin"),
    val caps: List<String> = emptyList(),
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
    val mode: String = "cli",
)

@Serializable
data class AuthInfo(
    val token: String? = null,
)

@Serializable
data class DeviceInfo(
    val id: String,
    val publicKey: String = "",
    val signature: String = "",
    val signedAt: Long? = null,
    val nonce: String? = null,
)

@Serializable
data class HelloOk(
    val type: String = "hello-ok",
    val protocol: Int = 3,
    val server: JsonElement? = null,
    val features: JsonElement? = null,
    val snapshot: JsonElement? = null,
    val canvasHostUrl: String? = null,
    val policy: PolicyInfo? = null,
    val auth: HelloAuth? = null,
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
)

// --- Chat message types ---

@Serializable
data class ChatSendParams(
    val message: String,
    val sessionKey: String = "main",
    val idempotencyKey: String,
)

@Serializable
data class ChatHistoryParams(
    val sessionKey: String = "main",
    val limit: Int = 50,
)

/**
 * Chat event payload broadcast by the gateway.
 * States: "delta" (streaming), "final" (complete), "error"
 */
@Serializable
data class ChatEventPayload(
    val runId: String? = null,
    val sessionKey: String? = null,
    val seq: Int? = null,
    val state: String? = null,
    val message: ChatMessagePayload? = null,
    val errorMessage: String? = null,
)

@Serializable
data class ChatMessagePayload(
    val role: String? = null,
    val content: JsonElement? = null,
    val timestamp: Long? = null,
)

// --- Approval types ---

@Serializable
data class ApprovalRequestPayload(
    val requestId: String,
    val tool: String? = null,
    val description: String? = null,
    val params: JsonObject? = null,
)

@Serializable
data class ApprovalResolveParams(
    val requestId: String,
    val approved: Boolean,
    val reason: String? = null,
)
