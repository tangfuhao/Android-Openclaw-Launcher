package com.openclaw.android.proot

import android.util.Log
import com.openclaw.android.core.OpenClawConstants
import com.openclaw.android.data.ModelApiType
import com.openclaw.android.data.ModelConfig
import com.openclaw.android.data.ModelProviderEntry
import com.openclaw.android.data.PreferencesManager
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

    private fun mockConfig(config: ModelConfig) {
        every { preferencesManager.getModelConfigSync() } returns config
    }

    private fun builtinEntry(
        providerId: String,
        apiKey: String,
        modelId: String = "test-model",
        apiType: String = ModelApiType.OPENAI_COMPLETIONS,
    ) = ModelProviderEntry(
        providerId = providerId,
        baseUrl = "",
        apiKey = apiKey,
        apiType = apiType,
        modelId = modelId,
    )

    @Test
    fun `getApiKeyEnvVars returns env for builtin providers`() {
        mockConfig(
            ModelConfig(
                providers = listOf(
                    builtinEntry("anthropic", "sk-ant", apiType = ModelApiType.ANTHROPIC_MESSAGES),
                    builtinEntry("google", "gk-123", apiType = ModelApiType.GOOGLE_GENERATIVE_AI),
                ),
            ),
        )

        val vars = writer.getApiKeyEnvVars()
        assertEquals("sk-ant", vars["ANTHROPIC_API_KEY"])
        assertEquals("gk-123", vars["GEMINI_API_KEY"])
        assertEquals(2, vars.size)
    }

    @Test
    fun `getApiKeyEnvVars excludes custom providers`() {
        mockConfig(
            ModelConfig(
                providers = listOf(
                    ModelProviderEntry(
                        providerId = "my-vendor",
                        baseUrl = "https://api.example.com/v1",
                        apiKey = "custom-key",
                        modelId = "m1",
                    ),
                ),
            ),
        )

        assertTrue(writer.getApiKeyEnvVars().isEmpty())
    }

    @Test
    fun `writeConfig creates config file`() {
        mockConfig(ModelConfig(providers = listOf(builtinEntry("anthropic", "sk-ant"))))
        writer.writeConfig()
        assertTrue(File(paths.hostOpenclawConfig, "openclaw.json").exists())
    }

    @Test
    fun `writeConfig includes env block for builtin providers`() {
        mockConfig(
            ModelConfig(
                providers = listOf(
                    builtinEntry("anthropic", "sk-ant"),
                    builtinEntry("openai", "sk-oai"),
                ),
            ),
        )
        writer.writeConfig()

        val json = JSONObject(File(paths.hostOpenclawConfig, "openclaw.json").readText())
        val env = json.getJSONObject("env")
        assertEquals("sk-ant", env.getString("ANTHROPIC_API_KEY"))
        assertEquals("sk-oai", env.getString("OPENAI_API_KEY"))
    }

    @Test
    fun `writeConfig writes primary model`() {
        mockConfig(
            ModelConfig(
                primaryModel = "zai/glm-4.5",
                providers = listOf(builtinEntry("zai", "zai-key")),
            ),
        )
        writer.writeConfig()

        val json = JSONObject(File(paths.hostOpenclawConfig, "openclaw.json").readText())
        val model = json.getJSONObject("agents").getJSONObject("defaults").getJSONObject("model")
        assertEquals("zai/glm-4.5", model.getString("primary"))
    }

    @Test
    fun `writeConfig writes custom models providers block`() {
        val config = ModelConfig(
            primaryModel = "my-vendor/glm-4-flash",
            providers = listOf(
                ModelProviderEntry(
                    providerId = "my-vendor",
                    baseUrl = "https://api.example.com/v1",
                    apiKey = "sk-custom",
                    apiType = ModelApiType.OPENAI_COMPLETIONS,
                    modelId = "glm-4-flash",
                ),
            ),
        )
        writer.writeConfig(config)

        val json = JSONObject(File(paths.hostOpenclawConfig, "openclaw.json").readText())
        assertFalse(json.has("env"))
        val provider = json.getJSONObject("models")
            .getJSONObject("providers")
            .getJSONObject("my-vendor")
        assertEquals("https://api.example.com/v1", provider.getString("baseUrl"))
        assertEquals("sk-custom", provider.getString("apiKey"))
        assertEquals("glm-4-flash", provider.getJSONArray("models").getJSONObject(0).getString("id"))
    }

    @Test
    fun `writeConfig sets gateway mode to local`() {
        mockConfig(ModelConfig.Empty)
        writer.writeConfig()

        val json = JSONObject(File(paths.hostOpenclawConfig, "openclaw.json").readText())
        assertEquals("local", json.getJSONObject("gateway").getString("mode"))
    }
}
