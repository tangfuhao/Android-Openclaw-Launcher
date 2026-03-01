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
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

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

    // --- API Keys ---

    /** Single reactive stream of all provider API keys. */
    val allApiKeys: Flow<Map<ApiProvider, String>> =
        context.dataStore.data.map { prefs ->
            ApiProvider.entries.associateWith { prefs[it.preferencesKey] ?: "" }
        }

    suspend fun setApiKey(provider: ApiProvider, key: String) {
        context.dataStore.edit { prefs ->
            prefs[provider.preferencesKey] = key
        }
    }

    // --- Model ---

    val selectedModel: Flow<String> =
        context.dataStore.data.map { it[Keys.SELECTED_MODEL] ?: "" }

    suspend fun setSelectedModel(model: String) {
        context.dataStore.edit { it[Keys.SELECTED_MODEL] = model }
    }

    fun getSelectedModelSync(): String = runBlocking {
        context.dataStore.data.first()[Keys.SELECTED_MODEL] ?: ""
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

    /** Blocking read for use in Service/BroadcastReceiver where coroutines aren't available. */
    fun isRootfsInstalledSync(): Boolean = runBlocking {
        isRootfsInstalled.first()
    }

    fun isBackgroundEnabledSync(): Boolean = runBlocking {
        isBackgroundEnabled.first()
    }

    /**
     * Returns a snapshot of all API keys for config generation.
     * Blocking — call from IO dispatcher.
     */
    fun getProviderConfigsSync(): Map<ApiProvider, ProviderConfig> = runBlocking {
        val prefs = context.dataStore.data.first()
        ApiProvider.entries.associateWith { provider ->
            ProviderConfig(apiKey = prefs[provider.preferencesKey] ?: "")
        }
    }

    enum class ApiProvider(
        val envVarName: String,
        val displayName: String,
        val keyHint: String,
        val defaultModel: String,
        val availableModels: List<ModelOption>,
    ) {
        ANTHROPIC(
            envVarName = "ANTHROPIC_API_KEY",
            displayName = "Anthropic",
            keyHint = "sk-ant-...",
            defaultModel = "anthropic/claude-sonnet-4-20250514",
            availableModels = listOf(
                ModelOption("anthropic/claude-sonnet-4-20250514", "Claude Sonnet 4"),
                ModelOption("anthropic/claude-haiku-4-5-20251001", "Claude Haiku 4.5"),
                ModelOption("anthropic/claude-opus-4-20250514", "Claude Opus 4"),
            ),
        ),
        OPENAI(
            envVarName = "OPENAI_API_KEY",
            displayName = "OpenAI",
            keyHint = "sk-...",
            defaultModel = "openai/o4-mini",
            availableModels = listOf(
                ModelOption("openai/o4-mini", "o4-mini"),
                ModelOption("openai/gpt-4.1", "GPT-4.1"),
                ModelOption("openai/gpt-4.1-mini", "GPT-4.1 Mini"),
            ),
        ),
        GOOGLE(
            envVarName = "GEMINI_API_KEY",
            displayName = "Google",
            keyHint = "AIza...",
            defaultModel = "google/gemini-2.5-flash",
            availableModels = listOf(
                ModelOption("google/gemini-2.5-flash", "Gemini 2.5 Flash"),
                ModelOption("google/gemini-2.5-pro", "Gemini 2.5 Pro"),
            ),
        ),
        OPENROUTER(
            envVarName = "OPENROUTER_API_KEY",
            displayName = "OpenRouter",
            keyHint = "sk-or-...",
            defaultModel = "anthropic/claude-sonnet-4-20250514",
            availableModels = listOf(
                ModelOption("anthropic/claude-sonnet-4-20250514", "Claude Sonnet 4"),
                ModelOption("openai/o4-mini", "o4-mini"),
                ModelOption("google/gemini-2.5-flash", "Gemini 2.5 Flash"),
            ),
        ),
        MINIMAX_CN(
            envVarName = "MINIMAX_CN_API_KEY",
            displayName = "MiniMax (中国)",
            keyHint = "eyJ...",
            defaultModel = "minimax-cn/MiniMax-M2.5",
            availableModels = listOf(
                ModelOption("minimax-cn/MiniMax-M2.5", "MiniMax-M2.5"),
                ModelOption("minimax-cn/MiniMax-M2.1", "MiniMax-M2.1"),
                ModelOption("minimax-cn/MiniMax-M2", "MiniMax-M2"),
            ),
        ),
        ZAI(
            envVarName = "ZAI_API_KEY",
            displayName = "智谱 GLM",
            keyHint = "",
            defaultModel = "zai/glm-4.5",
            availableModels = listOf(
                ModelOption("zai/glm-4.5", "GLM-4.5"),
                ModelOption("zai/glm-4.5-air", "GLM-4.5 Air"),
                ModelOption("zai/glm-4.5-flash", "GLM-4.5 Flash"),
            ),
        ),
        KIMI_CODING(
            envVarName = "KIMI_API_KEY",
            displayName = "Kimi",
            keyHint = "",
            defaultModel = "kimi-coding/k2p5",
            availableModels = listOf(
                ModelOption("kimi-coding/k2p5", "Kimi K2.5"),
                ModelOption("kimi-coding/kimi-k2-thinking", "Kimi K2 Thinking"),
            ),
        );

        val preferencesKey: Preferences.Key<String>
            get() = stringPreferencesKey("api_key_${name.lowercase()}")
    }

    data class ModelOption(val id: String, val displayName: String)

    data class ProviderConfig(val apiKey: String) {
        val hasApiKey: Boolean get() = apiKey.isNotBlank()
    }
}
