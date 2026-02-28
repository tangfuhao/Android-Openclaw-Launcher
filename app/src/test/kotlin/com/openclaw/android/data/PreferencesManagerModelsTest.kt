package com.openclaw.android.data

import com.openclaw.android.data.PreferencesManager.ApiProvider
import com.openclaw.android.data.PreferencesManager.ApiType
import com.openclaw.android.data.PreferencesManager.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesManagerModelsTest {

    @Test
    fun `ApiProvider entries has 4 values`() {
        assertEquals(4, ApiProvider.entries.size)
    }

    @Test
    fun `ApiProvider envVarName for each provider`() {
        assertEquals("ANTHROPIC_API_KEY", ApiProvider.ANTHROPIC.envVarName)
        assertEquals("OPENAI_API_KEY", ApiProvider.OPENAI.envVarName)
        assertEquals("GOOGLE_API_KEY", ApiProvider.GOOGLE.envVarName)
        assertEquals("OPENROUTER_API_KEY", ApiProvider.OPENROUTER.envVarName)
    }

    @Test
    fun `ApiProvider displayName for each provider`() {
        assertEquals("Anthropic", ApiProvider.ANTHROPIC.displayName)
        assertEquals("OpenAI", ApiProvider.OPENAI.displayName)
        assertEquals("Google", ApiProvider.GOOGLE.displayName)
        assertEquals("OpenRouter", ApiProvider.OPENROUTER.displayName)
    }

    @Test
    fun `ProviderConfig hasApiKey returns true for non-blank key`() {
        val config = ProviderConfig(apiKey = "sk-123", baseUrl = "", apiType = "")
        assertTrue(config.hasApiKey)
    }

    @Test
    fun `ProviderConfig hasApiKey returns false for blank key`() {
        val config = ProviderConfig(apiKey = "   ", baseUrl = "", apiType = "")
        assertFalse(config.hasApiKey)
    }

    @Test
    fun `ProviderConfig hasApiKey returns false for empty key`() {
        val config = ProviderConfig(apiKey = "", baseUrl = "", apiType = "")
        assertFalse(config.hasApiKey)
    }

    @Test
    fun `ProviderConfig hasCustomBaseUrl returns true for non-blank url`() {
        val config = ProviderConfig(apiKey = "k", baseUrl = "https://api.example.com", apiType = "")
        assertTrue(config.hasCustomBaseUrl)
    }

    @Test
    fun `ProviderConfig hasCustomBaseUrl returns false for blank url`() {
        val config = ProviderConfig(apiKey = "k", baseUrl = "  ", apiType = "")
        assertFalse(config.hasCustomBaseUrl)
    }

    @Test
    fun `ApiType constants have expected values`() {
        assertEquals("anthropic-messages", ApiType.ANTHROPIC_MESSAGES)
        assertEquals("openai-completions", ApiType.OPENAI_COMPLETIONS)
    }
}
