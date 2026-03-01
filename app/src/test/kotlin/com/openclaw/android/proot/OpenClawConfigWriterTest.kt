package com.openclaw.android.proot

import android.util.Log
import com.openclaw.android.core.OpenClawConstants
import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.data.PreferencesManager.ApiProvider
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

    private fun allProvidersEmpty(): Map<ApiProvider, ProviderConfig> =
        ApiProvider.entries.associateWith { ProviderConfig(apiKey = "") }

    private fun withKeys(vararg pairs: Pair<ApiProvider, String>): Map<ApiProvider, ProviderConfig> {
        val base = allProvidersEmpty().toMutableMap()
        for ((provider, key) in pairs) {
            base[provider] = ProviderConfig(apiKey = key)
        }
        return base
    }

    // --- getApiKeyEnvVars ---

    @Test
    fun `getApiKeyEnvVars returns standard provider keys`() {
        mockConfigs(withKeys(
            ApiProvider.ANTHROPIC to "sk-ant",
            ApiProvider.GOOGLE to "gk-123",
        ))

        val vars = writer.getApiKeyEnvVars()
        assertEquals("sk-ant", vars["ANTHROPIC_API_KEY"])
        assertEquals("gk-123", vars["GEMINI_API_KEY"])
        assertEquals(2, vars.size)
    }

    @Test
    fun `getApiKeyEnvVars includes CN provider keys`() {
        mockConfigs(withKeys(
            ApiProvider.MINIMAX_CN to "cn-key",
            ApiProvider.ZAI to "zai-key",
            ApiProvider.KIMI_CODING to "kimi-key",
        ))

        val vars = writer.getApiKeyEnvVars()
        assertEquals("cn-key", vars["MINIMAX_CN_API_KEY"])
        assertEquals("zai-key", vars["ZAI_API_KEY"])
        assertEquals("kimi-key", vars["KIMI_API_KEY"])
        assertEquals(3, vars.size)
    }

    @Test
    fun `getApiKeyEnvVars excludes providers without key`() {
        mockConfigs(allProvidersEmpty())

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
        val configs = ApiProvider.entries.associateWith { ProviderConfig(apiKey = "key-${it.name}") }
        mockConfigs(configs)

        val vars = writer.getApiKeyEnvVars()
        assertTrue(vars.containsKey("ANTHROPIC_API_KEY"))
        assertTrue(vars.containsKey("OPENAI_API_KEY"))
        assertTrue(vars.containsKey("GEMINI_API_KEY"))
        assertTrue(vars.containsKey("OPENROUTER_API_KEY"))
        assertTrue(vars.containsKey("MINIMAX_CN_API_KEY"))
        assertTrue(vars.containsKey("ZAI_API_KEY"))
        assertTrue(vars.containsKey("KIMI_API_KEY"))
    }

    // --- writeConfig ---

    @Test
    fun `writeConfig creates config file`() {
        mockConfigs(withKeys(ApiProvider.ANTHROPIC to "sk-ant"))
        every { preferencesManager.getSelectedModelSync() } returns ""

        writer.writeConfig()

        val configFile = File(paths.hostOpenclawConfig, "openclaw.json")
        assertTrue(configFile.exists())
    }

    @Test
    fun `writeConfig writes valid JSON`() {
        mockConfigs(withKeys(ApiProvider.ANTHROPIC to "sk-ant"))
        every { preferencesManager.getSelectedModelSync() } returns ""

        writer.writeConfig()

        val configFile = File(paths.hostOpenclawConfig, "openclaw.json")
        val json = JSONObject(configFile.readText())
        assertTrue(json.has("env"))
    }

    @Test
    fun `writeConfig includes env block for all providers with keys`() {
        mockConfigs(withKeys(
            ApiProvider.ANTHROPIC to "sk-ant",
            ApiProvider.OPENAI to "sk-oai",
        ))
        every { preferencesManager.getSelectedModelSync() } returns ""

        writer.writeConfig()

        val json = JSONObject(File(paths.hostOpenclawConfig, "openclaw.json").readText())
        val env = json.getJSONObject("env")
        assertEquals("sk-ant", env.getString("ANTHROPIC_API_KEY"))
        assertEquals("sk-oai", env.getString("OPENAI_API_KEY"))
    }

    @Test
    fun `writeConfig skips providers without API key`() {
        mockConfigs(allProvidersEmpty())
        every { preferencesManager.getSelectedModelSync() } returns ""

        writer.writeConfig()

        val json = JSONObject(File(paths.hostOpenclawConfig, "openclaw.json").readText())
        assertFalse(json.has("env"))
    }

    @Test
    fun `writeConfig writes selected model`() {
        mockConfigs(withKeys(ApiProvider.ZAI to "zai-key"))
        every { preferencesManager.getSelectedModelSync() } returns "zai/glm-4.5"

        writer.writeConfig()

        val json = JSONObject(File(paths.hostOpenclawConfig, "openclaw.json").readText())
        val model = json.getJSONObject("agents").getJSONObject("defaults").getJSONObject("model")
        assertEquals("zai/glm-4.5", model.getString("primary"))
    }

    @Test
    fun `writeConfig sets gateway mode to local`() {
        mockConfigs(allProvidersEmpty())
        every { preferencesManager.getSelectedModelSync() } returns ""

        writer.writeConfig()

        val json = JSONObject(File(paths.hostOpenclawConfig, "openclaw.json").readText())
        assertEquals("local", json.getJSONObject("gateway").getString("mode"))
    }
}
