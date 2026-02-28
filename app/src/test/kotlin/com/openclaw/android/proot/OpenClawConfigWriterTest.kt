package com.openclaw.android.proot

import android.util.Log
import com.openclaw.android.core.OpenClawConstants
import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.data.PreferencesManager.ApiProvider
import com.openclaw.android.data.PreferencesManager.ApiType
import com.openclaw.android.data.PreferencesManager.ProviderConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OpenClawConfigWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var paths: OpenClawConstants.Paths
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var writer: OpenClawConfigWriter

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        paths = OpenClawConstants.Paths(tempFolder.root)
        paths.ensureDirectories()

        preferencesManager = mockk()
        writer = OpenClawConfigWriter(paths, preferencesManager)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun mockConfigs(configs: Map<ApiProvider, ProviderConfig>) {
        every { preferencesManager.getProviderConfigsSync() } returns configs
    }

    private fun standardConfig(apiKey: String) = ProviderConfig(
        apiKey = apiKey, baseUrl = "", apiType = "",
    )

    private fun customConfig(apiKey: String, baseUrl: String, apiType: String = "") = ProviderConfig(
        apiKey = apiKey, baseUrl = baseUrl, apiType = apiType,
    )

    // --- getApiKeyEnvVars ---

    @Test
    fun `getApiKeyEnvVars returns standard provider keys`() {
        mockConfigs(mapOf(
            ApiProvider.ANTHROPIC to standardConfig("sk-ant"),
            ApiProvider.OPENAI to standardConfig(""),
            ApiProvider.GOOGLE to standardConfig("gk-123"),
            ApiProvider.OPENROUTER to standardConfig(""),
        ))

        val vars = writer.getApiKeyEnvVars()
        assertEquals("sk-ant", vars["ANTHROPIC_API_KEY"])
        assertEquals("gk-123", vars["GOOGLE_API_KEY"])
        assertEquals(2, vars.size)
    }

    @Test
    fun `getApiKeyEnvVars excludes custom base URL providers`() {
        mockConfigs(mapOf(
            ApiProvider.ANTHROPIC to customConfig("sk-ant", "https://custom.api.com"),
            ApiProvider.OPENAI to standardConfig("sk-oai"),
            ApiProvider.GOOGLE to standardConfig(""),
            ApiProvider.OPENROUTER to standardConfig(""),
        ))

        val vars = writer.getApiKeyEnvVars()
        assertFalse(vars.containsKey("ANTHROPIC_API_KEY"))
        assertEquals("sk-oai", vars["OPENAI_API_KEY"])
    }

    @Test
    fun `getApiKeyEnvVars excludes providers without key`() {
        mockConfigs(mapOf(
            ApiProvider.ANTHROPIC to standardConfig(""),
            ApiProvider.OPENAI to standardConfig(""),
            ApiProvider.GOOGLE to standardConfig(""),
            ApiProvider.OPENROUTER to standardConfig(""),
        ))

        val vars = writer.getApiKeyEnvVars()
        assertTrue(vars.isEmpty())
    }

    @Test
    fun `getApiKeyEnvVars returns empty map when no providers configured`() {
        mockConfigs(emptyMap())
        val vars = writer.getApiKeyEnvVars()
        assertTrue(vars.isEmpty())
    }

    @Test
    fun `getApiKeyEnvVars maps correct env var names`() {
        mockConfigs(mapOf(
            ApiProvider.ANTHROPIC to standardConfig("a"),
            ApiProvider.OPENAI to standardConfig("b"),
            ApiProvider.GOOGLE to standardConfig("c"),
            ApiProvider.OPENROUTER to standardConfig("d"),
        ))

        val vars = writer.getApiKeyEnvVars()
        assertTrue(vars.containsKey("ANTHROPIC_API_KEY"))
        assertTrue(vars.containsKey("OPENAI_API_KEY"))
        assertTrue(vars.containsKey("GOOGLE_API_KEY"))
        assertTrue(vars.containsKey("OPENROUTER_API_KEY"))
    }

    // --- writeConfig ---

    @Test
    fun `writeConfig creates config file`() {
        mockConfigs(mapOf(
            ApiProvider.ANTHROPIC to standardConfig("sk-ant"),
            ApiProvider.OPENAI to standardConfig(""),
            ApiProvider.GOOGLE to standardConfig(""),
            ApiProvider.OPENROUTER to standardConfig(""),
        ))

        writer.writeConfig()

        val configFile = File(paths.hostOpenclawConfig, "openclaw.json")
        assertTrue(configFile.exists())
    }

    @Test
    fun `writeConfig writes valid JSON`() {
        mockConfigs(mapOf(
            ApiProvider.ANTHROPIC to standardConfig("sk-ant"),
            ApiProvider.OPENAI to standardConfig(""),
            ApiProvider.GOOGLE to standardConfig(""),
            ApiProvider.OPENROUTER to standardConfig(""),
        ))

        writer.writeConfig()

        val configFile = File(paths.hostOpenclawConfig, "openclaw.json")
        val json = JSONObject(configFile.readText())
        assertTrue(json.has("env"))
    }

    @Test
    fun `writeConfig includes env block for standard providers`() {
        mockConfigs(mapOf(
            ApiProvider.ANTHROPIC to standardConfig("sk-ant"),
            ApiProvider.OPENAI to standardConfig("sk-oai"),
            ApiProvider.GOOGLE to standardConfig(""),
            ApiProvider.OPENROUTER to standardConfig(""),
        ))

        writer.writeConfig()

        val json = JSONObject(File(paths.hostOpenclawConfig, "openclaw.json").readText())
        val env = json.getJSONObject("env")
        assertEquals("sk-ant", env.getString("ANTHROPIC_API_KEY"))
        assertEquals("sk-oai", env.getString("OPENAI_API_KEY"))
    }

    @Test
    fun `writeConfig includes models providers for custom URL`() {
        mockConfigs(mapOf(
            ApiProvider.ANTHROPIC to customConfig("sk-ant", "https://proxy.example.com", "anthropic-messages"),
            ApiProvider.OPENAI to standardConfig(""),
            ApiProvider.GOOGLE to standardConfig(""),
            ApiProvider.OPENROUTER to standardConfig(""),
        ))

        writer.writeConfig()

        val json = JSONObject(File(paths.hostOpenclawConfig, "openclaw.json").readText())
        assertTrue(json.has("models"))
        val providers = json.getJSONObject("models").getJSONObject("providers")
        assertTrue(providers.has("anthropic-custom"))
        assertEquals("https://proxy.example.com", providers.getJSONObject("anthropic-custom").getString("baseUrl"))
    }

    @Test
    fun `writeConfig handles mixed standard and custom providers`() {
        mockConfigs(mapOf(
            ApiProvider.ANTHROPIC to standardConfig("sk-ant"),
            ApiProvider.OPENAI to customConfig("sk-oai", "https://oai-proxy.com", "openai-completions"),
            ApiProvider.GOOGLE to standardConfig(""),
            ApiProvider.OPENROUTER to standardConfig(""),
        ))

        writer.writeConfig()

        val json = JSONObject(File(paths.hostOpenclawConfig, "openclaw.json").readText())
        assertTrue(json.has("env"))
        assertTrue(json.has("models"))
        assertEquals("sk-ant", json.getJSONObject("env").getString("ANTHROPIC_API_KEY"))
    }

    @Test
    fun `writeConfig skips providers without API key`() {
        mockConfigs(mapOf(
            ApiProvider.ANTHROPIC to standardConfig(""),
            ApiProvider.OPENAI to standardConfig(""),
            ApiProvider.GOOGLE to standardConfig(""),
            ApiProvider.OPENROUTER to standardConfig(""),
        ))

        writer.writeConfig()

        val json = JSONObject(File(paths.hostOpenclawConfig, "openclaw.json").readText())
        assertFalse(json.has("env"))
        assertFalse(json.has("models"))
    }

    @Test
    fun `writeConfig uses default apiType when blank`() {
        mockConfigs(mapOf(
            ApiProvider.ANTHROPIC to customConfig("sk-ant", "https://proxy.com", ""),
            ApiProvider.OPENAI to standardConfig(""),
            ApiProvider.GOOGLE to standardConfig(""),
            ApiProvider.OPENROUTER to standardConfig(""),
        ))

        writer.writeConfig()

        val json = JSONObject(File(paths.hostOpenclawConfig, "openclaw.json").readText())
        val provider = json.getJSONObject("models").getJSONObject("providers").getJSONObject("anthropic-custom")
        assertEquals(ApiType.ANTHROPIC_MESSAGES, provider.getString("api"))
    }

    @Test
    fun `writeConfig uses explicit apiType when set`() {
        mockConfigs(mapOf(
            ApiProvider.OPENAI to customConfig("sk-oai", "https://proxy.com", "openai-completions"),
            ApiProvider.ANTHROPIC to standardConfig(""),
            ApiProvider.GOOGLE to standardConfig(""),
            ApiProvider.OPENROUTER to standardConfig(""),
        ))

        writer.writeConfig()

        val json = JSONObject(File(paths.hostOpenclawConfig, "openclaw.json").readText())
        val provider = json.getJSONObject("models").getJSONObject("providers").getJSONObject("openai-custom")
        assertEquals("openai-completions", provider.getString("api"))
    }
}
