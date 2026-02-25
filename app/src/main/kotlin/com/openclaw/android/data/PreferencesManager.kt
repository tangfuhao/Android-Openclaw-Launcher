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
        val BOOTSTRAP_INSTALLED = booleanPreferencesKey("bootstrap_installed")
        val BOOTSTRAP_VERSION = stringPreferencesKey("bootstrap_version")
        val GATEWAY_AUTOSTART = booleanPreferencesKey("gateway_autostart")
        val BACKGROUND_ENABLED = booleanPreferencesKey("background_enabled")
        val API_KEY_ANTHROPIC = stringPreferencesKey("api_key_anthropic")
        val API_KEY_OPENAI = stringPreferencesKey("api_key_openai")
        val API_KEY_GOOGLE = stringPreferencesKey("api_key_google")
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
    }

    // --- Bootstrap ---

    val isBootstrapInstalled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.BOOTSTRAP_INSTALLED] ?: false }

    suspend fun setBootstrapInstalled(value: Boolean) {
        context.dataStore.edit { it[Keys.BOOTSTRAP_INSTALLED] = value }
    }

    suspend fun setBootstrapVersion(version: String) {
        context.dataStore.edit { it[Keys.BOOTSTRAP_VERSION] = version }
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

    suspend fun setApiKey(provider: ApiProvider, key: String) {
        context.dataStore.edit { prefs ->
            when (provider) {
                ApiProvider.ANTHROPIC -> prefs[Keys.API_KEY_ANTHROPIC] = key
                ApiProvider.OPENAI -> prefs[Keys.API_KEY_OPENAI] = key
                ApiProvider.GOOGLE -> prefs[Keys.API_KEY_GOOGLE] = key
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

    /** Blocking read for use in Service/BroadcastReceiver where coroutines aren't available. */
    fun isBootstrapInstalledSync(): Boolean = runBlocking {
        isBootstrapInstalled.first()
    }

    fun isBackgroundEnabledSync(): Boolean = runBlocking {
        isBackgroundEnabled.first()
    }

    enum class ApiProvider { ANTHROPIC, OPENAI, GOOGLE }
}
