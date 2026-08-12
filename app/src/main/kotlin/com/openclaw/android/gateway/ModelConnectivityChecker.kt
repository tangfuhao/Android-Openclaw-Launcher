package com.openclaw.android.gateway

import com.openclaw.android.data.ModelApiType
import com.openclaw.android.data.ModelConfig
import com.openclaw.android.data.ModelProviderEntry
import com.openclaw.android.data.ProviderEnvLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed class ConnectivityResult {
    data class Success(val latencyMs: Long) : ConnectivityResult()
    data class Failure(val message: String) : ConnectivityResult()
}

@Singleton
class ModelConnectivityChecker @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun test(config: ModelConfig): ConnectivityResult = withContext(Dispatchers.IO) {
        config.validate()?.let { return@withContext ConnectivityResult.Failure(it) }

        val entry = config.probeEntry()
            ?: return@withContext ConnectivityResult.Failure("No provider entry found")

        val baseUrl = ProviderEnvLookup.resolveBaseUrl(entry)
            ?: return@withContext ConnectivityResult.Failure(
                "Base URL is required for connectivity test (unknown provider: ${entry.providerId})",
            )

        when (entry.apiType) {
            ModelApiType.OPENAI_COMPLETIONS -> probeOpenAiCompletions(baseUrl, entry)
            ModelApiType.ANTHROPIC_MESSAGES -> probeAnthropicMessages(baseUrl, entry)
            ModelApiType.GOOGLE_GENERATIVE_AI -> probeGoogleGenerativeAi(baseUrl, entry)
            ModelApiType.OPENAI_RESPONSES ->
                ConnectivityResult.Failure("openai-responses probe is not supported yet")
            else -> ConnectivityResult.Failure("Unsupported API type: ${entry.apiType}")
        }
    }

    private fun probeOpenAiCompletions(
        baseUrl: String,
        entry: ModelProviderEntry,
    ): ConnectivityResult {
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val body = JSONObject().apply {
            put("model", entry.modelId)
            put("max_tokens", 5)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Reply with OK")
                })
            })
        }
        return executeProbe(url, entry.apiKey, body, authHeader = "Bearer")
    }

    private fun probeAnthropicMessages(
        baseUrl: String,
        entry: ModelProviderEntry,
    ): ConnectivityResult {
        val url = "${baseUrl.trimEnd('/')}/v1/messages"
        val body = JSONObject().apply {
            put("model", entry.modelId)
            put("max_tokens", 5)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Reply with OK")
                })
            })
        }
        return executeProbe(
            url = url,
            apiKey = entry.apiKey,
            body = body,
            authHeader = "x-api-key",
            extraHeaders = mapOf("anthropic-version" to "2023-06-01"),
        )
    }

    private fun probeGoogleGenerativeAi(
        baseUrl: String,
        entry: ModelProviderEntry,
    ): ConnectivityResult {
        val url = "${baseUrl.trimEnd('/')}/models/${entry.modelId}:generateContent?key=${entry.apiKey}"
        val body = JSONObject().apply {
            put("contents", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", org.json.JSONArray().apply {
                        put(JSONObject().apply { put("text", "Reply with OK") })
                    })
                })
            })
        }
        return executeProbe(url, apiKey = null, body = body, authHeader = null)
    }

    private fun executeProbe(
        url: String,
        apiKey: String?,
        body: JSONObject,
        authHeader: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ConnectivityResult {
        val start = System.currentTimeMillis()
        return try {
            val requestBuilder = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))

            if (authHeader != null && apiKey != null) {
                if (authHeader == "Bearer") {
                    requestBuilder.header("Authorization", "Bearer $apiKey")
                } else {
                    requestBuilder.header(authHeader, apiKey)
                }
            }
            extraHeaders.forEach { (key, value) -> requestBuilder.header(key, value) }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    if (responseBody.contains("content") || responseBody.contains("text")) {
                        ConnectivityResult.Success(latency)
                    } else {
                        ConnectivityResult.Failure("Unexpected response: ${responseBody.take(200)}")
                    }
                } else {
                    ConnectivityResult.Failure(formatHttpError(response.code, responseBody))
                }
            }
        } catch (e: Exception) {
            ConnectivityResult.Failure(e.message ?: "Connection failed")
        }
    }

    private fun formatHttpError(code: Int, body: String): String {
        val snippet = body.take(300).ifBlank { "no body" }
        return when (code) {
            401, 403 -> "Authentication failed (HTTP $code): $snippet"
            404 -> "Model or endpoint not found (HTTP 404): $snippet"
            422 -> "Invalid request (HTTP 422): $snippet"
            else -> "HTTP $code: $snippet"
        }
    }
}
