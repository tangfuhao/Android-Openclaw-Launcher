package com.openclaw.android.ui.chat

import com.openclaw.android.data.ChatMessage
import com.openclaw.android.gateway.ApprovalRequestPayload
import com.openclaw.android.gateway.ChatEventPayload
import com.openclaw.android.gateway.ChatMessagePayload
import com.openclaw.android.gateway.GatewayClient
import com.openclaw.android.gateway.GatewayResponse
import com.openclaw.android.gateway.GatewayState
import com.openclaw.android.service.ProcessManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var gatewayClient: GatewayClient
    private lateinit var processManager: ProcessManager
    private lateinit var connectionStateFlow: MutableStateFlow<GatewayState>
    private lateinit var processStateFlow: MutableStateFlow<ProcessManager.ProcessState>
    private lateinit var chatEventsFlow: MutableSharedFlow<ChatEventPayload>
    private lateinit var approvalRequestsFlow: MutableSharedFlow<ApprovalRequestPayload>

    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        gatewayClient = mockk(relaxed = true)
        processManager = mockk(relaxed = true)
        connectionStateFlow = MutableStateFlow(GatewayState.Idle)
        processStateFlow = MutableStateFlow(ProcessManager.ProcessState.Stopped)
        chatEventsFlow = MutableSharedFlow()
        approvalRequestsFlow = MutableSharedFlow()

        every { gatewayClient.connectionState } returns connectionStateFlow
        every { gatewayClient.chatEvents } returns chatEventsFlow
        every { gatewayClient.approvalRequests } returns approvalRequestsFlow
        every { processManager.processState } returns processStateFlow

        viewModel = ChatViewModel(gatewayClient, processManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialStateHasEmptyMessages() = runTest {
        advanceUntilIdle()
        assertTrue(viewModel.messages.value.isEmpty())
    }

    @Test
    fun sendMessageAddsUserMessageAndAssistantPlaceholder() = runTest {
        coEvery { gatewayClient.request("chat.send", any()) } returns GatewayResponse(
            id = "1",
            ok = true,
            payload = buildJsonObject { put("runId", "r1") },
        )

        viewModel.sendMessage("hello world")
        advanceUntilIdle()

        assertEquals(2, viewModel.messages.value.size)
        assertEquals("hello world", viewModel.messages.value[0].textContent)
        assertEquals(ChatMessage.Role.USER, viewModel.messages.value[0].role)
        assertEquals(ChatMessage.Role.ASSISTANT, viewModel.messages.value[1].role)
        assertEquals("r1", viewModel.messages.value[1].runId)
    }

    @Test
    fun sendMessageIgnoresBlankText() = runTest {
        viewModel.sendMessage("   ")
        advanceUntilIdle()
        assertTrue(viewModel.messages.value.isEmpty())
    }

    @Test
    fun sendMessageMarksUserMessageErrorAndRemovesPlaceholderOnFailure() = runTest {
        coEvery { gatewayClient.request("chat.send", any()) } throws RuntimeException("network error")

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals(ChatMessage.Status.ERROR, viewModel.messages.value[0].status)
        assertEquals(ChatMessage.Role.USER, viewModel.messages.value[0].role)
    }

    @Test
    fun clearCommandOnlyClearsLocalMessages() = runTest {
        coEvery { gatewayClient.request("chat.send", any()) } returns GatewayResponse(
            id = "1",
            ok = true,
            payload = buildJsonObject { put("runId", "r1") },
        )

        viewModel.sendMessage("hello")
        advanceUntilIdle()
        assertEquals(2, viewModel.messages.value.size)

        viewModel.sendMessage("/clear")
        advanceUntilIdle()
        assertTrue(viewModel.messages.value.isEmpty())
    }

    @Test
    fun resetAndNewCommandsResetSessionAndClearMessages() = runTest {
        coEvery { gatewayClient.request("sessions.reset", any()) } returns GatewayResponse(id = "1", ok = true)
        coEvery { gatewayClient.request("chat.send", any()) } returns GatewayResponse(
            id = "2",
            ok = true,
            payload = buildJsonObject { put("runId", "r1") },
        )

        viewModel.sendMessage("hello")
        advanceUntilIdle()
        assertEquals(2, viewModel.messages.value.size)

        viewModel.sendMessage("/reset")
        advanceUntilIdle()
        assertTrue(viewModel.messages.value.isEmpty())
        assertNull(viewModel.activeRunId.value)

        viewModel.sendMessage("hello again")
        advanceUntilIdle()
        assertEquals(2, viewModel.messages.value.size)

        viewModel.sendMessage("/new")
        advanceUntilIdle()
        assertTrue(viewModel.messages.value.isEmpty())
        assertNull(viewModel.activeRunId.value)
    }

    @Test
    fun deltaEventCreatesNewAssistantMessage() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run1",
            sessionKey = "main",
            state = "delta",
            message = ChatMessagePayload(role = "assistant", content = JsonPrimitive("Hello")),
        ))
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals(ChatMessage.Role.ASSISTANT, viewModel.messages.value[0].role)
        assertEquals("Hello", viewModel.messages.value[0].textContent)
        assertTrue(viewModel.messages.value[0].isStreaming)
    }

    @Test
    fun emptyDeltaDoesNotCreatePlaceholderMessage() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run1",
            sessionKey = "main",
            state = "delta",
            message = ChatMessagePayload(
                role = "assistant",
                content = kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject { put("type", "image"); put("mimeType", "image/png") })
                },
            ),
        ))
        advanceUntilIdle()

        assertTrue(viewModel.messages.value.isEmpty())
    }

    @Test
    fun finalEventCompletesStreamingMessage() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run1",
            state = "delta",
            message = ChatMessagePayload(role = "assistant", content = JsonPrimitive("streaming...")),
        ))
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run1",
            state = "final",
            message = ChatMessagePayload(role = "assistant", content = JsonPrimitive("Final content")),
        ))
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals("Final content", viewModel.messages.value[0].textContent)
        assertEquals(false, viewModel.messages.value[0].isStreaming)
    }

    @Test
    fun abortedEventStopsStreamingOnExistingMessage() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run1",
            state = "delta",
            message = ChatMessagePayload(role = "assistant", content = JsonPrimitive("partial...")),
        ))
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(runId = "run1", state = "aborted"))
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals(false, viewModel.messages.value[0].isStreaming)
        assertEquals("partial...", viewModel.messages.value[0].textContent)
    }

    @Test
    fun errorEventAddsSystemMessageWhenNoExistingRunMessage() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run1",
            state = "error",
            errorMessage = "API key invalid",
        ))
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals(ChatMessage.Role.SYSTEM, viewModel.messages.value[0].role)
        assertTrue(viewModel.messages.value[0].textContent.contains("API key invalid"))
    }

    @Test
    fun approvalRequestSetsPendingApproval() = runTest {
        advanceUntilIdle()

        approvalRequestsFlow.emit(ApprovalRequestPayload(
            id = "a1",
            command = "bash",
            commandArgv = listOf("-c", "ls -la"),
        ))
        advanceUntilIdle()

        val approval = viewModel.pendingApproval.value
        assertNotNull(approval)
        assertEquals("a1", approval!!.requestId)
    }

    @Test
    fun resolveApprovalClearsPendingApprovalOnSuccess() = runTest {
        advanceUntilIdle()

        approvalRequestsFlow.emit(ApprovalRequestPayload(id = "a1", command = "bash"))
        advanceUntilIdle()
        assertNotNull(viewModel.pendingApproval.value)

        coEvery { gatewayClient.request("exec.approval.resolve", any()) } returns GatewayResponse(
            id = "1",
            ok = true,
        )

        viewModel.resolveApproval("a1", true)
        advanceUntilIdle()

        assertNull(viewModel.pendingApproval.value)
    }
}
