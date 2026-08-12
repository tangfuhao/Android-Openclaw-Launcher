package com.openclaw.android.proot

import android.util.Log
import com.openclaw.android.core.OpenClawConstants
import com.openclaw.android.data.ModelConfig
import com.openclaw.android.data.ModelProviderEntry
import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.data.ProviderEnvLookup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Generates and writes `openclaw.json` to the rootfs config directory based on
 * the user's [ModelConfig] stored in [PreferencesManager].
 */
class OpenClawConfigWriter(
    private val paths: OpenClawConstants.Paths,
    private val preferencesManager: PreferencesManager,
) {
    companion object {
        private const val TAG = "OpenClawConfigWriter"
    }

    fun writeConfig() {
        val config = preferencesManager.getModelConfigSync()
        writeConfig(config)
    }

    fun writeConfig(config: ModelConfig) {
        val configJson = buildConfigJson(config)
        val configFile = File(paths.hostOpenclawConfig, "openclaw.json")
        paths.hostOpenclawConfig.mkdirs()
        configFile.writeText(configJson.toString(2))
        Log.i(TAG, "Wrote openclaw.json to ${configFile.absolutePath}")

        writeAuthProfiles(config)
    }

    private fun writeAuthProfiles(config: ModelConfig) {
        val agentDir = File(paths.hostOpenclawConfig, "agents/main/agent")
        agentDir.mkdirs()

        val profiles = JSONObject()
        for (entry in config.providers) {
            if (!entry.hasApiKey) continue
            val providerName = entry.providerId.lowercase().trim()
            profiles.put(providerName, JSONObject().apply {
                put("type", "api_key")
                put("provider", providerName)
                put("key", entry.apiKey)
            })
        }

        val authFile = File(agentDir, "auth-profiles.json")
        val authJson = JSONObject().apply { put("profiles", profiles) }
        authFile.writeText(authJson.toString(2))
        Log.i(TAG, "Wrote auth-profiles.json with ${profiles.length()} provider(s)")
    }

    private fun buildConfigJson(config: ModelConfig): JSONObject {
        val root = JSONObject()

        val envBlock = JSONObject()
        for (entry in config.providers) {
            if (!entry.hasApiKey || entry.isCustom) continue
            val envVar = ProviderEnvLookup.envVarName(entry.providerId) ?: continue
            envBlock.put(envVar, entry.apiKey)
        }
        if (envBlock.length() > 0) {
            root.put("env", envBlock)
        }

        val customProviders = buildCustomProvidersBlock(config.providers)
        if (customProviders.length() > 0) {
            root.put("models", JSONObject().apply {
                put("mode", "merge")
                put("providers", customProviders)
            })
        }

        root.put("gateway", JSONObject().apply { put("mode", "local") })

        root.put("messages", JSONObject().apply {
            put("queue", JSONObject().apply { put("mode", "steer") })
        })

        if (config.primaryModel.isNotBlank()) {
            root.put("agents", JSONObject().apply {
                put("defaults", JSONObject().apply {
                    put("model", JSONObject().apply { put("primary", config.primaryModel) })
                })
            })
        }

        return root
    }

    private fun buildCustomProvidersBlock(entries: List<ModelProviderEntry>): JSONObject {
        val providers = JSONObject()
        val grouped = entries.filter { it.isCustom && it.hasApiKey }
            .groupBy { it.providerId.lowercase().trim() }

        for ((providerId, group) in grouped) {
            val first = group.first()
            val modelsArray = JSONArray()
            for (entry in group) {
                modelsArray.put(buildModelDefinition(entry))
            }
            providers.put(providerId, JSONObject().apply {
                put("baseUrl", first.baseUrl.trim().trimEnd('/'))
                put("apiKey", first.apiKey)
                put("api", first.apiType)
                put("models", modelsArray)
            })
        }
        return providers
    }

    private fun buildModelDefinition(entry: ModelProviderEntry): JSONObject {
        return JSONObject().apply {
            put("id", entry.modelId)
            put("name", entry.modelId)
            put("reasoning", false)
            put("input", JSONArray().put("text"))
            put("cost", JSONObject().apply {
                put("input", 0)
                put("output", 0)
                put("cacheRead", 0)
                put("cacheWrite", 0)
            })
            put("contextWindow", 128_000)
            put("maxTokens", 32_000)
        }
    }

    fun getApiKeyEnvVars(): Map<String, String> {
        val config = preferencesManager.getModelConfigSync()
        return buildMap {
            for (entry in config.providers) {
                if (!entry.hasApiKey || entry.isCustom) continue
                val envVar = ProviderEnvLookup.envVarName(entry.providerId) ?: continue
                put(envVar, entry.apiKey)
            }
        }
    }
}
