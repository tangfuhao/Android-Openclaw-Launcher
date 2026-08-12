package com.openclaw.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.android.data.ModelConfig
import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.gateway.ConnectivityResult
import com.openclaw.android.gateway.ModelConnectivityChecker
import com.openclaw.android.proot.OpenClawConfigWriter
import com.openclaw.android.service.ProcessManager
import com.openclaw.android.ui.components.ModelConfigFormState
import com.openclaw.android.ui.components.toFormState
import com.openclaw.android.ui.components.toModelConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val processManager: ProcessManager,
    private val configWriter: OpenClawConfigWriter,
    private val connectivityChecker: ModelConnectivityChecker,
) : ViewModel() {

    val processState: StateFlow<String> = processManager.processState
        .map { it.displayText }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Unknown")

    val backgroundEnabled: StateFlow<Boolean> = preferencesManager.isBackgroundEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val modelConfig: StateFlow<ModelConfig> = preferencesManager.modelConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ModelConfig.Empty)

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _saveMessage = MutableStateFlow<String?>(null)
    val saveMessage: StateFlow<String?> = _saveMessage.asStateFlow()

    fun setBackgroundEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setBackgroundEnabled(enabled) }
    }

    fun saveModelConfig(formState: ModelConfigFormState) {
        val config = formState.toModelConfig()
        config.validate()?.let {
            _saveMessage.value = it
            return
        }
        viewModelScope.launch {
            preferencesManager.setModelConfig(config)
            withContext(Dispatchers.IO) { configWriter.writeConfig(config) }
            _saveMessage.value = "Saved. Restart Gateway for env changes to take effect."
            _testResult.value = null
        }
    }

    fun testConnection(formState: ModelConfigFormState) {
        val config = formState.toModelConfig()
        config.validate()?.let {
            _testResult.value = it
            return
        }
        viewModelScope.launch {
            _isTesting.value = true
            _testResult.value = null
            when (val result = connectivityChecker.test(config)) {
                is ConnectivityResult.Success ->
                    _testResult.value = "Connected (${result.latencyMs}ms). API reachable; Agent tool support may vary."
                is ConnectivityResult.Failure ->
                    _testResult.value = result.message
            }
            _isTesting.value = false
        }
    }

    fun clearMessages() {
        _saveMessage.value = null
        _testResult.value = null
    }
}
