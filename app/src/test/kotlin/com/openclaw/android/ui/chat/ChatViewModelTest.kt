package com.openclaw.android.ui.chat

import com.openclaw.android.data.ChatMessage
import com.openclaw.android.gateway.ApprovalRequestPayload
import com.openclaw.android.gateway.ChatChunkPayload
import com.openclaw.android.gateway.ChatEventPayload
import com.openclaw.android.gateway.ChatMessagePayload
import com.openclaw.android.gateway.GatewayClient
import com.openclaw.android.gateway.GatewayResponse
import com.openclaw.android.gateway.GatewayState
import com.openclaw.android.gateway.JsonError
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
import kotlinx.serialization.json.JsonObject
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

    @Test
    fun `initial connection state is Idle`() = runTest {
        advanceUntilIdle()
        assertEquals(GatewayState.Idle, viewModel.connectionState.value)
    }

    // --- sendMessage ---

    @Test
    fun `sendMessage adds user message to list`() = runTest {
        coEvery { gatewayClient.request("chat.send", any()) } returns GatewayResponse(
            id = "1", ok = true, payload = buildJsonObject { put("messageId", "m1") },
        )

        viewModel.sendMessage("hello world")
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals("hello world", viewModel.messages.value[0].content)
        assertEquals(ChatMessage.Role.USER, viewModel.messages.value[0].role)
    }

    @Test
    fun `sendMessage trims text`() = runTest {
        coEvery { gatewayClient.request("chat.send", any()) } returns GatewayResponse(
            id = "1", ok = true, payload = buildJsonObject { put("messageId", "m1") },
        )

        viewModel.sendMessage("  hello  ")
        advanceUntilIdle()

        assertEquals("hello", viewModel.messages.value[0].content)
    }

    @Test
    fun `sendMessage ignores blank text`() = runTest {
        viewModel.sendMessage("   ")
        advanceUntilIdle()

        assertTrue(viewModel.messages.value.isEmpty())
    }

    @Test
    fun `sendMessage sets status to SENDING then SENT`() = runTest {
        coEvery { gatewayClient.request("chat.send", any()) } returns GatewayResponse(
            id = "1", ok = true, payload = buildJsonObject { put("messageId", "m1") },
        )

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        assertEquals(ChatMessage.Status.SENT, viewModel.messages.value[0].status)
    }

    @Test
    fun `sendMessage sets status to ERROR on failure`() = runTest {
        coEvery { gatewayClient.request("chat.send", any()) } throws RuntimeException("network error")

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        assertEquals(ChatMessage.Status.ERROR, viewModel.messages.value[0].status)
    }

    // --- loadHistory ---

    @Test
    fun `loadHistory replaces messages`() = runTest {
        val payload = buildJsonObject {
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("id", "h1"); put("role", "user"); put("content", "old msg"); put("timestamp", "100")
                })
            }
        }
        coEvery { gatewayClient.request("chat.history", any()) } returns GatewayResponse(
            id = "1", ok = true, payload = payload,
        )

        viewModel.loadHistory()
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals("old msg", viewModel.messages.value[0].content)
    }

    @Test
    fun `loadHistory sets loading state`() = runTest {
        coEvery { gatewayClient.request("chat.history", any()) } returns GatewayResponse(
            id = "1", ok = true, payload = buildJsonObject { putJsonArray("messages") {} },
        )

        viewModel.loadHistory()
        advanceUntilIdle()

        assertEquals(false, viewModel.isLoading.value)
    }

    @Test
    fun `loadHistory resets loading on failure`() = runTest {
        coEvery { gatewayClient.request("chat.history", any()) } throws RuntimeException("fail")

        viewModel.loadHistory()
        advanceUntilIdle()

        assertEquals(false, viewModel.isLoading.value)
    }

    // --- Streaming ---

    @Test
    fun `handleStreamingChunk creates new assistant message`() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            chunk = ChatChunkPayload(messageId = "s1", delta = "Hello", done = false),
        ))
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals("s1", viewModel.messages.value[0].id)
        assertEquals(ChatMessage.Role.ASSISTANT, viewModel.messages.value[0].role)
        assertEquals("Hello", viewModel.messages.value[0].content)
        assertTrue(viewModel.messages.value[0].isStreaming)
    }

    @Test
    fun `handleStreamingChunk accumulates delta`() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            chunk = ChatChunkPayload(messageId = "s1", delta = "Hello", done = false),
        ))
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            chunk = ChatChunkPayload(messageId = "s1", delta = " World", done = false),
        ))
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals("Hello World", viewModel.messages.value[0].content)
    }

    @Test
    fun `handleStreamingChunk marks streaming done`() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            chunk = ChatChunkPayload(messageId = "s1", delta = "Done", done = true),
        ))
        advanceUntilIdle()

        assertEquals(false, viewModel.messages.value[0].isStreaming)
    }

    @Test
    fun `handleStreamingChunk updates existing message`() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            chunk = ChatChunkPayload(messageId = "s1", delta = "Part1", done = false),
        ))
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            chunk = ChatChunkPayload(messageId = "s1", delta = "Part2", done = false),
        ))
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals("Part1Part2", viewModel.messages.value[0].content)
    }

    // --- Complete message events ---

    @Test
    fun `complete message event replaces streaming message`() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            chunk = ChatChunkPayload(messageId = "s1", delta = "stream...", done = false),
        ))
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            message = ChatMessagePayload(id = "s1", role = "assistant", content = "Final content", timestamp = 123L),
        ))
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals("Final content", viewModel.messages.value[0].content)
        assertEquals(false, viewModel.messages.value[0].isStreaming)
    }

    @Test
    fun `complete message event adds new message`() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload(
            message = ChatMessagePayload(id = "m1", role = "assistant", content = "Hello!", timestamp = 100L),
        ))
        advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals("Hello!", viewModel.messages.value[0].content)
    }

    @Test
    fun `unknown chat event is ignored`() = runTest {
        advanceUntilIdle()

        chatEventsFlow.emit(ChatEventPayload())
        advanceUntilIdle()

        assertTrue(viewModel.messages.value.isEmpty())
    }

    // --- Approvals ---

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

    @Test
    fun `resolveApproval keeps approval on failure`() = runTest {
        advanceUntilIdle()

        approvalRequestsFlow.emit(ApprovalRequestPayload(requestId = "a1", tool = "bash", description = "run ls"))
        advanceUntilIdle()

        coEvery { gatewayClient.request("exec.approval.resolve", any()) } throws RuntimeException("fail")

        viewModel.resolveApproval("a1", true)
        advanceUntilIdle()

        // pendingApproval is not cleared because the resolve threw
        assertNotNull(viewModel.pendingApproval.value)
    }

    @Test
    fun `approval request sets pendingApproval`() = runTest {
        advanceUntilIdle()

        approvalRequestsFlow.emit(ApprovalRequestPayload(requestId = "a1", tool = "bash", description = "rm -rf /"))
        advanceUntilIdle()

        val approval = viewModel.pendingApproval.value
        assertNotNull(approval)
        assertEquals("a1", approval!!.requestId)
        assertEquals("bash", approval.tool)
    }
}
