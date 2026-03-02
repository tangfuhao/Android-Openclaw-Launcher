package com.openclaw.android.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Session management API built on top of [GatewayClient].
 *
 * Wire format aligned with sessions.ts — all methods use "key" (not "sessionKey")
 * as the wire field name via @SerialName on the param data classes.
 */
class SessionApi(private val gateway: GatewayClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun list(
        limit: Int? = null,
        activeMinutes: Int? = null,
        includeDerivedTitles: Boolean? = null,
        includeLastMessage: Boolean? = null,
        search: String? = null,
    ): List<SessionData> {
        val params = SessionsListParams(
            limit = limit,
            activeMinutes = activeMinutes,
            includeDerivedTitles = includeDerivedTitles,
            includeLastMessage = includeLastMessage,
            search = search,
        )
        val paramsJson = json.encodeToJsonElement(params).jsonObject
        val response = gateway.request("sessions.list", paramsJson)
        if (!response.ok) return emptyList()

        val sessions = response.payload
            ?.jsonObject?.get("sessions")?.jsonArray
            ?: return emptyList()

        return sessions.mapNotNull { el ->
            try {
                val obj = el.jsonObject
                SessionData(
                    key = obj["key"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    label = obj["label"]?.jsonPrimitive?.content,
                    model = obj["model"]?.jsonPrimitive?.content,
                    lastActivity = obj["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull(),
                )
            } catch (_: Exception) { null }
        }
    }

    suspend fun reset(sessionKey: String, reason: String? = null) {
        val params = SessionsResetParams(sessionKey = sessionKey, reason = reason)
        val paramsJson = json.encodeToJsonElement(params).jsonObject
        val response = gateway.request("sessions.reset", paramsJson)
        if (!response.ok) {
            throw SessionApiException(response.error?.message ?: "Failed to reset session")
        }
    }

    suspend fun delete(sessionKey: String, deleteTranscript: Boolean? = null) {
        val params = SessionsDeleteParams(sessionKey = sessionKey, deleteTranscript = deleteTranscript)
        val paramsJson = json.encodeToJsonElement(params).jsonObject
        val response = gateway.request("sessions.delete", paramsJson)
        if (!response.ok) {
            throw SessionApiException(response.error?.message ?: "Failed to delete session")
        }
    }

    suspend fun compact(sessionKey: String, maxLines: Int? = null) {
        val params = SessionsCompactParams(sessionKey = sessionKey, maxLines = maxLines)
        val paramsJson = json.encodeToJsonElement(params).jsonObject
        val response = gateway.request("sessions.compact", paramsJson)
        if (!response.ok) {
            throw SessionApiException(response.error?.message ?: "Failed to compact session")
        }
    }

    suspend fun patch(
        sessionKey: String,
        model: String? = null,
        thinkingLevel: String? = null,
        verboseLevel: String? = null,
        reasoningLevel: String? = null,
        responseUsage: String? = null,
        label: String? = null,
    ) {
        val params = SessionsPatchParams(
            sessionKey = sessionKey,
            model = model,
            thinkingLevel = thinkingLevel,
            verboseLevel = verboseLevel,
            reasoningLevel = reasoningLevel,
            responseUsage = responseUsage,
            label = label,
        )
        val paramsJson = json.encodeToJsonElement(params).jsonObject
        val response = gateway.request("sessions.patch", paramsJson)
        if (!response.ok) {
            throw SessionApiException(response.error?.message ?: "Failed to patch session")
        }
    }

    suspend fun usage(sessionKey: String? = null): JsonElement? {
        val params = SessionsUsageParams(sessionKey = sessionKey)
        val paramsJson = json.encodeToJsonElement(params).jsonObject
        val response = gateway.request("sessions.usage", paramsJson)
        if (!response.ok) return null
        return response.payload
    }

    data class SessionData(
        val key: String,
        val label: String?,
        val model: String?,
        val lastActivity: Long?,
    )

    class SessionApiException(message: String) : RuntimeException(message)
}
