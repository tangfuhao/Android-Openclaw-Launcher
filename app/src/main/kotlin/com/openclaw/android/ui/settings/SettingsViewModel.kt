package com.openclaw.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.data.PreferencesManager.ApiProvider
import com.openclaw.android.proot.OpenClawConfigWriter
import com.openclaw.android.service.ProcessManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val processManager: ProcessManager,
    private val configWriter: OpenClawConfigWriter,
) : ViewModel() {

    val processState: StateFlow<String> = processManager.processState
        .map { it.displayText }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Unknown")

    val backgroundEnabled: StateFlow<Boolean> = preferencesManager.isBackgroundEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // --- API Keys ---
    val anthropicKey: StateFlow<String> = preferencesManager.anthropicApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val openaiKey: StateFlow<String> = preferencesManager.openaiApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val openrouterKey: StateFlow<String> = preferencesManager.openrouterApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // --- Base URLs ---
    val anthropicBaseUrl: StateFlow<String> = preferencesManager.anthropicBaseUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val openaiBaseUrl: StateFlow<String> = preferencesManager.openaiBaseUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val openrouterBaseUrl: StateFlow<String> = preferencesManager.openrouterBaseUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // --- API Types ---
    val anthropicApiType: StateFlow<String> = preferencesManager.anthropicApiType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesManager.ApiType.ANTHROPIC_MESSAGES)

    val openaiApiType: StateFlow<String> = preferencesManager.openaiApiType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesManager.ApiType.OPENAI_COMPLETIONS)

    val openrouterApiType: StateFlow<String> = preferencesManager.openrouterApiType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesManager.ApiType.OPENAI_COMPLETIONS)

    fun setBackgroundEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setBackgroundEnabled(enabled) }
    }

    fun saveProviderConfig(
        provider: ApiProvider,
        apiKey: String,
        baseUrl: String = "",
        apiType: String = "",
    ) {
        viewModelScope.launch {
            preferencesManager.setApiKey(provider, apiKey)
            preferencesManager.setBaseUrl(provider, baseUrl)
            if (apiType.isNotBlank()) {
                preferencesManager.setApiType(provider, apiType)
            }
            launch(Dispatchers.IO) { configWriter.writeConfig() }
        }
    }
}
