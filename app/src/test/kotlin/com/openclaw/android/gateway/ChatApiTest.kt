package com.openclaw.android.gateway

import com.openclaw.android.data.ChatMessage
import com.openclaw.android.data.ContentBlock
import com.openclaw.android.data.ToolPhase
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

    @Test
    fun `parseContentBlocks handles image type`() {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "image")
                put("mimeType", "image/png")
                put("omitted", true)
                put("bytes", 357080)
            })
        }
        val blocks = chatApi.parseContentBlocks(content)
        assertEquals(1, blocks.size)
        val img = blocks[0] as ContentBlock.Image
        assertEquals("image/png", img.mediaType)
        assertTrue(img.omitted)
        assertEquals(357080L, img.bytes)
        assertEquals(null, img.source)
    }

    @Test
    fun `parseContentBlocks handles image with data`() {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "image")
                put("mimeType", "image/jpeg")
                put("data", "base64data")
            })
        }
        val blocks = chatApi.parseContentBlocks(content)
        assertEquals(1, blocks.size)
        val img = blocks[0] as ContentBlock.Image
        assertEquals("image/jpeg", img.mediaType)
        assertEquals(false, img.omitted)
        assertEquals("base64data", img.source)
    }

    @Test
    fun `parseContentBlocks handles file type as MediaRef`() {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "file")
                put("mimeType", "application/pdf")
                put("fileName", "report.pdf")
                put("path", "/tmp/report.pdf")
                put("bytes", 12345)
            })
        }
        val blocks = chatApi.parseContentBlocks(content)
        assertEquals(1, blocks.size)
        val ref = blocks[0] as ContentBlock.MediaRef
        assertEquals("/tmp/report.pdf", ref.prootPath)
        assertEquals("application/pdf", ref.mimeType)
        assertEquals("report.pdf", ref.fileName)
        assertEquals(12345L, ref.size)
    }

    @Test
    fun `getHistory extracts media blocks from toolResult into ToolActivity`() = runTest {
        val payload = buildJsonObject {
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "toolCall")
                            put("id", "tc-img")
                            put("name", "Read")
                            put("arguments", buildJsonObject { put("file_path", "/tmp/screenshot.png") })
                        })
                    })
                    put("timestamp", "1700000000")
                })
                add(buildJsonObject {
                    put("role", "toolResult")
                    put("toolCallId", "tc-img")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "Read image file [image/png]") })
                        add(buildJsonObject {
                            put("type", "image")
                            put("mimeType", "image/png")
                            put("omitted", true)
                            put("bytes", 357080)
                        })
                    })
                    put("timestamp", "1700000001")
                })
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "Here is the screenshot.") })
                    })
                    put("timestamp", "1700000002")
                })
            }
        }
        coEvery { gateway.request("chat.history", any()) } returns okResponse(payload)

        val messages = chatApi.getHistory()
        assertEquals(1, messages.size)
        assertEquals(1, messages[0].toolActivities.size)

        val activity = messages[0].toolActivities[0]
        assertEquals("Read", activity.toolName)
        assertEquals("tc-img", activity.toolId)
        assertEquals(ToolPhase.COMPLETED, activity.phase)
        assertTrue(activity.output?.contains("Read image file") == true)

        // Media in ToolActivity
        assertEquals(1, activity.mediaBlocks.size)
        val img = activity.mediaBlocks[0] as ContentBlock.Image
        assertEquals("image/png", img.mediaType)
        assertTrue(img.omitted)
        assertEquals(357080L, img.bytes)
        assertEquals("/tmp/screenshot.png", img.prootPath)

        // Media also promoted to message-level contentBlocks for direct visibility
        val messageImages = messages[0].contentBlocks.filterIsInstance<ContentBlock.Image>()
        assertEquals(1, messageImages.size)
        assertEquals("/tmp/screenshot.png", messageImages[0].prootPath)
    }

    // --- stripTranscriptPrefix ---

    @Test
    fun `stripTranscriptPrefix removes system event and day-of-week timestamp`() {
        val raw = "System: [2026-03-02 10:32:52 UTC] Exec completed (gentle-b, code 1) :: " +
            "Gateway service check failed\n\n[Mon 2026-03-02 10:33 UTC] 用skills的方法"
        assertEquals("用skills的方法", chatApi.stripTranscriptPrefix(raw))
    }

    @Test
    fun `stripTranscriptPrefix removes ISO timestamp without day-of-week`() {
        val raw = "[2026-03-02 10:32:52 UTC] hello world"
        assertEquals("hello world", chatApi.stripTranscriptPrefix(raw))
    }

    @Test
    fun `stripTranscriptPrefix removes ISO 8601 compact timestamp`() {
        val raw = "[2026-03-02T10:32:52Z] hello"
        assertEquals("hello", chatApi.stripTranscriptPrefix(raw))
    }

    @Test
    fun `stripTranscriptPrefix uses last timestamp when multiple present`() {
        val raw = "[2026-03-02 10:00 UTC] system stuff\n\n[Mon 2026-03-02 10:05 UTC] actual msg"
        assertEquals("actual msg", chatApi.stripTranscriptPrefix(raw))
    }

    @Test
    fun `stripTranscriptPrefix returns original text when no timestamp`() {
        assertEquals("hello world", chatApi.stripTranscriptPrefix("hello world"))
    }

    @Test
    fun `stripTranscriptPrefix strips System lines as fallback`() {
        val raw = "System: something failed\n\nhello"
        assertEquals("hello", chatApi.stripTranscriptPrefix(raw))
    }

    @Test
    fun `stripTranscriptPrefix handles timestamp with seconds`() {
        val raw = "[Tue 2026-05-15 14:30:45 CST] 你好"
        assertEquals("你好", chatApi.stripTranscriptPrefix(raw))
    }

    @Test
    fun `stripTranscriptPrefix handles ctime-style timestamp`() {
        val raw = "[Mar  2 10:33:00 UTC 2026] test message"
        assertEquals("test message", chatApi.stripTranscriptPrefix(raw))
    }

    @Test
    fun `stripTranscriptPrefix preserves brackets in user text`() {
        val raw = "[Mon 2026-03-02 10:33 UTC] tell me about [React]"
        assertEquals("tell me about [React]", chatApi.stripTranscriptPrefix(raw))
    }
}
