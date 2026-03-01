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
    fun `ApprovalRequestPayload deserializes`() {
        val raw = """{"requestId":"r1","tool":"bash","description":"Run ls -la","params":{"command":"ls -la"}}"""
        val approval = json.decodeFromString(ApprovalRequestPayload.serializer(), raw)
        assertEquals("r1", approval.requestId)
        assertEquals("bash", approval.tool)
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
        assertEquals("cli", info.mode)
    }

    @Test
    fun `GatewayRequest default params is empty object`() {
        val req = GatewayRequest(id = "x", method = "test")
        assertEquals(JsonObject(emptyMap()), req.params)
    }

    @Test
    fun `ApprovalResolveParams round-trip`() {
        val original = ApprovalResolveParams(requestId = "r1", approved = true, reason = "looks safe")
        val text = json.encodeToString(ApprovalResolveParams.serializer(), original)
        val decoded = json.decodeFromString(ApprovalResolveParams.serializer(), text)
        assertEquals(original, decoded)
    }
}
