package com.openclaw.android.ui.chat

import com.openclaw.android.data.ChatMessage
import com.openclaw.android.gateway.ApprovalRequestPayload
import com.openclaw.android.gateway.ChatEventPayload
import com.openclaw.android.gateway.ChatMessagePayload
import com.openclaw.android.gateway.GatewayClient
import com.openclaw.android.gateway.GatewayResponse
import com.openclaw.android.gateway.GatewayState
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
import kotlinx.serialization.json.putJsonArray
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
    private lateinit var connectionStateFlow: MutableStateFlow<GatewayState>
    private lateinit var chatEventsFlow: MutableSharedFlow<ChatEventPayload>
    private lateinit var approvalRequestsFlow: MutableSharedFlow<ApprovalRequestPayload>

    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        gatewayClient = mockk(relaxed = true)
        connectionStateFlow = MutableStateFlow(GatewayState.Idle)
        chatEventsFlow = MutableSharedFlow()
        approvalRequestsFlow = MutableSharedFlow()

        every { gatewayClient.connectionState } returns connectionStateFlow
        every { gatewayClient.chatEvents } returns chatEventsFlow
        every { gatewayClient.approvalRequests } returns approvalRequestsFlow

        viewModel = ChatViewModel(gatewayClient)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Initial state ---

    @Test
    fun `initial state has empty messages`() = runTest {
        advanceUntilIdle()
        assertTrue(viewModel.messages.value.isEmpty())
    }

    @Test
    fun `initial state is not loading`() = runTest {
        advanceUntilIdle()
        assertEquals(false, viewModel.isLoading.value)
    }

    @Test
    fun `initial state has no pending approval`() = runTest {
        advanceUntilIdle()
        assertNull(viewModel.pendingApproval.value)
    }

    // --- sendMessage ---

    @Test
    fun `sendMessage adds user message to list`() = runTest {
        coEvery { gatewayClient.request("chat.send", any()) } returns GatewayResponse(
            id = "1", ok = true, payload = buildJsonObject { put("runId", "r1") },
        )

        viewModel.sendMessage("hello world")
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals("hello world", viewModel.messages.value[0].content)
        assertEquals(ChatMessage.Role.USER, viewModel.messages.value[0].role)
    }

    @Test
    fun `sendMessage ignores blank text`() = runTest {
        viewModel.sendMessage("   ")
        advanceUntilIdle()
        assertTrue(viewModel.messages.value.isEmpty())
    }

    @Test
    fun `sendMessage sets status to ERROR on failure`() = runTest {
        coEvery { gatewayClient.request("chat.send", any()) } throws RuntimeException("network error")

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        assertEquals(ChatMessage.Status.ERROR, viewModel.messages.value[0].status)
    }

    // --- Streaming (delta events) ---

    @Test
    fun `delta event creates new assistant message`() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run1", sessionKey = "main", state = "delta",
            message = ChatMessagePayload(role = "assistant", content = JsonPrimitive("Hello")),
        ))
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals(ChatMessage.Role.ASSISTANT, viewModel.messages.value[0].role)
        assertEquals("Hello", viewModel.messages.value[0].content)
        assertTrue(viewModel.messages.value[0].isStreaming)
    }

    @Test
    fun `subsequent delta replaces content`() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run1", state = "delta",
            message = ChatMessagePayload(role = "assistant", content = JsonPrimitive("Hello")),
        ))
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run1", state = "delta",
            message = ChatMessagePayload(role = "assistant", content = JsonPrimitive("Hello World")),
        ))
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals("Hello World", viewModel.messages.value[0].content)
    }

    // --- Final events ---

    @Test
    fun `final event completes streaming message`() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run1", state = "delta",
            message = ChatMessagePayload(role = "assistant", content = JsonPrimitive("streaming...")),
        ))
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run1", state = "final",
            message = ChatMessagePayload(role = "assistant", content = JsonPrimitive("Final content")),
        ))
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals("Final content", viewModel.messages.value[0].content)
        assertEquals(false, viewModel.messages.value[0].isStreaming)
    }

    @Test
    fun `final event with no prior delta adds new message`() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run1", state = "final",
            message = ChatMessagePayload(role = "assistant", content = JsonPrimitive("Complete")),
        ))
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals("Complete", viewModel.messages.value[0].content)
    }

    // --- Error events ---

    @Test
    fun `error event adds system error message`() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run1", state = "error", errorMessage = "API key invalid",
        ))
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals(ChatMessage.Role.SYSTEM, viewModel.messages.value[0].role)
        assertTrue(viewModel.messages.value[0].content.contains("API key invalid"))
    }

    @Test
    fun `unknown event is ignored`() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload())
        advanceUntilIdle()

        assertTrue(viewModel.messages.value.isEmpty())
    }

    // --- Approvals ---

    @Test
    fun `approval request sets pendingApproval`() = runTest {
        advanceUntilIdle()

        approvalRequestsFlow.emit(ApprovalRequestPayload(requestId = "a1", tool = "bash", description = "rm -rf /"))
        advanceUntilIdle()

        val approval = viewModel.pendingApproval.value
        assertNotNull(approval)
        assertEquals("a1", approval!!.requestId)
    }

    @Test
    fun `resolveApproval clears pending approval on success`() = runTest {
        advanceUntilIdle()

        approvalRequestsFlow.emit(ApprovalRequestPayload(requestId = "a1", tool = "bash", description = "run ls"))
        advanceUntilIdle()
        assertNotNull(viewModel.pendingApproval.value)

        coEvery { gatewayClient.request("exec.approval.resolve", any()) } returns GatewayResponse(
            id = "1", ok = true,
        )

        viewModel.resolveApproval("a1", true)
        advanceUntilIdle()

        assertNull(viewModel.pendingApproval.value)
    }
}
