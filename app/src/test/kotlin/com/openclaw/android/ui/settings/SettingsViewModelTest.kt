package com.openclaw.android.ui.settings

import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.data.PreferencesManager.ApiProvider
import com.openclaw.android.proot.OpenClawConfigWriter
import com.openclaw.android.service.ProcessManager
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var processManager: ProcessManager
    private lateinit var configWriter: OpenClawConfigWriter
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        preferencesManager = mockk(relaxed = true)
        processManager = mockk()
        configWriter = mockk(relaxed = true)

        every { processManager.processState } returns MutableStateFlow(ProcessManager.ProcessState.Stopped)
        every { preferencesManager.isBackgroundEnabled } returns flowOf(true)
        every { preferencesManager.allApiKeys } returns flowOf(
            ApiProvider.entries.associateWith { "" }
        )

        viewModel = SettingsViewModel(preferencesManager, processManager, configWriter)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial processState is Unknown`() = runTest {
        assertEquals("Unknown", viewModel.processState.value)
    }

    @Test
    fun `initial backgroundEnabled is true`() = runTest {
        advanceUntilIdle()
        assertEquals(true, viewModel.backgroundEnabled.value)
    }

    @Test
    fun `initial API keys are empty`() = runTest {
        advanceUntilIdle()
        val keys = viewModel.apiKeys.value
        ApiProvider.entries.forEach { provider ->
            assertEquals("", keys[provider] ?: "")
        }
    }

    @Test
    fun `setBackgroundEnabled delegates to PreferencesManager`() = runTest {
        viewModel.setBackgroundEnabled(false)
        advanceUntilIdle()

        coVerify { preferencesManager.setBackgroundEnabled(false) }
    }

    @Test
    fun `saveProviderConfig saves apiKey`() = runTest {
        viewModel.saveProviderConfig(ApiProvider.ANTHROPIC, apiKey = "sk-test")
        advanceUntilIdle()

        coVerify { preferencesManager.setApiKey(ApiProvider.ANTHROPIC, "sk-test") }
    }

    @Test
    fun `saveProviderConfig saves CN provider apiKey`() = runTest {
        viewModel.saveProviderConfig(ApiProvider.MINIMAX_CN, apiKey = "cn-key")
        advanceUntilIdle()

        coVerify { preferencesManager.setApiKey(ApiProvider.MINIMAX_CN, "cn-key") }
    }

    @Test
    fun `saveProviderConfig calls configWriter writeConfig`() = runTest {
        viewModel.saveProviderConfig(ApiProvider.ANTHROPIC, apiKey = "k")
        advanceUntilIdle()
        Thread.sleep(100)

        verify(timeout = 1000) { configWriter.writeConfig() }
    }

    @Test
    fun `processState maps to displayText`() = runTest {
        val stateFlow = MutableStateFlow<ProcessManager.ProcessState>(ProcessManager.ProcessState.Running)
        every { processManager.processState } returns stateFlow

        val vm = SettingsViewModel(preferencesManager, processManager, configWriter)

        val job = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            vm.processState.collect {}
        }
        advanceUntilIdle()

        assertEquals("Running", vm.processState.value)
        job.cancel()
    }
}
