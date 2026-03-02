package com.openclaw.android.gateway

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Abstraction for gateway connection targets.
 * Currently only [Local] is used; [Remote] is prepared for future
 * remote gateway support (the key design decision: "WebSocket 复用
 * OpenClaw Gateway Protocol v3，未来可无缝切换远程网关").
 */
sealed interface GatewayConnectionConfig {
    val wsUrl: String
    fun resolveAuthInfo(): AuthInfo

    data class Local(
        val host: String = "127.0.0.1",
        val port: Int = 18789,
        val wsPath: String = "/",
        val configDir: File,
    ) : GatewayConnectionConfig {
        override val wsUrl: String get() = "ws://$host:$port$wsPath"

        override fun resolveAuthInfo(): AuthInfo {
            val token = readLocalAuthToken()
            return if (token != null) AuthInfo(token = token) else AuthInfo()
        }

        private fun readLocalAuthToken(): String? {
            val configFile = File(configDir, "openclaw.json")
            if (!configFile.exists()) return null
            return try {
                val json = Json { ignoreUnknownKeys = true }
                val parsed = json.parseToJsonElement(configFile.readText()).jsonObject
                parsed["gateway"]?.jsonObject
                    ?.get("auth")?.jsonObject
                    ?.get("token")?.jsonPrimitive?.content
            } catch (e: Exception) {
                Log.w("GatewayConnectionConfig", "Failed to read auth token", e)
                null
            }
        }
    }

    data class Remote(
        val url: String,
        val token: String,
    ) : GatewayConnectionConfig {
        override val wsUrl: String get() = url
        override fun resolveAuthInfo(): AuthInfo = AuthInfo(token = token)
    }
}
