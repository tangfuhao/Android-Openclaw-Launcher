package com.openclaw.android.ui.setup

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Environment
import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.data.PreferencesManager.ApiProvider
import com.openclaw.android.proot.OpenClawConfigWriter
import com.openclaw.android.proot.RootfsInstaller
import com.openclaw.android.proot.RootfsState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelStepTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var rootfsInstaller: RootfsInstaller
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var configWriter: OpenClawConfigWriter
    private lateinit var viewModel: SetupViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        rootfsInstaller = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        configWriter = mockk(relaxed = true)

        every { rootfsInstaller.state } returns MutableStateFlow(RootfsState.NotInstalled)

        // Mock Android system services for checkDevice() called in init
        val am = mockk<ActivityManager>(relaxed = true)
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns am

        val cm = mockk<ConnectivityManager>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        val network = mockk<Network>()
        every { cm.activeNetwork } returns network
        val caps = mockk<NetworkCapabilities>()
        every { cm.getNetworkCapabilities(network) } returns caps
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true

        mockkStatic(Environment::class)
        every { Environment.getDataDirectory() } returns File("/data")

        viewModel = SetupViewModel(context, rootfsInstaller, preferencesManager, configWriter)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Environment::class)
    }

    // --- Step state machine ---

    @Test
    fun `initial step is WELCOME`() = runTest {
        advanceUntilIdle()
        assertEquals(SetupStep.WELCOME, viewModel.currentStep.value)
    }

    @Test
    fun `nextStep from WELCOME goes to DEVICE_CHECK`() = runTest {
        advanceUntilIdle()
        viewModel.nextStep()
        assertEquals(SetupStep.DEVICE_CHECK, viewModel.currentStep.value)
    }

    @Test
    fun `nextStep from DEVICE_CHECK goes to DOWNLOAD`() = runTest {
        advanceUntilIdle()
        viewModel.nextStep() // WELCOME -> DEVICE_CHECK
        viewModel.nextStep() // DEVICE_CHECK -> DOWNLOAD
        assertEquals(SetupStep.DOWNLOAD, viewModel.currentStep.value)
    }

    @Test
    fun `nextStep from DOWNLOAD goes to API_KEY`() = runTest {
        advanceUntilIdle()
        repeat(3) { viewModel.nextStep() }
        assertEquals(SetupStep.API_KEY, viewModel.currentStep.value)
    }

    @Test
    fun `nextStep from API_KEY goes to COMPLETE`() = runTest {
        advanceUntilIdle()
        repeat(4) { viewModel.nextStep() }
        assertEquals(SetupStep.COMPLETE, viewModel.currentStep.value)
    }

    @Test
    fun `nextStep from COMPLETE stays at COMPLETE`() = runTest {
        advanceUntilIdle()
        repeat(5) { viewModel.nextStep() }
        assertEquals(SetupStep.COMPLETE, viewModel.currentStep.value)
        viewModel.nextStep()
        assertEquals(SetupStep.COMPLETE, viewModel.currentStep.value)
    }

    // --- saveProviderConfig ---

    @Test
    fun `saveProviderConfig saves key and advances step`() = runTest {
        advanceUntilIdle()
        val stepBefore = viewModel.currentStep.value

        viewModel.saveProviderConfig(ApiProvider.ANTHROPIC, apiKey = "sk-test")
        advanceUntilIdle()
        // Allow IO dispatcher coroutine to complete
        Thread.sleep(200)
        advanceUntilIdle()

        coVerify { preferencesManager.setApiKey(ApiProvider.ANTHROPIC, "sk-test") }
        assertTrue(viewModel.currentStep.value != stepBefore)
    }

    @Test
    fun `saveProviderConfig saves baseUrl when not blank`() = runTest {
        advanceUntilIdle()
        viewModel.saveProviderConfig(ApiProvider.OPENAI, apiKey = "k", baseUrl = "https://proxy.com")
        advanceUntilIdle()

        coVerify { preferencesManager.setBaseUrl(ApiProvider.OPENAI, "https://proxy.com") }
    }

    @Test
    fun `saveProviderConfig skips baseUrl when blank`() = runTest {
        advanceUntilIdle()
        viewModel.saveProviderConfig(ApiProvider.OPENAI, apiKey = "k", baseUrl = "")
        advanceUntilIdle()

        coVerify(exactly = 0) { preferencesManager.setBaseUrl(any(), any()) }
    }

    @Test
    fun `saveProviderConfig calls configWriter`() = runTest {
        advanceUntilIdle()
        viewModel.saveProviderConfig(ApiProvider.ANTHROPIC, apiKey = "k")
        advanceUntilIdle()
        Thread.sleep(100)

        verify(timeout = 1000) { configWriter.writeConfig() }
    }

    // --- finishSetup ---

    @Test
    fun `finishSetup sets setup completed`() = runTest {
        advanceUntilIdle()
        viewModel.finishSetup()
        advanceUntilIdle()

        coVerify { preferencesManager.setSetupCompleted(true) }
    }

    // --- startInstallation ---

    @Test
    fun `startInstallation calls rootfsInstaller`() = runTest {
        advanceUntilIdle()
        viewModel.startInstallation()
        advanceUntilIdle()

        coVerify { rootfsInstaller.install(any()) }
    }

    // --- DeviceCheck ---

    @Test
    fun `DeviceCheck allOk requires all checks passing`() {
        val check = SetupViewModel.DeviceCheck(
            totalRamMb = 8192, freeStorageMb = 4096, networkOk = true,
            ramOk = true, storageOk = true,
        )
        assertTrue(check.allOk)
    }

    @Test
    fun `DeviceCheck allOk fails if any check fails`() {
        val noRam = SetupViewModel.DeviceCheck(ramOk = false, storageOk = true, networkOk = true)
        assertFalse(noRam.allOk)

        val noStorage = SetupViewModel.DeviceCheck(ramOk = true, storageOk = false, networkOk = true)
        assertFalse(noStorage.allOk)

        val noNetwork = SetupViewModel.DeviceCheck(ramOk = true, storageOk = true, networkOk = false)
        assertFalse(noNetwork.allOk)
    }
}
