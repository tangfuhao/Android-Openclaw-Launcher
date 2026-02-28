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

        val API_KEY_ANTHROPIC = stringPreferencesKey("api_key_anthropic")
        val API_KEY_OPENAI = stringPreferencesKey("api_key_openai")
        val API_KEY_GOOGLE = stringPreferencesKey("api_key_google")
        val API_KEY_OPENROUTER = stringPreferencesKey("api_key_openrouter")

        val BASE_URL_ANTHROPIC = stringPreferencesKey("base_url_anthropic")
        val BASE_URL_OPENAI = stringPreferencesKey("base_url_openai")
        val BASE_URL_OPENROUTER = stringPreferencesKey("base_url_openrouter")

        val API_TYPE_ANTHROPIC = stringPreferencesKey("api_type_anthropic")
        val API_TYPE_OPENAI = stringPreferencesKey("api_type_openai")
        val API_TYPE_OPENROUTER = stringPreferencesKey("api_type_openrouter")

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

    val anthropicApiKey: Flow<String> =
        context.dataStore.data.map { it[Keys.API_KEY_ANTHROPIC] ?: "" }

    val openaiApiKey: Flow<String> =
        context.dataStore.data.map { it[Keys.API_KEY_OPENAI] ?: "" }

    val googleApiKey: Flow<String> =
        context.dataStore.data.map { it[Keys.API_KEY_GOOGLE] ?: "" }

    val openrouterApiKey: Flow<String> =
        context.dataStore.data.map { it[Keys.API_KEY_OPENROUTER] ?: "" }

    suspend fun setApiKey(provider: ApiProvider, key: String) {
        context.dataStore.edit { prefs ->
            when (provider) {
                ApiProvider.ANTHROPIC -> prefs[Keys.API_KEY_ANTHROPIC] = key
                ApiProvider.OPENAI -> prefs[Keys.API_KEY_OPENAI] = key
                ApiProvider.GOOGLE -> prefs[Keys.API_KEY_GOOGLE] = key
                ApiProvider.OPENROUTER -> prefs[Keys.API_KEY_OPENROUTER] = key
            }
        }
    }

    // --- Base URLs ---

    val anthropicBaseUrl: Flow<String> =
        context.dataStore.data.map { it[Keys.BASE_URL_ANTHROPIC] ?: "" }

    val openaiBaseUrl: Flow<String> =
        context.dataStore.data.map { it[Keys.BASE_URL_OPENAI] ?: "" }

    val openrouterBaseUrl: Flow<String> =
        context.dataStore.data.map { it[Keys.BASE_URL_OPENROUTER] ?: "" }

    suspend fun setBaseUrl(provider: ApiProvider, url: String) {
        context.dataStore.edit { prefs ->
            when (provider) {
                ApiProvider.ANTHROPIC -> prefs[Keys.BASE_URL_ANTHROPIC] = url
                ApiProvider.OPENAI -> prefs[Keys.BASE_URL_OPENAI] = url
                ApiProvider.OPENROUTER -> prefs[Keys.BASE_URL_OPENROUTER] = url
                ApiProvider.GOOGLE -> { /* Google doesn't support custom base URL */ }
            }
        }
    }

    // --- API Types (for custom base URL endpoints) ---

    val anthropicApiType: Flow<String> =
        context.dataStore.data.map { it[Keys.API_TYPE_ANTHROPIC] ?: ApiType.ANTHROPIC_MESSAGES }

    val openaiApiType: Flow<String> =
        context.dataStore.data.map { it[Keys.API_TYPE_OPENAI] ?: ApiType.OPENAI_COMPLETIONS }

    val openrouterApiType: Flow<String> =
        context.dataStore.data.map { it[Keys.API_TYPE_OPENROUTER] ?: ApiType.OPENAI_COMPLETIONS }

    suspend fun setApiType(provider: ApiProvider, type: String) {
        context.dataStore.edit { prefs ->
            when (provider) {
                ApiProvider.ANTHROPIC -> prefs[Keys.API_TYPE_ANTHROPIC] = type
                ApiProvider.OPENAI -> prefs[Keys.API_TYPE_OPENAI] = type
                ApiProvider.OPENROUTER -> prefs[Keys.API_TYPE_OPENROUTER] = type
                ApiProvider.GOOGLE -> { /* not applicable */ }
            }
        }
    }

    // --- Model ---

    val selectedModel: Flow<String> =
        context.dataStore.data.map { it[Keys.SELECTED_MODEL] ?: "claude-sonnet-4-20250514" }

    suspend fun setSelectedModel(model: String) {
        context.dataStore.edit { it[Keys.SELECTED_MODEL] = model }
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
     * Returns a snapshot of all API keys and base URLs for config generation.
     * Blocking — call from IO dispatcher.
     */
    fun getProviderConfigsSync(): Map<ApiProvider, ProviderConfig> = runBlocking {
        val prefs = context.dataStore.data.first()
        ApiProvider.entries.associateWith { provider ->
            ProviderConfig(
                apiKey = when (provider) {
                    ApiProvider.ANTHROPIC -> prefs[Keys.API_KEY_ANTHROPIC] ?: ""
                    ApiProvider.OPENAI -> prefs[Keys.API_KEY_OPENAI] ?: ""
                    ApiProvider.GOOGLE -> prefs[Keys.API_KEY_GOOGLE] ?: ""
                    ApiProvider.OPENROUTER -> prefs[Keys.API_KEY_OPENROUTER] ?: ""
                },
                baseUrl = when (provider) {
                    ApiProvider.ANTHROPIC -> prefs[Keys.BASE_URL_ANTHROPIC] ?: ""
                    ApiProvider.OPENAI -> prefs[Keys.BASE_URL_OPENAI] ?: ""
                    ApiProvider.OPENROUTER -> prefs[Keys.BASE_URL_OPENROUTER] ?: ""
                    ApiProvider.GOOGLE -> ""
                },
                apiType = when (provider) {
                    ApiProvider.ANTHROPIC -> prefs[Keys.API_TYPE_ANTHROPIC] ?: ApiType.ANTHROPIC_MESSAGES
                    ApiProvider.OPENAI -> prefs[Keys.API_TYPE_OPENAI] ?: ApiType.OPENAI_COMPLETIONS
                    ApiProvider.OPENROUTER -> prefs[Keys.API_TYPE_OPENROUTER] ?: ApiType.OPENAI_COMPLETIONS
                    ApiProvider.GOOGLE -> ""
                },
            )
        }
    }

    enum class ApiProvider {
        ANTHROPIC, OPENAI, GOOGLE, OPENROUTER;

        val envVarName: String
            get() = when (this) {
                ANTHROPIC -> "ANTHROPIC_API_KEY"
                OPENAI -> "OPENAI_API_KEY"
                GOOGLE -> "GOOGLE_API_KEY"
                OPENROUTER -> "OPENROUTER_API_KEY"
            }

        val displayName: String
            get() = when (this) {
                ANTHROPIC -> "Anthropic"
                OPENAI -> "OpenAI"
                GOOGLE -> "Google"
                OPENROUTER -> "OpenRouter"
            }
    }

    data class ProviderConfig(
        val apiKey: String,
        val baseUrl: String,
        val apiType: String,
    ) {
        val hasCustomBaseUrl: Boolean get() = baseUrl.isNotBlank()
        val hasApiKey: Boolean get() = apiKey.isNotBlank()
    }

    object ApiType {
        const val ANTHROPIC_MESSAGES = "anthropic-messages"
        const val OPENAI_COMPLETIONS = "openai-completions"
    }
}
