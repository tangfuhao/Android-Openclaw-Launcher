package com.openclaw.android.gateway

import com.openclaw.android.data.ChatMessage
import com.openclaw.android.data.ContentBlock
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatApiTest {

    private lateinit var gateway: GatewayClient
    private lateinit var chatApi: ChatApi
    private lateinit var chatEventsFlow: MutableSharedFlow<ChatEventPayload>
    private lateinit var agentEventsFlow: MutableSharedFlow<AgentEventPayload>

    @Before
    fun setUp() {
        gateway = mockk(relaxed = true)
        chatEventsFlow = MutableSharedFlow()
        agentEventsFlow = MutableSharedFlow()
        every { gateway.chatEvents } returns chatEventsFlow
        every { gateway.agentEvents } returns agentEventsFlow
        chatApi = ChatApi(gateway)
    }

    private fun okResponse(payload: JsonObject? = null) = GatewayResponse(
        id = "test", ok = true, payload = payload,
    )

    private fun errorResponse(code: String = "ERR", message: String? = "fail") = GatewayResponse(
        id = "test", ok = false, error = JsonError(code = code, message = message),
    )

    // --- sendMessage ---

    @Test
    fun `sendMessage returns runId from response`() = runTest {
        val payload = buildJsonObject { put("runId", "run-123") }
        coEvery { gateway.request("chat.send", any()) } returns okResponse(payload)

        val result = chatApi.sendMessage("hello")
        assertEquals("run-123", result)
    }

    @Test
    fun `sendMessage falls back to UUID when payload missing runId`() = runTest {
        coEvery { gateway.request("chat.send", any()) } returns okResponse(buildJsonObject { })

        val result = chatApi.sendMessage("hello")
        assertNotNull(result)
        assertTrue(result.isNotBlank())
    }

    @Test(expected = ChatApi.ChatApiException::class)
    fun `sendMessage throws ChatApiException on failure response`() = runTest {
        coEvery { gateway.request("chat.send", any()) } returns errorResponse(message = "quota exceeded")
        chatApi.sendMessage("hello")
    }

    @Test
    fun `sendMessage passes correct params to gateway`() = runTest {
        val paramsSlot = slot<JsonObject>()
        coEvery { gateway.request("chat.send", capture(paramsSlot)) } returns okResponse(
            buildJsonObject { put("runId", "r1") }
        )

        chatApi.sendMessage("hello world")
        val params = paramsSlot.captured
        assertEquals("hello world", params["message"]?.let { (it as JsonPrimitive).content })
        assertEquals("main", params["sessionKey"]?.let { (it as JsonPrimitive).content })
        assertNotNull(params["idempotencyKey"])
    }

    @Test
    fun `sendMessage includes attachments in params`() = runTest {
        val paramsSlot = slot<JsonObject>()
        coEvery { gateway.request("chat.send", capture(paramsSlot)) } returns okResponse(
            buildJsonObject { put("runId", "r1") }
        )

        chatApi.sendMessage(
            "analyze this",
            attachments = listOf(ChatAttachment(type = "image", mimeType = "image/png", content = "base64")),
        )
        val params = paramsSlot.captured
        assertNotNull(params["attachments"])
    }

    // --- getHistory ---

    @Test
    fun `getHistory returns parsed messages with string content`() = runTest {
        val payload = buildJsonObject {
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "hello")
                    put("timestamp", "1700000000")
                })
            }
        }
        coEvery { gateway.request("chat.history", any()) } returns okResponse(payload)

        val messages = chatApi.getHistory()
        assertEquals(1, messages.size)
        assertEquals("hello", messages[0].textContent)
        assertEquals(ChatMessage.Role.USER, messages[0].role)
    }

    @Test
    fun `getHistory parses array content blocks`() = runTest {
        val payload = buildJsonObject {
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "Hello ") })
                        add(buildJsonObject { put("type", "text"); put("text", "world") })
                    })
                    put("timestamp", "1700000000")
                })
            }
        }
        coEvery { gateway.request("chat.history", any()) } returns okResponse(payload)

        val messages = chatApi.getHistory()
        assertEquals(1, messages.size)
        assertEquals(2, messages[0].contentBlocks.size)
        assertEquals("Hello \nworld", messages[0].textContent)
    }

    @Test
    fun `getHistory merges toolCall and toolResult into toolActivities`() = runTest {
        val payload = buildJsonObject {
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "toolCall")
                            put("id", "tool1")
                            put("name", "bash")
                            put("arguments", buildJsonObject { put("command", "ls") })
                        })
                    })
                    put("timestamp", "1700000000")
                })
                add(buildJsonObject {
                    put("role", "toolResult")
                    put("toolCallId", "tool1")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "file.txt") })
                    })
                    put("timestamp", "1700000001")
                })
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "Done") })
                    })
                    put("timestamp", "1700000002")
                })
            }
        }
        coEvery { gateway.request("chat.history", any()) } returns okResponse(payload)

        val messages = chatApi.getHistory()
        assertEquals(1, messages.size)
        assertEquals(1, messages[0].toolActivities.size)
        assertEquals("bash", messages[0].toolActivities[0].toolName)
        assertEquals("file.txt", messages[0].toolActivities[0].output)
    }

    @Test
    fun `getHistory returns empty list when no messages`() = runTest {
        coEvery { gateway.request("chat.history", any()) } returns okResponse(buildJsonObject { })
        assertTrue(chatApi.getHistory().isEmpty())
    }

    @Test(expected = ChatApi.ChatApiException::class)
    fun `getHistory throws ChatApiException on failure`() = runTest {
        coEvery { gateway.request("chat.history", any()) } returns errorResponse()
        chatApi.getHistory()
    }

    // --- observeChatEvents ---

    @Test
    fun `observeChatEvents maps delta correctly`() = runTest {
        val events = mutableListOf<ChatApi.ChatEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            chatApi.observeChatEvents().collect { events.add(it) }
        }

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run1",
            sessionKey = "main",
            state = "delta",
            message = ChatMessagePayload(
                role = "assistant",
                content = JsonPrimitive("hello"),
            ),
        ))

        assertEquals(1, events.size)
        val delta = events[0] as ChatApi.ChatEvent.Delta
        assertEquals("run1", delta.runId)
        assertEquals(1, delta.contentBlocks.size)
        assertEquals("hello", (delta.contentBlocks[0] as ContentBlock.Text).text)

        job.cancel()
    }

    @Test
    fun `observeChatEvents maps final correctly`() = runTest {
        val events = mutableListOf<ChatApi.ChatEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            chatApi.observeChatEvents().collect { events.add(it) }
        }

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run2",
            sessionKey = "main",
            state = "final",
            message = ChatMessagePayload(
                role = "assistant",
                content = JsonPrimitive("done"),
            ),
        ))

        assertEquals(1, events.size)
        val final = events[0] as ChatApi.ChatEvent.Final
        assertEquals("run2", final.runId)
        assertNotNull(final.contentBlocks)
        assertEquals("done", (final.contentBlocks!![0] as ContentBlock.Text).text)

        job.cancel()
    }

    @Test
    fun `observeChatEvents maps aborted correctly`() = runTest {
        val events = mutableListOf<ChatApi.ChatEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            chatApi.observeChatEvents().collect { events.add(it) }
        }

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run-abort",
            sessionKey = "main",
            state = "aborted",
        ))

        assertEquals(1, events.size)
        val aborted = events[0] as ChatApi.ChatEvent.Aborted
        assertEquals("run-abort", aborted.runId)

        job.cancel()
    }

    @Test
    fun `observeChatEvents maps error correctly`() = runTest {
        val events = mutableListOf<ChatApi.ChatEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            chatApi.observeChatEvents().collect { events.add(it) }
        }

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run3",
            state = "error",
            errorMessage = "rate limited",
        ))

        assertEquals(1, events.size)
        val error = events[0] as ChatApi.ChatEvent.Error
        assertEquals("run3", error.runId)
        assertEquals("rate limited", error.errorMessage)

        job.cancel()
    }

    @Test
    fun `observeChatEvents emits Unknown for null state`() = runTest {
        val events = mutableListOf<ChatApi.ChatEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            chatApi.observeChatEvents().collect { events.add(it) }
        }

        chatEventsFlow.emit(ChatEventPayload())

        assertEquals(1, events.size)
        assertEquals(ChatApi.ChatEvent.Unknown, events[0])

        job.cancel()
    }

    // --- abortRun ---

    @Test
    fun `abortRun sends chat abort request`() = runTest {
        val paramsSlot = slot<JsonObject>()
        coEvery { gateway.request("chat.abort", capture(paramsSlot)) } returns okResponse()

        chatApi.abortRun(sessionKey = "main", runId = "run-1")
        val params = paramsSlot.captured
        assertEquals("main", params["sessionKey"]?.let { (it as JsonPrimitive).content })
        assertEquals("run-1", params["runId"]?.let { (it as JsonPrimitive).content })
    }

    // --- parseContentBlocks ---

    @Test
    fun `parseContentBlocks handles null`() {
        val blocks = chatApi.parseContentBlocks(null)
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `parseContentBlocks handles plain string`() {
        val blocks = chatApi.parseContentBlocks(JsonPrimitive("hello"))
        assertEquals(1, blocks.size)
        assertEquals("hello", (blocks[0] as ContentBlock.Text).text)
    }

    @Test
    fun `parseContentBlocks handles empty string`() {
        val blocks = chatApi.parseContentBlocks(JsonPrimitive(""))
        assertTrue(blocks.isEmpty())
    }
}
