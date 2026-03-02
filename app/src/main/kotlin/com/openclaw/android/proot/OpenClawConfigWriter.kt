package com.openclaw.android.proot

import android.util.Log
import com.openclaw.android.core.OpenClawConstants
import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.data.PreferencesManager.ApiProvider
import org.json.JSONObject
import java.io.File

/**
 * Generates and writes `openclaw.json` to the rootfs config directory based on
 * the user's provider settings stored in [PreferencesManager].
 *
 * All providers are injected via environment variables, which take highest
 * precedence in OpenClaw's config resolution.
 */
class OpenClawConfigWriter(
    private val paths: OpenClawConstants.Paths,
    private val preferencesManager: PreferencesManager,
) {
    companion object {
        private const val TAG = "OpenClawConfigWriter"
    }

    /**
     * Writes `openclaw.json` and `auth-profiles.json` to the rootfs config directory.
     * Should be called from IO dispatcher before gateway starts.
     */
    fun writeConfig() {
        val configs = preferencesManager.getProviderConfigsSync()

        val configJson = buildConfigJson(configs)
        val configFile = File(paths.hostOpenclawConfig, "openclaw.json")
        paths.hostOpenclawConfig.mkdirs()
        configFile.writeText(configJson.toString(2))
        Log.i(TAG, "Wrote openclaw.json to ${configFile.absolutePath}")

        writeAuthProfiles(configs)
    }

    /**
     * Writes API keys to `auth-profiles.json` in the per-agent directory.
     * OpenClaw resolves provider credentials from this file.
     */
    private fun writeAuthProfiles(configs: Map<ApiProvider, PreferencesManager.ProviderConfig>) {
        val agentDir = File(paths.hostOpenclawConfig, "agents/main/agent")
        agentDir.mkdirs()

        val profiles = JSONObject()
        for ((provider, config) in configs) {
            if (!config.hasApiKey) continue
            val providerName = providerConfigName(provider)
            profiles.put(providerName, JSONObject().apply {
                put("type", "api_key")
                put("provider", providerName)
                put("key", config.apiKey)
            })
        }

        val authFile = File(agentDir, "auth-profiles.json")
        val authJson = JSONObject().apply { put("profiles", profiles) }
        authFile.writeText(authJson.toString(2))
        Log.i(TAG, "Wrote auth-profiles.json with ${profiles.length()} provider(s)")
    }

    private fun buildConfigJson(configs: Map<ApiProvider, PreferencesManager.ProviderConfig>): JSONObject {
        val root = JSONObject()

        val envBlock = JSONObject()
        for ((provider, config) in configs) {
            if (!config.hasApiKey) continue
            envBlock.put(provider.envVarName, config.apiKey)
        }
        if (envBlock.length() > 0) {
            root.put("env", envBlock)
        }

        root.put("gateway", JSONObject().apply { put("mode", "local") })

        root.put("messages", JSONObject().apply {
            put("queue", JSONObject().apply { put("mode", "steer") })
        })

        val selectedModel = preferencesManager.getSelectedModelSync()
        if (selectedModel.isNotBlank()) {
            root.put("agents", JSONObject().apply {
                put("defaults", JSONObject().apply {
                    put("model", JSONObject().apply { put("primary", selectedModel) })
                })
            })
        }

        return root
    }

    /**
     * Returns the environment variables map for API keys.
     */
    fun getApiKeyEnvVars(): Map<String, String> {
        val configs = preferencesManager.getProviderConfigsSync()
        return buildMap {
            for ((provider, config) in configs) {
                if (!config.hasApiKey) continue
                put(provider.envVarName, config.apiKey)
            }
        }
    }

    /** Maps [ApiProvider] to the provider name used in OpenClaw config files. */
    private fun providerConfigName(provider: ApiProvider): String = when (provider) {
        ApiProvider.ANTHROPIC -> "anthropic"
        ApiProvider.OPENAI -> "openai"
        ApiProvider.GOOGLE -> "google"
        ApiProvider.OPENROUTER -> "openrouter"
        ApiProvider.MINIMAX_CN -> "minimax-cn"
        ApiProvider.ZAI -> "zai"
        ApiProvider.KIMI_CODING -> "kimi-coding"
    }
}
