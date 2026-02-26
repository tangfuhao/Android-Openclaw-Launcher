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
import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.proot.RootfsInstaller
import com.openclaw.android.proot.RootfsState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootfsInstaller: RootfsInstaller,
    private val preferencesManager: PreferencesManager,
) : ViewModel() {

    private val _currentStep = MutableStateFlow(SetupStep.WELCOME)
    val currentStep: StateFlow<SetupStep> = _currentStep.asStateFlow()

    val rootfsState: StateFlow<RootfsState> = rootfsInstaller.state

    private val _deviceCheck = MutableStateFlow(DeviceCheck())
    val deviceCheck: StateFlow<DeviceCheck> = _deviceCheck.asStateFlow()

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

    fun saveApiKey(key: String) {
        viewModelScope.launch {
            if (key.startsWith("sk-ant-")) {
                preferencesManager.setApiKey(PreferencesManager.ApiProvider.ANTHROPIC, key)
            } else if (key.startsWith("sk-")) {
                preferencesManager.setApiKey(PreferencesManager.ApiProvider.OPENAI, key)
            } else {
                preferencesManager.setApiKey(PreferencesManager.ApiProvider.ANTHROPIC, key)
            }
            nextStep()
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
