package com.openclaw.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigTest {

    @Test
    fun `validate requires primary model with slash`() {
        val config = ModelConfig(
            primaryModel = "invalid",
            providers = listOf(
                ModelProviderEntry(
                    providerId = "anthropic",
                    apiKey = "sk",
                    modelId = "claude",
                ),
            ),
        )
        assertEquals("Primary model must be provider/model format", config.validate())
    }

    @Test
    fun `validate passes for complete config`() {
        val config = ModelConfig(
            primaryModel = "anthropic/claude-sonnet",
            providers = listOf(
                ModelProviderEntry(
                    providerId = "anthropic",
                    apiKey = "sk-ant",
                    modelId = "claude-sonnet",
                    apiType = ModelApiType.ANTHROPIC_MESSAGES,
                ),
            ),
        )
        assertNull(config.validate())
    }

    @Test
    fun `probeEntry matches primary model provider prefix`() {
        val config = ModelConfig(
            primaryModel = "openai/gpt-4",
            providers = listOf(
                ModelProviderEntry(providerId = "anthropic", apiKey = "a", modelId = "c"),
                ModelProviderEntry(providerId = "openai", apiKey = "o", modelId = "gpt-4"),
            ),
        )
        assertEquals("openai", config.probeEntry()?.providerId)
    }

    @Test
    fun `json round trip preserves config`() {
        val original = ModelConfig(
            primaryModel = "my-vendor/model-a",
            providers = listOf(
                ModelProviderEntry(
                    providerId = "my-vendor",
                    baseUrl = "https://api.example.com/v1",
                    apiKey = "key",
                    apiType = ModelApiType.OPENAI_COMPLETIONS,
                    modelId = "model-a",
                ),
            ),
        )
        val restored = ModelConfig.fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun `ProviderEnvLookup resolves known env and base URLs`() {
        assertEquals("ANTHROPIC_API_KEY", ProviderEnvLookup.envVarName("anthropic"))
        assertEquals("https://api.openai.com/v1", ProviderEnvLookup.defaultBaseUrl("openai"))
        assertNull(ProviderEnvLookup.defaultBaseUrl("unknown-vendor"))
    }

    @Test
    fun `ModelProviderEntry isCustom when baseUrl set`() {
        assertTrue(
            ModelProviderEntry(providerId = "x", baseUrl = "https://a.com", apiKey = "k", modelId = "m").isCustom,
        )
        assertFalse(
            ModelProviderEntry(providerId = "anthropic", apiKey = "k", modelId = "m").isCustom,
        )
    }
}
