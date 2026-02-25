package com.openclaw.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.service.ProcessManager
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {

    val processState: StateFlow<String> = processManager.processState
        .map { it.displayText }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Unknown")

    val backgroundEnabled: StateFlow<Boolean> = preferencesManager.isBackgroundEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val anthropicKey: StateFlow<String> = preferencesManager.anthropicApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val openaiKey: StateFlow<String> = preferencesManager.openaiApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun setBackgroundEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setBackgroundEnabled(enabled) }
    }

    fun setAnthropicKey(key: String) {
        viewModelScope.launch { preferencesManager.setApiKey(PreferencesManager.ApiProvider.ANTHROPIC, key) }
    }

    fun setOpenaiKey(key: String) {
        viewModelScope.launch { preferencesManager.setApiKey(PreferencesManager.ApiProvider.OPENAI, key) }
    }
}
