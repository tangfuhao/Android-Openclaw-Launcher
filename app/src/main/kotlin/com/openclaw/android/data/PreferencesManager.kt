package com.openclaw.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "openclaw_settings")

class PreferencesManager(private val context: Context) {

    private object Keys {
        val ROOTFS_INSTALLED = booleanPreferencesKey("rootfs_installed")
        val ROOTFS_VERSION = stringPreferencesKey("rootfs_version")
        val GATEWAY_AUTOSTART = booleanPreferencesKey("gateway_autostart")
        val BACKGROUND_ENABLED = booleanPreferencesKey("background_enabled")
        val MODEL_CONFIG = stringPreferencesKey("model_config")
        val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        val DEVICE_ID = stringPreferencesKey("device_id")

        // Legacy keys (migration only)
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val LEGACY_API_KEY_ANTHROPIC = stringPreferencesKey("api_key_anthropic")
        val LEGACY_API_KEY_OPENAI = stringPreferencesKey("api_key_openai")
        val LEGACY_API_KEY_GOOGLE = stringPreferencesKey("api_key_google")
        val LEGACY_API_KEY_OPENROUTER = stringPreferencesKey("api_key_openrouter")
        val LEGACY_API_KEY_MINIMAX_CN = stringPreferencesKey("api_key_minimax_cn")
        val LEGACY_API_KEY_ZAI = stringPreferencesKey("api_key_zai")
        val LEGACY_API_KEY_KIMI_CODING = stringPreferencesKey("api_key_kimi_coding")
    }

    private data class LegacyProvider(
        val providerId: String,
        val key: Preferences.Key<String>,
        val defaultApiType: String,
    )

    private val legacyProviders = listOf(
        LegacyProvider("anthropic", Keys.LEGACY_API_KEY_ANTHROPIC, ModelApiType.ANTHROPIC_MESSAGES),
        LegacyProvider("openai", Keys.LEGACY_API_KEY_OPENAI, ModelApiType.OPENAI_COMPLETIONS),
        LegacyProvider("google", Keys.LEGACY_API_KEY_GOOGLE, ModelApiType.GOOGLE_GENERATIVE_AI),
        LegacyProvider("openrouter", Keys.LEGACY_API_KEY_OPENROUTER, ModelApiType.OPENAI_COMPLETIONS),
        LegacyProvider("minimax-cn", Keys.LEGACY_API_KEY_MINIMAX_CN, ModelApiType.OPENAI_COMPLETIONS),
        LegacyProvider("zai", Keys.LEGACY_API_KEY_ZAI, ModelApiType.OPENAI_COMPLETIONS),
        LegacyProvider("kimi-coding", Keys.LEGACY_API_KEY_KIMI_CODING, ModelApiType.OPENAI_COMPLETIONS),
    )

    // --- Rootfs ---

    val isRootfsInstalled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ROOTFS_INSTALLED] ?: false }

    suspend fun setRootfsInstalled(value: Boolean) {
        context.dataStore.edit { it[Keys.ROOTFS_INSTALLED] = value }
    }

    suspend fun setRootfsVersion(version: String) {
        context.dataStore.edit { it[Keys.ROOTFS_VERSION] = version }
    }

    // --- Gateway ---

    val isGatewayAutostart: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.GATEWAY_AUTOSTART] ?: true }

    val isBackgroundEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.BACKGROUND_ENABLED] ?: true }

    suspend fun setBackgroundEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.BACKGROUND_ENABLED] = value }
    }

    // --- Model config ---

    val modelConfig: Flow<ModelConfig> =
        context.dataStore.data.map { prefs -> parseModelConfig(prefs) }

    suspend fun setModelConfig(config: ModelConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MODEL_CONFIG] = config.toJson()
        }
    }

    fun getModelConfigSync(): ModelConfig = runBlocking {
        parseModelConfig(context.dataStore.data.first())
    }

    private fun parseModelConfig(prefs: Preferences): ModelConfig {
        val raw = prefs[Keys.MODEL_CONFIG]
        if (!raw.isNullOrBlank()) {
            return ModelConfig.fromJson(raw)
        }
        return migrateLegacyModelConfig(prefs)
    }

    private fun migrateLegacyModelConfig(prefs: Preferences): ModelConfig {
        val selectedModel = prefs[Keys.SELECTED_MODEL] ?: ""
        val entries = legacyProviders.mapNotNull { legacy ->
            val key = prefs[legacy.key]?.trim().orEmpty()
            if (key.isBlank()) return@mapNotNull null
            val modelId = when {
                selectedModel.startsWith("${legacy.providerId}/") ->
                    selectedModel.removePrefix("${legacy.providerId}/")
                selectedModel.isNotBlank() && selectedModel.contains("/") ->
                    selectedModel.substringAfter("/")
                else -> ""
            }
            ModelProviderEntry(
                providerId = legacy.providerId,
                baseUrl = "",
                apiKey = key,
                apiType = legacy.defaultApiType,
                modelId = modelId,
            )
        }
        return ModelConfig(
            primaryModel = selectedModel,
            providers = entries,
        )
    }

    // --- Setup ---

    val isSetupCompleted: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.SETUP_COMPLETED] ?: false }

    suspend fun setSetupCompleted(value: Boolean) {
        context.dataStore.edit { it[Keys.SETUP_COMPLETED] = value }
    }

    // --- Device ---

    suspend fun getOrCreateDeviceId(): String {
        val prefs = context.dataStore.data.first()
        val existing = prefs[Keys.DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing

        val newId = "android-${android.os.Build.MODEL.replace(" ", "-")}-${
            java.util.UUID.randomUUID().toString().take(8)
        }"
        context.dataStore.edit { it[Keys.DEVICE_ID] = newId }
        return newId
    }

    fun isRootfsInstalledSync(): Boolean = runBlocking {
        isRootfsInstalled.first()
    }

    fun isBackgroundEnabledSync(): Boolean = runBlocking {
        isBackgroundEnabled.first()
    }
}
