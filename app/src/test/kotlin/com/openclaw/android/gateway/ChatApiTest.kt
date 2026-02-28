package com.openclaw.android.gateway

import com.openclaw.android.data.ChatMessage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
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

    @Before
    fun setUp() {
        gateway = mockk(relaxed = true)
        chatEventsFlow = MutableSharedFlow()
        every { gateway.chatEvents } returns chatEventsFlow
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
    fun `sendMessage returns messageId from response`() = runTest {
        val payload = buildJsonObject { put("messageId", "msg-123") }
        coEvery { gateway.request("chat.send", any()) } returns okResponse(payload)

        val result = chatApi.sendMessage("hello")
        assertEquals("msg-123", result)
    }

    @Test
    fun `sendMessage falls back to UUID when payload missing messageId`() = runTest {
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
    fun `sendMessage throws ChatApiException with fallback message`() = runTest {
        coEvery { gateway.request("chat.send", any()) } returns errorResponse(message = null)
        try {
            chatApi.sendMessage("hello")
        } catch (e: ChatApi.ChatApiException) {
            assertEquals("Failed to send message", e.message)
        }
    }

    @Test
    fun `sendMessage passes correct params to gateway`() = runTest {
        val paramsSlot = slot<JsonObject>()
        coEvery { gateway.request("chat.send", capture(paramsSlot)) } returns okResponse(
            buildJsonObject { put("messageId", "m1") }
        )

        chatApi.sendMessage("hello world")
        val params = paramsSlot.captured
        assertEquals("hello world", params["text"]?.toString()?.trim('"'))
        assertEquals("main", params["sessionKey"]?.toString()?.trim('"'))
    }

    @Test
    fun `sendMessage uses custom sessionKey`() = runTest {
        val paramsSlot = slot<JsonObject>()
        coEvery { gateway.request("chat.send", capture(paramsSlot)) } returns okResponse(
            buildJsonObject { put("messageId", "m1") }
        )

        chatApi.sendMessage("test", sessionKey = "custom-session")
        assertEquals("custom-session", paramsSlot.captured["sessionKey"]?.toString()?.trim('"'))
    }

    // --- getHistory ---

    @Test
    fun `getHistory returns parsed messages`() = runTest {
        val payload = buildJsonObject {
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("id", "m1")
                    put("role", "user")
                    put("content", "hello")
                    put("timestamp", "1700000000")
                })
            }
        }
        coEvery { gateway.request("chat.history", any()) } returns okResponse(payload)

        val messages = chatApi.getHistory()
        assertEquals(1, messages.size)
        assertEquals("m1", messages[0].id)
        assertEquals("hello", messages[0].content)
    }

    @Test
    fun `getHistory maps user role correctly`() = runTest {
        val payload = buildJsonObject {
            putJsonArray("messages") {
                add(buildJsonObject { put("id", "1"); put("role", "user"); put("content", "") })
            }
        }
        coEvery { gateway.request("chat.history", any()) } returns okResponse(payload)

        val messages = chatApi.getHistory()
        assertEquals(ChatMessage.Role.USER, messages[0].role)
    }

    @Test
    fun `getHistory maps assistant role correctly`() = runTest {
        val payload = buildJsonObject {
            putJsonArray("messages") {
                add(buildJsonObject { put("id", "1"); put("role", "assistant"); put("content", "") })
            }
        }
        coEvery { gateway.request("chat.history", any()) } returns okResponse(payload)

        val messages = chatApi.getHistory()
        assertEquals(ChatMessage.Role.ASSISTANT, messages[0].role)
    }

    @Test
    fun `getHistory maps unknown role to SYSTEM`() = runTest {
        val payload = buildJsonObject {
            putJsonArray("messages") {
                add(buildJsonObject { put("id", "1"); put("role", "tool"); put("content", "") })
            }
        }
        coEvery { gateway.request("chat.history", any()) } returns okResponse(payload)

        val messages = chatApi.getHistory()
        assertEquals(ChatMessage.Role.SYSTEM, messages[0].role)
    }

    @Test
    fun `getHistory returns empty list when no messages`() = runTest {
        val payload = buildJsonObject { }
        coEvery { gateway.request("chat.history", any()) } returns okResponse(payload)

        val messages = chatApi.getHistory()
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `getHistory returns empty list when payload null`() = runTest {
        coEvery { gateway.request("chat.history", any()) } returns okResponse(null)

        val messages = chatApi.getHistory()
        assertTrue(messages.isEmpty())
    }

    @Test(expected = ChatApi.ChatApiException::class)
    fun `getHistory throws ChatApiException on failure`() = runTest {
        coEvery { gateway.request("chat.history", any()) } returns errorResponse()
        chatApi.getHistory()
    }

    @Test
    fun `getHistory skips malformed messages`() = runTest {
        val payload = buildJsonObject {
            putJsonArray("messages") {
                add(buildJsonObject { put("id", "1"); put("role", "user"); put("content", "ok") })
                add(JsonPrimitive("not-an-object"))
            }
        }
        coEvery { gateway.request("chat.history", any()) } returns okResponse(payload)

        val messages = chatApi.getHistory()
        assertEquals(1, messages.size)
    }

    @Test
    fun `getHistory passes before param when provided`() = runTest {
        val paramsSlot = slot<JsonObject>()
        coEvery { gateway.request("chat.history", capture(paramsSlot)) } returns okResponse(
            buildJsonObject { putJsonArray("messages") {} }
        )

        chatApi.getHistory(before = "cursor-123")
        assertTrue(paramsSlot.captured.containsKey("before"))
    }

    @Test
    fun `getHistory omits before param when null`() = runTest {
        val paramsSlot = slot<JsonObject>()
        coEvery { gateway.request("chat.history", capture(paramsSlot)) } returns okResponse(
            buildJsonObject { putJsonArray("messages") {} }
        )

        chatApi.getHistory(before = null)
        assertTrue(!paramsSlot.captured.containsKey("before"))
    }

    // --- observeChatEvents ---

    @Test
    fun `observeChatEvents maps chunk correctly`() = runTest {
        val events = mutableListOf<ChatApi.ChatEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            chatApi.observeChatEvents().collect { events.add(it) }
        }

        chatEventsFlow.emit(ChatEventPayload(
            chunk = ChatChunkPayload(messageId = "m1", delta = "hello", done = false),
        ))

        assertEquals(1, events.size)
        val chunk = events[0] as ChatApi.ChatEvent.Chunk
        assertEquals("m1", chunk.messageId)
        assertEquals("hello", chunk.delta)
        assertEquals(false, chunk.done)

        job.cancel()
    }

    @Test
    fun `observeChatEvents maps message correctly`() = runTest {
        val events = mutableListOf<ChatApi.ChatEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            chatApi.observeChatEvents().collect { events.add(it) }
        }

        chatEventsFlow.emit(ChatEventPayload(
            message = ChatMessagePayload(id = "m2", role = "assistant", content = "world", timestamp = 123L),
        ))

        assertEquals(1, events.size)
        val msg = events[0] as ChatApi.ChatEvent.Message
        assertEquals("m2", msg.message.id)
        assertEquals(ChatMessage.Role.ASSISTANT, msg.message.role)
        assertEquals("world", msg.message.content)

        job.cancel()
    }

    @Test
    fun `observeChatEvents emits Unknown for empty payload`() = runTest {
        val events = mutableListOf<ChatApi.ChatEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            chatApi.observeChatEvents().collect { events.add(it) }
        }

        chatEventsFlow.emit(ChatEventPayload())

        assertEquals(1, events.size)
        assertEquals(ChatApi.ChatEvent.Unknown, events[0])

        job.cancel()
    }

    @Test
    fun `observeChatEvents defaults null fields to empty string`() = runTest {
        val events = mutableListOf<ChatApi.ChatEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            chatApi.observeChatEvents().collect { events.add(it) }
        }

        chatEventsFlow.emit(ChatEventPayload(
            chunk = ChatChunkPayload(messageId = null, delta = null, done = true),
        ))

        assertEquals(1, events.size)
        val chunk = events[0] as ChatApi.ChatEvent.Chunk
        assertEquals("", chunk.messageId)
        assertEquals("", chunk.delta)

        job.cancel()
    }
}
