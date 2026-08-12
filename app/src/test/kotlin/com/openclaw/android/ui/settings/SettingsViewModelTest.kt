package com.openclaw.android.ui.settings

import com.openclaw.android.data.ModelConfig
import com.openclaw.android.data.ModelProviderEntry
import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.gateway.ConnectivityResult
import com.openclaw.android.gateway.ModelConnectivityChecker
import com.openclaw.android.proot.OpenClawConfigWriter
import com.openclaw.android.service.ProcessManager
import com.openclaw.android.ui.components.ModelConfigFormState
import com.openclaw.android.ui.components.toModelConfig
import io.mockk.coEvery
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var processManager: ProcessManager
    private lateinit var configWriter: OpenClawConfigWriter
    private lateinit var connectivityChecker: ModelConnectivityChecker
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        preferencesManager = mockk(relaxed = true)
        processManager = mockk()
        configWriter = mockk(relaxed = true)
        connectivityChecker = mockk()

        every { processManager.processState } returns MutableStateFlow(ProcessManager.ProcessState.Stopped)
        every { preferencesManager.isBackgroundEnabled } returns flowOf(true)
        every { preferencesManager.modelConfig } returns flowOf(ModelConfig.Empty)

        viewModel = SettingsViewModel(
            preferencesManager,
            processManager,
            configWriter,
            connectivityChecker,
        )
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
    fun `saveModelConfig validates and persists`() = runTest {
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

        coVerify { preferencesManager.setModelConfig(form.toModelConfig()) }
        verify { configWriter.writeConfig(form.toModelConfig()) }
    }

    @Test
    fun `testConnection reports success`() = runTest {
        coEvery { connectivityChecker.test(any()) } returns ConnectivityResult.Success(120)

        val form = ModelConfigFormState(
            primaryModel = "openai/gpt-4",
            providers = listOf(
                ModelProviderEntry(providerId = "openai", apiKey = "sk", modelId = "gpt-4"),
            ),
        )

        viewModel.testConnection(form)
        advanceUntilIdle()

        assertEquals(
            "Connected (120ms). API reachable; Agent tool support may vary.",
            viewModel.testResult.value,
        )
    }

    @Test
    fun `testConnection reports failure`() = runTest {
        coEvery { connectivityChecker.test(any()) } returns ConnectivityResult.Failure("HTTP 401")

        val form = ModelConfigFormState(
            primaryModel = "openai/gpt-4",
            providers = listOf(
                ModelProviderEntry(providerId = "openai", apiKey = "bad", modelId = "gpt-4"),
            ),
        )

        viewModel.testConnection(form)
        advanceUntilIdle()

        assertEquals("HTTP 401", viewModel.testResult.value)
    }

    @Test
    fun `processState maps to displayText`() = runTest {
        val stateFlow = MutableStateFlow<ProcessManager.ProcessState>(ProcessManager.ProcessState.Running)
        every { processManager.processState } returns stateFlow

        val vm = SettingsViewModel(preferencesManager, processManager, configWriter, connectivityChecker)

        val job = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            vm.processState.collect {}
        }
        advanceUntilIdle()

        assertEquals("Running", vm.processState.value)
        job.cancel()
    }
}
