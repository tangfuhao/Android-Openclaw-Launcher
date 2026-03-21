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

    @Before
    fun setUp() {
        gateway = mockk(relaxed = true)
        chatEventsFlow = MutableSharedFlow()
        every { gateway.chatEvents } returns chatEventsFlow
        chatApi = ChatApi(gateway)
    }

    private fun okResponse(payload: JsonObject? = null) = GatewayResponse(
        id = "test",
        ok = true,
        payload = payload,
    )

    private fun errorResponse(message: String = "fail") = GatewayResponse(
        id = "test",
        ok = false,
        error = JsonError(code = "ERR", message = message),
    )

    @Test
    fun sendMessageReturnsRunIdFromResponse() = runTest {
        val payload = buildJsonObject { put("runId", "run-123") }
        coEvery { gateway.request("chat.send", any()) } returns okResponse(payload)

        val result = chatApi.sendMessage("hello")
        assertEquals("run-123", result)
    }

    @Test
    fun sendMessagePassesTextOnlyParamsToGateway() = runTest {
        val paramsSlot = slot<JsonObject>()
        coEvery { gateway.request("chat.send", capture(paramsSlot)) } returns okResponse(
            buildJsonObject { put("runId", "r1") },
        )

        chatApi.sendMessage("hello world")
        val params = paramsSlot.captured

        assertEquals("hello world", params["message"]?.let { (it as JsonPrimitive).content })
        assertEquals("main", params["sessionKey"]?.let { (it as JsonPrimitive).content })
        assertNotNull(params["idempotencyKey"])
        assertTrue(params["attachments"] == null)
    }

    @Test(expected = ChatApi.ChatApiException::class)
    fun sendMessageThrowsOnGatewayFailure() = runTest {
        coEvery { gateway.request("chat.send", any()) } returns errorResponse("quota exceeded")
        chatApi.sendMessage("hello")
    }

    @Test
    fun getHistoryParsesUserAndAssistantTextMessages() = runTest {
        val payload = buildJsonObject {
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "hello")
                    put("timestamp", "1700000000")
                })
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "Hello ") })
                        add(buildJsonObject { put("type", "text"); put("text", "world") })
                    })
                    put("timestamp", "1700000001")
                })
            }
        }
        coEvery { gateway.request("chat.history", any()) } returns okResponse(payload)

        val messages = chatApi.getHistory()
        assertEquals(2, messages.size)
        assertEquals(ChatMessage.Role.USER, messages[0].role)
        assertEquals("hello", messages[0].textContent)
        assertEquals(ChatMessage.Role.ASSISTANT, messages[1].role)
        assertEquals("Hello \nworld", messages[1].textContent)
    }

    @Test
    fun getHistoryIgnoresNonTextPayloads() = runTest {
        val payload = buildJsonObject {
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "toolCall")
                            put("id", "tool1")
                            put("name", "bash")
                        })
                    })
                })
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "visible") })
                        add(buildJsonObject { put("type", "image"); put("mimeType", "image/png") })
                    })
                })
            }
        }
        coEvery { gateway.request("chat.history", any()) } returns okResponse(payload)

        val messages = chatApi.getHistory()
        assertEquals(1, messages.size)
        assertEquals("visible", messages[0].textContent)
    }

    @Test(expected = ChatApi.ChatApiException::class)
    fun getHistoryThrowsOnFailure() = runTest {
        coEvery { gateway.request("chat.history", any()) } returns errorResponse()
        chatApi.getHistory()
    }

    @Test
    fun observeChatEventsMapsDeltaText() = runTest {
        val events = mutableListOf<ChatApi.ChatEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            chatApi.observeChatEvents().collect { events.add(it) }
        }

        chatEventsFlow.emit(ChatEventPayload(
            runId = "run1",
            sessionKey = "main",
            state = "delta",
            message = ChatMessagePayload(role = "assistant", content = JsonPrimitive("hello")),
        ))

        assertEquals(1, events.size)
        val delta = events[0] as ChatApi.ChatEvent.Delta
        assertEquals("run1", delta.runId)
        assertEquals("hello", (delta.contentBlocks[0] as ContentBlock.Text).text)

        job.cancel()
    }

    @Test
    fun observeChatEventsMapsFinalWithEmptyTextBlocks() = runTest {
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
                content = buildJsonArray {
                    add(buildJsonObject { put("type", "image"); put("mimeType", "image/png") })
                },
            ),
        ))

        assertEquals(1, events.size)
        val final = events[0] as ChatApi.ChatEvent.Final
        assertTrue(final.contentBlocks.isEmpty())

        job.cancel()
    }

    @Test
    fun abortRunPassesParamsToGateway() = runTest {
        val paramsSlot = slot<JsonObject>()
        coEvery { gateway.request("chat.abort", capture(paramsSlot)) } returns okResponse()

        chatApi.abortRun(sessionKey = "main", runId = "run-1")
        val params = paramsSlot.captured
        assertEquals("main", params["sessionKey"]?.let { (it as JsonPrimitive).content })
        assertEquals("run-1", params["runId"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun parseContentBlocksHandlesNull() {
        assertTrue(chatApi.parseContentBlocks(null).isEmpty())
    }

    @Test
    fun parseContentBlocksHandlesPlainString() {
        val blocks = chatApi.parseContentBlocks(JsonPrimitive("hello"))
        assertEquals(1, blocks.size)
        assertEquals("hello", blocks[0].text)
    }

    @Test
    fun parseContentBlocksKeepsOnlyTextEntries() {
        val content = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "first") })
            add(buildJsonObject { put("type", "image"); put("mimeType", "image/png") })
            add(buildJsonObject { put("type", "text"); put("text", "second") })
        }

        val blocks = chatApi.parseContentBlocks(content)
        assertEquals(listOf("first", "second"), blocks.map { it.text })
    }

    @Test
    fun stripTranscriptPrefixRemovesPrependedSystemAndTimestamp() {
        val raw = "System: [2026-03-02 10:32:52 UTC] Exec completed\n\n[Mon 2026-03-02 10:33 UTC] actual msg"
        assertEquals("actual msg", chatApi.stripTranscriptPrefix(raw))
    }

    @Test
    fun stripTranscriptPrefixFallsBackToOriginalText() {
        assertEquals("hello world", chatApi.stripTranscriptPrefix("hello world"))
    }
}
