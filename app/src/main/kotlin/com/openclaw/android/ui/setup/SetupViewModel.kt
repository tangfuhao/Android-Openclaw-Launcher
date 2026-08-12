package com.openclaw.android.ui.setup

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.android.BuildConfig
import com.openclaw.android.data.ModelConfig
import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.gateway.ConnectivityResult
import com.openclaw.android.gateway.ModelConnectivityChecker
import com.openclaw.android.proot.OpenClawConfigWriter
import com.openclaw.android.proot.RootfsInstaller
import com.openclaw.android.proot.RootfsState
import com.openclaw.android.ui.components.ModelConfigFormState
import com.openclaw.android.ui.components.toModelConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootfsInstaller: RootfsInstaller,
    private val preferencesManager: PreferencesManager,
    private val configWriter: OpenClawConfigWriter,
    private val connectivityChecker: ModelConnectivityChecker,
) : ViewModel() {

    private val _currentStep = MutableStateFlow(SetupStep.WELCOME)
    val currentStep: StateFlow<SetupStep> = _currentStep.asStateFlow()

    val rootfsState: StateFlow<RootfsState> = rootfsInstaller.state

    private val _deviceCheck = MutableStateFlow(DeviceCheck())
    val deviceCheck: StateFlow<DeviceCheck> = _deviceCheck.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    init {
        checkDevice()
    }

    fun nextStep() {
        _currentStep.value = when (_currentStep.value) {
            SetupStep.WELCOME -> SetupStep.DEVICE_CHECK
            SetupStep.DEVICE_CHECK -> SetupStep.DOWNLOAD
            SetupStep.DOWNLOAD -> SetupStep.API_KEY
            SetupStep.API_KEY -> SetupStep.COMPLETE
            SetupStep.COMPLETE -> SetupStep.COMPLETE
        }
    }

    fun startInstallation() {
        viewModelScope.launch {
            rootfsInstaller.install(BuildConfig.ROOTFS_URL)
        }
    }

    fun saveModelConfig(formState: ModelConfigFormState) {
        val config = formState.toModelConfig()
        config.validate()?.let {
            _testResult.value = it
            return
        }
        viewModelScope.launch {
            preferencesManager.setModelConfig(config)
            withContext(Dispatchers.IO) { configWriter.writeConfig(config) }
            nextStep()
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
                    _testResult.value = "Connected (${result.latencyMs}ms)"
                is ConnectivityResult.Failure ->
                    _testResult.value = result.message
            }
            _isTesting.value = false
        }
    }

    fun finishSetup() {
        viewModelScope.launch {
            preferencesManager.setSetupCompleted(true)
        }
    }

    private fun checkDevice() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalRamMb = (memInfo.totalMem / 1_048_576).toInt()

        val stat = StatFs(Environment.getDataDirectory().path)
        val freeStorageMb = (stat.availableBlocksLong * stat.blockSizeLong / 1_048_576).toInt()

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkOk = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        _deviceCheck.value = DeviceCheck(
            totalRamMb = totalRamMb,
            freeStorageMb = freeStorageMb,
            networkOk = networkOk,
            ramOk = totalRamMb >= 4096,
            storageOk = freeStorageMb >= 3072,
        )
    }

    data class DeviceCheck(
        val totalRamMb: Int = 0,
        val freeStorageMb: Int = 0,
        val networkOk: Boolean = false,
        val ramOk: Boolean = false,
        val storageOk: Boolean = false,
    ) {
        val allOk: Boolean get() = ramOk && storageOk && networkOk
    }
}
