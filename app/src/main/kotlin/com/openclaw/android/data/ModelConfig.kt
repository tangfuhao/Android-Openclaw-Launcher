package com.openclaw.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ModelProviderEntry(
    val providerId: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val apiType: String = ModelApiType.OPENAI_COMPLETIONS,
    val modelId: String = "",
) {
    val hasApiKey: Boolean get() = apiKey.isNotBlank()
    val isCustom: Boolean get() = baseUrl.isNotBlank()
}

@Serializable
data class ModelConfig(
    val primaryModel: String = "",
    val providers: List<ModelProviderEntry> = emptyList(),
) {
    companion object {
        val Empty = ModelConfig()
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(raw: String): ModelConfig {
            if (raw.isBlank()) return Empty
            return runCatching { json.decodeFromString<ModelConfig>(raw) }.getOrDefault(Empty)
        }
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    fun validate(): String? {
        if (primaryModel.isBlank()) return "Primary model is required"
        if (!primaryModel.contains("/")) return "Primary model must be provider/model format"
        if (providers.isEmpty()) return "At least one provider entry is required"
        providers.forEachIndexed { index, entry ->
            if (entry.providerId.isBlank()) return "Provider #${index + 1}: ID is required"
            if (entry.apiKey.isBlank()) return "Provider #${index + 1}: API key is required"
            if (entry.modelId.isBlank()) return "Provider #${index + 1}: Model ID is required"
        }
        return null
    }

    /** Resolves the provider row used for connectivity probes. */
    fun probeEntry(): ModelProviderEntry? {
        if (providers.isEmpty()) return null
        val prefix = primaryModel.substringBefore("/").lowercase()
        return providers.firstOrNull { it.providerId.lowercase() == prefix } ?: providers.first()
    }
}

object ModelApiType {
    const val OPENAI_COMPLETIONS = "openai-completions"
    const val ANTHROPIC_MESSAGES = "anthropic-messages"
    const val GOOGLE_GENERATIVE_AI = "google-generative-ai"
    const val OPENAI_RESPONSES = "openai-responses"

    val ALL = listOf(
        OPENAI_COMPLETIONS,
        ANTHROPIC_MESSAGES,
        GOOGLE_GENERATIVE_AI,
        OPENAI_RESPONSES,
    )
}

object ProviderEnvLookup {
    private val envByProviderId = mapOf(
        "anthropic" to "ANTHROPIC_API_KEY",
        "openai" to "OPENAI_API_KEY",
        "google" to "GEMINI_API_KEY",
        "openrouter" to "OPENROUTER_API_KEY",
        "minimax-cn" to "MINIMAX_CN_API_KEY",
        "zai" to "ZAI_API_KEY",
        "kimi-coding" to "KIMI_API_KEY",
    )

    private val defaultBaseUrlByProviderId = mapOf(
        "anthropic" to "https://api.anthropic.com",
        "openai" to "https://api.openai.com/v1",
        "google" to "https://generativelanguage.googleapis.com/v1beta",
        "openrouter" to "https://openrouter.ai/api/v1",
    )

    fun envVarName(providerId: String): String? =
        envByProviderId[providerId.lowercase().trim()]

    fun defaultBaseUrl(providerId: String): String? =
        defaultBaseUrlByProviderId[providerId.lowercase().trim()]

    fun resolveBaseUrl(entry: ModelProviderEntry): String? {
        if (entry.baseUrl.isNotBlank()) return entry.baseUrl.trim().trimEnd('/')
        return defaultBaseUrl(entry.providerId)
    }
}
