package com.openclaw.android.data

import com.openclaw.android.data.PreferencesManager.ApiProvider
import com.openclaw.android.data.PreferencesManager.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesManagerModelsTest {

    @Test
    fun `ApiProvider entries has 7 values`() {
        assertEquals(7, ApiProvider.entries.size)
    }

    @Test
    fun `ApiProvider envVarName for each provider`() {
        assertEquals("ANTHROPIC_API_KEY", ApiProvider.ANTHROPIC.envVarName)
        assertEquals("OPENAI_API_KEY", ApiProvider.OPENAI.envVarName)
        assertEquals("GEMINI_API_KEY", ApiProvider.GOOGLE.envVarName)
        assertEquals("OPENROUTER_API_KEY", ApiProvider.OPENROUTER.envVarName)
        assertEquals("MINIMAX_CN_API_KEY", ApiProvider.MINIMAX_CN.envVarName)
        assertEquals("ZAI_API_KEY", ApiProvider.ZAI.envVarName)
        assertEquals("KIMI_API_KEY", ApiProvider.KIMI_CODING.envVarName)
    }

    @Test
    fun `ApiProvider displayName for each provider`() {
        assertEquals("Anthropic", ApiProvider.ANTHROPIC.displayName)
        assertEquals("OpenAI", ApiProvider.OPENAI.displayName)
        assertEquals("Google", ApiProvider.GOOGLE.displayName)
        assertEquals("OpenRouter", ApiProvider.OPENROUTER.displayName)
        assertEquals("MiniMax (中国)", ApiProvider.MINIMAX_CN.displayName)
        assertEquals("智谱 GLM", ApiProvider.ZAI.displayName)
        assertEquals("Kimi", ApiProvider.KIMI_CODING.displayName)
    }

    @Test
    fun `ApiProvider defaultModel contains provider prefix`() {
        ApiProvider.entries.forEach { provider ->
            assertTrue(
                "${provider.name}.defaultModel should contain '/'",
                provider.defaultModel.contains("/"),
            )
        }
    }

    @Test
    fun `ApiProvider availableModels is not empty`() {
        ApiProvider.entries.forEach { provider ->
            assertTrue(
                "${provider.name}.availableModels should not be empty",
                provider.availableModels.isNotEmpty(),
            )
        }
    }

    @Test
    fun `ProviderConfig hasApiKey returns true for non-blank key`() {
        val config = ProviderConfig(apiKey = "sk-123")
        assertTrue(config.hasApiKey)
    }

    @Test
    fun `ProviderConfig hasApiKey returns false for blank key`() {
        val config = ProviderConfig(apiKey = "   ")
        assertFalse(config.hasApiKey)
    }

    @Test
    fun `ProviderConfig hasApiKey returns false for empty key`() {
        val config = ProviderConfig(apiKey = "")
        assertFalse(config.hasApiKey)
    }
}
