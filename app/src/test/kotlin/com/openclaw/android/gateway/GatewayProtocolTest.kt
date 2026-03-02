package com.openclaw.android.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayProtocolTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; explicitNulls = false }

    @Test
    fun `GatewayRequest serializes correctly`() {
        val req = GatewayRequest(id = "test-1", method = "chat.send", params = buildJsonObject {
            put("message", "hello")
        })
        val text = json.encodeToString(GatewayRequest.serializer(), req)
        assertTrue(text.contains("\"type\":\"req\""))
        assertTrue(text.contains("\"id\":\"test-1\""))
        assertTrue(text.contains("\"method\":\"chat.send\""))
    }

    @Test
    fun `GatewayFrame parses response frame`() {
        val raw = """{"type":"res","id":"abc","ok":true,"payload":{"protocol":3}}"""
        val frame = json.decodeFromString(GatewayFrame.serializer(), raw)
        assertEquals("res", frame.type)
        assertEquals("abc", frame.id)
        assertEquals(true, frame.ok)
        assertNotNull(frame.payload)
    }

    @Test
    fun `GatewayFrame parses event frame with object stateVersion`() {
        val raw = """{"type":"event","event":"health","payload":{},"seq":1,"stateVersion":{"presence":1,"health":3}}"""
        val frame = json.decodeFromString(GatewayFrame.serializer(), raw)
        assertEquals("event", frame.type)
        assertEquals("health", frame.event)
        assertEquals(1L, frame.seq)
        assertNotNull(frame.stateVersion)
    }

    @Test
    fun `GatewayFrame parses chat event`() {
        val raw = """{"type":"event","event":"chat","payload":{"runId":"r1","sessionKey":"main","seq":2,"state":"delta","message":{"role":"assistant","content":[{"type":"text","text":"hello"}]}},"seq":5}"""
        val frame = json.decodeFromString(GatewayFrame.serializer(), raw)
        assertEquals("event", frame.type)
        assertEquals("chat", frame.event)
    }

    @Test
    fun `GatewayFrame ignores unknown fields`() {
        val raw = """{"type":"res","id":"x","ok":false,"unknownField":"value"}"""
        val frame = json.decodeFromString(GatewayFrame.serializer(), raw)
        assertEquals("x", frame.id)
        assertEquals(false, frame.ok)
    }

    @Test
    fun `ConnectChallenge deserializes`() {
        val raw = """{"nonce":"abc123","ts":1700000000}"""
        val challenge = json.decodeFromString(ConnectChallenge.serializer(), raw)
        assertEquals("abc123", challenge.nonce)
        assertEquals(1700000000L, challenge.ts)
    }

    @Test
    fun `ConnectParams serializes with defaults`() {
        val params = ConnectParams(client = ClientInfo())
        val text = json.encodeToString(ConnectParams.serializer(), params)
        assertTrue(text.contains("\"role\":\"operator\""))
        assertTrue(text.contains("\"minProtocol\":3"))
    }

    @Test
    fun `ConnectParams device null is omitted from JSON`() {
        val params = ConnectParams(client = ClientInfo(), device = null)
        val text = json.encodeToString(ConnectParams.serializer(), params)
        assertTrue(!text.contains("\"device\""))
    }

    @Test
    fun `ChatEventPayload with delta state`() {
        val raw = """{"runId":"run1","sessionKey":"main","seq":1,"state":"delta","message":{"role":"assistant","content":[{"type":"text","text":"hello"}],"timestamp":1700000000}}"""
        val payload = json.decodeFromString(ChatEventPayload.serializer(), raw)
        assertEquals("run1", payload.runId)
        assertEquals("delta", payload.state)
        assertNotNull(payload.message)
        assertEquals("assistant", payload.message?.role)
    }

    @Test
    fun `ChatEventPayload with final state`() {
        val raw = """{"runId":"run1","sessionKey":"main","seq":2,"state":"final","message":{"role":"assistant","content":[{"type":"text","text":"done"}]}}"""
        val payload = json.decodeFromString(ChatEventPayload.serializer(), raw)
        assertEquals("final", payload.state)
        assertNotNull(payload.message)
    }

    @Test
    fun `ChatEventPayload with error state`() {
        val raw = """{"runId":"run1","sessionKey":"main","seq":3,"state":"error","errorMessage":"rate limited"}"""
        val payload = json.decodeFromString(ChatEventPayload.serializer(), raw)
        assertEquals("error", payload.state)
        assertEquals("rate limited", payload.errorMessage)
        assertNull(payload.message)
    }

    @Test
    fun `ChatMessagePayload with string content`() {
        val raw = """{"role":"user","content":"hello","timestamp":1700000000}"""
        val msg = json.decodeFromString(ChatMessagePayload.serializer(), raw)
        assertEquals("user", msg.role)
        assertEquals(JsonPrimitive("hello"), msg.content)
    }

    @Test
    fun `ChatMessagePayload with array content`() {
        val raw = """{"role":"assistant","content":[{"type":"text","text":"hi"}],"timestamp":1700000000}"""
        val msg = json.decodeFromString(ChatMessagePayload.serializer(), raw)
        assertEquals("assistant", msg.role)
        assertNotNull(msg.content)
    }

    @Test
    fun `ApprovalRequestPayload deserializes official schema`() {
        val raw = """{"id":"r1","command":"bash","commandArgv":["-c","ls -la"],"cwd":"/root"}"""
        val approval = json.decodeFromString(ApprovalRequestPayload.serializer(), raw)
        assertEquals("r1", approval.id)
        assertEquals("bash", approval.command)
        assertEquals(listOf("-c", "ls -la"), approval.commandArgv)
        assertEquals("/root", approval.cwd)
    }

    @Test
    fun `HelloOk deserializes with defaults`() {
        val raw = """{"type":"hello-ok","protocol":3}"""
        val helloOk = json.decodeFromString(HelloOk.serializer(), raw)
        assertEquals(3, helloOk.protocol)
        assertNull(helloOk.policy)
    }

    @Test
    fun `HelloOk with full payload`() {
        val raw = """{"type":"hello-ok","protocol":3,"server":{"version":"1.0","connId":"abc"},"features":{"methods":["chat.send"],"events":["chat"]},"snapshot":{},"policy":{"maxPayload":1000000,"maxBufferedBytes":5000000,"tickIntervalMs":15000}}"""
        val helloOk = json.decodeFromString(HelloOk.serializer(), raw)
        assertEquals(3, helloOk.protocol)
        assertNotNull(helloOk.server)
        assertNotNull(helloOk.features)
        assertNotNull(helloOk.policy)
        assertEquals(15000L, helloOk.policy?.tickIntervalMs)
    }

    @Test
    fun `ChatSendParams round-trip`() {
        val original = ChatSendParams(message = "hello world", sessionKey = "custom", idempotencyKey = "key-1")
        val text = json.encodeToString(ChatSendParams.serializer(), original)
        val decoded = json.decodeFromString(ChatSendParams.serializer(), text)
        assertEquals(original, decoded)
    }

    @Test
    fun `ConnectParams default scopes include operator admin`() {
        val params = ConnectParams(client = ClientInfo())
        assertTrue(params.scopes.contains("operator.admin"))
        assertTrue(params.scopes.contains("operator.read"))
        assertTrue(params.scopes.contains("operator.write"))
    }

    @Test
    fun `ClientInfo defaults to openclaw-android`() {
        val info = ClientInfo()
        assertEquals("openclaw-android", info.id)
        assertEquals("ui", info.mode)
    }

    @Test
    fun `GatewayRequest default params is empty object`() {
        val req = GatewayRequest(id = "x", method = "test")
        assertEquals(JsonObject(emptyMap()), req.params)
    }

    @Test
    fun `ApprovalResolveParams round-trip`() {
        val original = ApprovalResolveParams(id = "r1", decision = "allow")
        val text = json.encodeToString(ApprovalResolveParams.serializer(), original)
        val decoded = json.decodeFromString(ApprovalResolveParams.serializer(), text)
        assertEquals(original, decoded)
        assertTrue(text.contains("\"decision\":\"allow\""))
    }

    @Test
    fun `ConnectParams default caps includes tool-events`() {
        val params = ConnectParams(client = ClientInfo())
        assertTrue(params.caps.contains("tool-events"))
    }

    @Test
    fun `ChatSendParams with attachments round-trip`() {
        val original = ChatSendParams(
            message = "analyze this",
            idempotencyKey = "k-1",
            attachments = listOf(ChatAttachment(type = "image", mimeType = "image/png", fileName = "test.png", content = "base64data")),
            thinking = "high",
        )
        val text = json.encodeToString(ChatSendParams.serializer(), original)
        val decoded = json.decodeFromString(ChatSendParams.serializer(), text)
        assertEquals(original, decoded)
        assertTrue(text.contains("\"attachments\""))
        assertTrue(text.contains("\"thinking\":\"high\""))
    }

    @Test
    fun `ChatSendParams without optional fields omits them`() {
        val params = ChatSendParams(message = "hello", idempotencyKey = "k-2")
        val text = json.encodeToString(ChatSendParams.serializer(), params)
        assertTrue(!text.contains("\"attachments\""))
        assertTrue(!text.contains("\"thinking\""))
        assertTrue(!text.contains("\"deliver\""))
    }

    @Test
    fun `ChatAbortParams round-trip`() {
        val original = ChatAbortParams(sessionKey = "main", runId = "run-1")
        val text = json.encodeToString(ChatAbortParams.serializer(), original)
        val decoded = json.decodeFromString(ChatAbortParams.serializer(), text)
        assertEquals(original, decoded)
    }

    @Test
    fun `ChatEventPayload with aborted state`() {
        val raw = """{"runId":"run1","sessionKey":"main","seq":4,"state":"aborted"}"""
        val payload = json.decodeFromString(ChatEventPayload.serializer(), raw)
        assertEquals("aborted", payload.state)
        assertNull(payload.message)
    }

    @Test
    fun `ChatEventPayload with usage and stopReason`() {
        val raw = """{"runId":"run1","sessionKey":"main","seq":5,"state":"final","usage":{"inputTokens":100,"outputTokens":50},"stopReason":"end_turn"}"""
        val payload = json.decodeFromString(ChatEventPayload.serializer(), raw)
        assertEquals("final", payload.state)
        assertNotNull(payload.usage)
        assertEquals("end_turn", payload.stopReason)
    }

    @Test
    fun `AgentEventPayload deserializes tool stream`() {
        val raw = """{"runId":"run1","sessionKey":"main","stream":"tool","seq":3,"ts":1700000000,"data":{"toolName":"bash","phase":"start"}}"""
        val evt = json.decodeFromString(AgentEventPayload.serializer(), raw)
        assertEquals("run1", evt.runId)
        assertEquals("tool", evt.stream)
        assertEquals(3, evt.seq)
        assertNotNull(evt.data)
    }

    @Test
    fun `AgentEventPayload deserializes lifecycle stream`() {
        val raw = """{"runId":"run1","stream":"lifecycle","seq":1,"data":{"phase":"end"}}"""
        val evt = json.decodeFromString(AgentEventPayload.serializer(), raw)
        assertEquals("lifecycle", evt.stream)
    }

    @Test
    fun `HelloOk features deserializes methods and events`() {
        val raw = """{"type":"hello-ok","protocol":3,"server":{"version":"1.0","connId":"c1"},"features":{"methods":["chat.send","chat.abort","chat.history"],"events":["chat","agent","exec.approval.requested"]},"snapshot":{},"policy":{"maxPayload":1000000,"maxBufferedBytes":5000000,"tickIntervalMs":15000}}"""
        val helloOk = json.decodeFromString(HelloOk.serializer(), raw)
        assertNotNull(helloOk.features)
        assertTrue(helloOk.features!!.methods.contains("chat.abort"))
        assertTrue(helloOk.features!!.events.contains("agent"))
    }

    @Test
    fun `ClientInfo with device info serializes`() {
        val info = ClientInfo(deviceFamily = "Google", modelIdentifier = "Pixel 9")
        val text = json.encodeToString(ClientInfo.serializer(), info)
        assertTrue(text.contains("\"deviceFamily\":\"Google\""))
        assertTrue(text.contains("\"modelIdentifier\":\"Pixel 9\""))
    }

    @Test
    fun `SessionsPatchParams round-trip`() {
        val original = SessionsPatchParams(sessionKey = "main", model = "claude-opus-4-6", thinkingLevel = "high")
        val text = json.encodeToString(SessionsPatchParams.serializer(), original)
        val decoded = json.decodeFromString(SessionsPatchParams.serializer(), text)
        assertEquals(original, decoded)
    }

    // ── @SerialName wire field alignment tests ────────────────────

    @Test
    fun `SessionsResetParams serializes key not sessionKey`() {
        val params = SessionsResetParams(sessionKey = "test-session")
        val text = json.encodeToString(SessionsResetParams.serializer(), params)
        assertTrue("Wire field must be 'key'", text.contains("\"key\":\"test-session\""))
        assertTrue("Must not contain sessionKey", !text.contains("\"sessionKey\""))
    }

    @Test
    fun `SessionsDeleteParams serializes key not sessionKey`() {
        val params = SessionsDeleteParams(sessionKey = "to-delete")
        val text = json.encodeToString(SessionsDeleteParams.serializer(), params)
        assertTrue(text.contains("\"key\":\"to-delete\""))
        assertTrue(!text.contains("\"sessionKey\""))
    }

    @Test
    fun `SessionsCompactParams serializes key not sessionKey`() {
        val params = SessionsCompactParams(sessionKey = "main")
        val text = json.encodeToString(SessionsCompactParams.serializer(), params)
        assertTrue(text.contains("\"key\":\"main\""))
        assertTrue(!text.contains("\"sessionKey\""))
    }

    @Test
    fun `SessionsPatchParams serializes key not sessionKey`() {
        val params = SessionsPatchParams(sessionKey = "main", model = "test-model")
        val text = json.encodeToString(SessionsPatchParams.serializer(), params)
        assertTrue(text.contains("\"key\":\"main\""))
        assertTrue(!text.contains("\"sessionKey\""))
    }

    @Test
    fun `SessionsUsageParams serializes key not sessionKey`() {
        val params = SessionsUsageParams(sessionKey = "main")
        val text = json.encodeToString(SessionsUsageParams.serializer(), params)
        assertTrue(text.contains("\"key\":\"main\""))
        assertTrue(!text.contains("\"sessionKey\""))
    }

    @Test
    fun `ApprovalResolveParams uses id and decision wire fields`() {
        val params = ApprovalResolveParams(id = "apr-1", decision = "deny")
        val text = json.encodeToString(ApprovalResolveParams.serializer(), params)
        assertTrue(text.contains("\"id\":\"apr-1\""))
        assertTrue(text.contains("\"decision\":\"deny\""))
    }

    // ── New types tests ──────────────────────────────────────────

    @Test
    fun `TickEventPayload deserializes`() {
        val raw = """{"ts":1700000000}"""
        val tick = json.decodeFromString(TickEventPayload.serializer(), raw)
        assertEquals(1700000000L, tick.ts)
    }

    @Test
    fun `ShutdownEventPayload deserializes`() {
        val raw = """{"reason":"restart","restartExpectedMs":5000}"""
        val shutdown = json.decodeFromString(ShutdownEventPayload.serializer(), raw)
        assertEquals("restart", shutdown.reason)
        assertEquals(5000L, shutdown.restartExpectedMs)
    }

    @Test
    fun `ServerInfo in HelloOk parses correctly`() {
        val raw = """{"type":"hello-ok","protocol":3,"server":{"version":"1.2.3","connId":"conn-abc"}}"""
        val helloOk = json.decodeFromString(HelloOk.serializer(), raw)
        assertNotNull(helloOk.server)
        assertEquals("1.2.3", helloOk.server?.version)
        assertEquals("conn-abc", helloOk.server?.connId)
    }

    @Test
    fun `ChatInjectParams round-trip`() {
        val original = ChatInjectParams(sessionKey = "main", message = "system context", label = "setup")
        val text = json.encodeToString(ChatInjectParams.serializer(), original)
        val decoded = json.decodeFromString(ChatInjectParams.serializer(), text)
        assertEquals(original, decoded)
    }

    @Test
    fun `SessionsResetParams with reason round-trip`() {
        val original = SessionsResetParams(sessionKey = "main", reason = "new")
        val text = json.encodeToString(SessionsResetParams.serializer(), original)
        assertTrue(text.contains("\"reason\":\"new\""))
        val decoded = json.decodeFromString(SessionsResetParams.serializer(), text)
        assertEquals(original, decoded)
    }
}
