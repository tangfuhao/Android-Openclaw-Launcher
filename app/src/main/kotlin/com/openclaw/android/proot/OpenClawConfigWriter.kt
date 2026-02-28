package com.openclaw.android.proot

import android.util.Log
import com.openclaw.android.core.OpenClawConstants
import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.data.PreferencesManager.ApiProvider
import com.openclaw.android.data.PreferencesManager.ApiType
import org.json.JSONObject
import java.io.File

/**
 * Generates and writes `openclaw.json` to the rootfs config directory based on
 * the user's provider settings stored in [PreferencesManager].
 *
 * Standard providers (no custom base URL) are injected via environment variables,
 * which take highest precedence in OpenClaw's config resolution.
 *
 * Providers with a custom base URL are configured as custom providers in the
 * `models.providers` section of `openclaw.json`, with the appropriate `api` type.
 */
class OpenClawConfigWriter(
    private val paths: OpenClawConstants.Paths,
    private val preferencesManager: PreferencesManager,
) {
    companion object {
        private const val TAG = "OpenClawConfigWriter"
    }

    /**
     * Writes `openclaw.json` to `rootfs/root/.openclaw/openclaw.json`.
     * Should be called from IO dispatcher before gateway starts.
     */
    fun writeConfig() {
        val configs = preferencesManager.getProviderConfigsSync()
        val configJson = buildConfigJson(configs)

        val configFile = File(paths.hostOpenclawConfig, "openclaw.json")
        paths.hostOpenclawConfig.mkdirs()
        configFile.writeText(configJson.toString(2))

        Log.i(TAG, "Wrote openclaw.json to ${configFile.absolutePath}")
    }

    private fun buildConfigJson(configs: Map<ApiProvider, PreferencesManager.ProviderConfig>): JSONObject {
        val root = JSONObject()

        val envBlock = JSONObject()
        val providersBlock = JSONObject()
        var hasCustomProvider = false

        for ((provider, config) in configs) {
            if (!config.hasApiKey) continue

            if (config.hasCustomBaseUrl) {
                val providerName = "${provider.name.lowercase()}-custom"
                val providerObj = JSONObject().apply {
                    put("baseUrl", config.baseUrl)
                    put("apiKey", config.apiKey)
                    put("api", config.apiType.ifBlank { defaultApiType(provider) })
                }
                providersBlock.put(providerName, providerObj)
                hasCustomProvider = true
            } else {
                envBlock.put(provider.envVarName, config.apiKey)
            }
        }

        if (envBlock.length() > 0) {
            root.put("env", envBlock)
        }

        if (hasCustomProvider) {
            val modelsBlock = JSONObject()
            modelsBlock.put("providers", providersBlock)
            root.put("models", modelsBlock)
        }

        return root
    }

    /**
     * Returns the environment variables map for API keys that don't use custom base URLs.
     * These should be passed to [ProotExecutor.buildEnvironment].
     */
    fun getApiKeyEnvVars(): Map<String, String> {
        val configs = preferencesManager.getProviderConfigsSync()
        return buildMap {
            for ((provider, config) in configs) {
                if (config.hasApiKey && !config.hasCustomBaseUrl) {
                    put(provider.envVarName, config.apiKey)
                }
            }
        }
    }

    private fun defaultApiType(provider: ApiProvider): String = when (provider) {
        ApiProvider.ANTHROPIC -> ApiType.ANTHROPIC_MESSAGES
        ApiProvider.OPENAI -> ApiType.OPENAI_COMPLETIONS
        ApiProvider.OPENROUTER -> ApiType.OPENAI_COMPLETIONS
        ApiProvider.GOOGLE -> ApiType.OPENAI_COMPLETIONS
    }
}
