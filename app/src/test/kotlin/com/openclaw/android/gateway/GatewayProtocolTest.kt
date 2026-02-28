package com.openclaw.android.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayProtocolTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Test
    fun `GatewayRequest serializes correctly`() {
        val req = GatewayRequest(id = "test-1", method = "chat.send", params = buildJsonObject {
            put("text", "hello")
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
    fun `GatewayFrame parses event frame`() {
        val raw = """{"type":"event","event":"chat","payload":{"chunk":{"messageId":"m1","delta":"hi","done":false}},"seq":5}"""
        val frame = json.decodeFromString(GatewayFrame.serializer(), raw)
        assertEquals("event", frame.type)
        assertEquals("chat", frame.event)
        assertEquals(5L, frame.seq)
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
    fun `ChatEventPayload with chunk`() {
        val raw = """{"sessionKey":"main","chunk":{"messageId":"m1","delta":"hello ","done":false}}"""
        val payload = json.decodeFromString(ChatEventPayload.serializer(), raw)
        assertNull(payload.message)
        assertNotNull(payload.chunk)
        assertEquals("m1", payload.chunk?.messageId)
        assertEquals("hello ", payload.chunk?.delta)
        assertEquals(false, payload.chunk?.done)
    }

    @Test
    fun `ChatEventPayload with complete message`() {
        val raw = """{"sessionKey":"main","message":{"id":"m2","role":"assistant","content":"world","timestamp":1700000000}}"""
        val payload = json.decodeFromString(ChatEventPayload.serializer(), raw)
        assertNotNull(payload.message)
        assertNull(payload.chunk)
        assertEquals("m2", payload.message?.id)
        assertEquals("assistant", payload.message?.role)
    }

    @Test
    fun `ApprovalRequestPayload deserializes`() {
        val raw = """{"requestId":"r1","tool":"bash","description":"Run ls -la","params":{"command":"ls -la"}}"""
        val approval = json.decodeFromString(ApprovalRequestPayload.serializer(), raw)
        assertEquals("r1", approval.requestId)
        assertEquals("bash", approval.tool)
        assertEquals("Run ls -la", approval.description)
    }

    @Test
    fun `HelloOk deserializes with defaults`() {
        val raw = """{"type":"hello-ok","protocol":3}"""
        val helloOk = json.decodeFromString(HelloOk.serializer(), raw)
        assertEquals(3, helloOk.protocol)
        assertNull(helloOk.policy)
    }

    @Test
    fun `HelloOk with policy`() {
        val raw = """{"type":"hello-ok","protocol":3,"policy":{"tickIntervalMs":30000}}"""
        val helloOk = json.decodeFromString(HelloOk.serializer(), raw)
        assertEquals(30000L, helloOk.policy?.tickIntervalMs)
    }

    @Test
    fun `JsonError deserializes`() {
        val raw = """{"code":"AUTH_FAILED","message":"Invalid token"}"""
        val error = json.decodeFromString(JsonError.serializer(), raw)
        assertEquals("AUTH_FAILED", error.code)
        assertEquals("Invalid token", error.message)
    }

    // --- New tests below ---

    @Test
    fun `GatewayRequest default type is req`() {
        val req = GatewayRequest(id = "x", method = "test")
        assertEquals("req", req.type)
    }

    @Test
    fun `GatewayRequest default params is empty object`() {
        val req = GatewayRequest(id = "x", method = "test")
        assertEquals(JsonObject(emptyMap()), req.params)
    }

    @Test
    fun `GatewayFrame minimal fields`() {
        val raw = """{"type":"unknown"}"""
        val frame = json.decodeFromString(GatewayFrame.serializer(), raw)
        assertEquals("unknown", frame.type)
        assertNull(frame.id)
        assertNull(frame.ok)
        assertNull(frame.event)
        assertNull(frame.payload)
        assertNull(frame.seq)
    }

    @Test
    fun `ChatChunkPayload defaults done to false`() {
        val raw = """{"messageId":"m1","delta":"hi"}"""
        val chunk = json.decodeFromString(ChatChunkPayload.serializer(), raw)
        assertEquals(false, chunk.done)
    }

    @Test
    fun `ConnectParams default role is operator`() {
        val params = ConnectParams(client = ClientInfo())
        assertEquals("operator", params.role)
    }

    @Test
    fun `ConnectParams default scopes`() {
        val params = ConnectParams(client = ClientInfo())
        assertEquals(
            listOf("operator.read", "operator.write", "operator.approvals"),
            params.scopes,
        )
    }

    @Test
    fun `DeviceInfo optional fields default to null`() {
        val info = DeviceInfo(id = "dev-1")
        assertNull(info.publicKey)
        assertNull(info.signature)
        assertNull(info.signedAt)
        assertNull(info.nonce)
    }

    @Test
    fun `ApprovalResolveParams round-trip`() {
        val original = ApprovalResolveParams(requestId = "r1", approved = true, reason = "looks safe")
        val text = json.encodeToString(ApprovalResolveParams.serializer(), original)
        val decoded = json.decodeFromString(ApprovalResolveParams.serializer(), text)
        assertEquals(original, decoded)
    }

    @Test
    fun `ChatSendParams round-trip`() {
        val original = ChatSendParams(text = "hello world", sessionKey = "custom")
        val text = json.encodeToString(ChatSendParams.serializer(), original)
        val decoded = json.decodeFromString(ChatSendParams.serializer(), text)
        assertEquals(original, decoded)
    }

    @Test
    fun `GatewayResponse with error`() {
        val response = GatewayResponse(
            id = "err-1",
            ok = false,
            error = JsonError(code = "RATE_LIMIT", message = "Too many requests"),
        )
        val text = json.encodeToString(GatewayResponse.serializer(), response)
        val decoded = json.decodeFromString(GatewayResponse.serializer(), text)
        assertEquals(false, decoded.ok)
        assertEquals("RATE_LIMIT", decoded.error?.code)
        assertEquals("Too many requests", decoded.error?.message)
    }
}
