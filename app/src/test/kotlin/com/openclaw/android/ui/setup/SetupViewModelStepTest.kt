package com.openclaw.android.ui.setup

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Environment
import com.openclaw.android.data.ModelProviderEntry
import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.gateway.ConnectivityResult
import com.openclaw.android.gateway.ModelConnectivityChecker
import com.openclaw.android.proot.OpenClawConfigWriter
import com.openclaw.android.proot.RootfsInstaller
import com.openclaw.android.proot.RootfsState
import com.openclaw.android.ui.components.ModelConfigFormState
import com.openclaw.android.ui.components.toModelConfig
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
    private lateinit var connectivityChecker: ModelConnectivityChecker
    private lateinit var viewModel: SetupViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        rootfsInstaller = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        configWriter = mockk(relaxed = true)
        connectivityChecker = mockk(relaxed = true)

        every { rootfsInstaller.state } returns MutableStateFlow(RootfsState.NotInstalled)

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

        viewModel = SetupViewModel(
            context,
            rootfsInstaller,
            preferencesManager,
            configWriter,
            connectivityChecker,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Environment::class)
    }

    @Test
    fun `initial step is WELCOME`() = runTest {
        advanceUntilIdle()
        assertEquals(SetupStep.WELCOME, viewModel.currentStep.value)
    }

    @Test
    fun `nextStep from API_KEY goes to COMPLETE`() = runTest {
        advanceUntilIdle()
        repeat(4) { viewModel.nextStep() }
        assertEquals(SetupStep.COMPLETE, viewModel.currentStep.value)
    }

    @Test
    fun `saveModelConfig saves config and advances step`() = runTest {
        advanceUntilIdle()
        repeat(3) { viewModel.nextStep() }
        assertEquals(SetupStep.API_KEY, viewModel.currentStep.value)

        val form = ModelConfigFormState(
            primaryModel = "anthropic/claude-sonnet",
            providers = listOf(
                ModelProviderEntry(
                    providerId = "anthropic",
                    apiKey = "sk-test",
                    modelId = "claude-sonnet",
                ),
            ),
        )

        viewModel.saveModelConfig(form)
        advanceUntilIdle()
        Thread.sleep(200)
        advanceUntilIdle()

        coVerify { preferencesManager.setModelConfig(any()) }
        verify { configWriter.writeConfig(any()) }
        assertEquals(SetupStep.COMPLETE, viewModel.currentStep.value)
    }

    @Test
    fun `testConnection delegates to checker`() = runTest {
        coEvery { connectivityChecker.test(any()) } returns ConnectivityResult.Success(50)
        val form = ModelConfigFormState(
            primaryModel = "openai/gpt-4",
            providers = listOf(
                ModelProviderEntry(providerId = "openai", apiKey = "sk", modelId = "gpt-4"),
            ),
        )

        viewModel.testConnection(form)
        advanceUntilIdle()

        assertEquals("Connected (50ms)", viewModel.testResult.value)
    }

    @Test
    fun `finishSetup sets setup completed`() = runTest {
        advanceUntilIdle()
        viewModel.finishSetup()
        advanceUntilIdle()

        coVerify { preferencesManager.setSetupCompleted(true) }
    }

    @Test
    fun `DeviceCheck allOk requires all checks passing`() {
        val check = SetupViewModel.DeviceCheck(
            totalRamMb = 8192,
            freeStorageMb = 4096,
            networkOk = true,
            ramOk = true,
            storageOk = true,
        )
        assertTrue(check.allOk)
    }

    @Test
    fun `DeviceCheck allOk fails if any check fails`() {
        val noRam = SetupViewModel.DeviceCheck(ramOk = false, storageOk = true, networkOk = true)
        assertFalse(noRam.allOk)
    }
}
