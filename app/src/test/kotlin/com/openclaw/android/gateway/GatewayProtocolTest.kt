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
}
