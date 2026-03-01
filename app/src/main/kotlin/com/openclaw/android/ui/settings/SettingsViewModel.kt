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

    val selectedModel: StateFlow<String> = preferencesManager.selectedModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val apiKeys: StateFlow<Map<ApiProvider, String>> = preferencesManager.allApiKeys
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun setBackgroundEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setBackgroundEnabled(enabled) }
    }

    fun saveProviderConfig(provider: ApiProvider, apiKey: String) {
        viewModelScope.launch {
            preferencesManager.setApiKey(provider, apiKey)
            launch(Dispatchers.IO) { configWriter.writeConfig() }
        }
    }

    fun setSelectedModel(modelId: String) {
        viewModelScope.launch {
            preferencesManager.setSelectedModel(modelId)
            launch(Dispatchers.IO) { configWriter.writeConfig() }
        }
    }
}
