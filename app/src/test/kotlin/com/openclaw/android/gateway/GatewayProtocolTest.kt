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

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun gatewayRequestSerializesCorrectly() {
        val req = GatewayRequest(id = "test-1", method = "chat.send", params = buildJsonObject {
            put("message", "hello")
        })
        val text = json.encodeToString(GatewayRequest.serializer(), req)
        assertTrue(text.contains("\"type\":\"req\""))
        assertTrue(text.contains("\"id\":\"test-1\""))
        assertTrue(text.contains("\"method\":\"chat.send\""))
    }

    @Test
    fun gatewayFrameParsesResponseFrame() {
        val raw = """{"type":"res","id":"abc","ok":true,"payload":{"protocol":3}}"""
        val frame = json.decodeFromString(GatewayFrame.serializer(), raw)
        assertEquals("res", frame.type)
        assertEquals("abc", frame.id)
        assertEquals(true, frame.ok)
        assertNotNull(frame.payload)
    }

    @Test
    fun connectChallengeDeserializes() {
        val raw = """{"nonce":"abc123","ts":1700000000}"""
        val challenge = json.decodeFromString(ConnectChallenge.serializer(), raw)
        assertEquals("abc123", challenge.nonce)
        assertEquals(1700000000L, challenge.ts)
    }

    @Test
    fun connectParamsSerializeWithDefaults() {
        val params = ConnectParams(client = ClientInfo())
        val text = json.encodeToString(ConnectParams.serializer(), params)
        assertTrue(text.contains("\"role\":\"operator\""))
        assertTrue(text.contains("\"minProtocol\":3"))
    }

    @Test
    fun connectParamsOmitNullDevice() {
        val params = ConnectParams(client = ClientInfo(), device = null)
        val text = json.encodeToString(ConnectParams.serializer(), params)
        assertTrue(!text.contains("\"device\""))
    }

    @Test
    fun chatEventPayloadDeserializesDeltaState() {
        val raw = """{"runId":"run1","sessionKey":"main","seq":1,"state":"delta","message":{"role":"assistant","content":[{"type":"text","text":"hello"}],"timestamp":1700000000}}"""
        val payload = json.decodeFromString(ChatEventPayload.serializer(), raw)
        assertEquals("run1", payload.runId)
        assertEquals("delta", payload.state)
        assertNotNull(payload.message)
    }

    @Test
    fun chatMessagePayloadHandlesStringContent() {
        val raw = """{"role":"user","content":"hello","timestamp":1700000000}"""
        val msg = json.decodeFromString(ChatMessagePayload.serializer(), raw)
        assertEquals("user", msg.role)
        assertEquals(JsonPrimitive("hello"), msg.content)
    }

    @Test
    fun approvalRequestPayloadMatchesOfficialSchema() {
        val raw = """{"id":"r1","command":"bash","commandArgv":["-c","ls -la"],"cwd":"/root"}"""
        val approval = json.decodeFromString(ApprovalRequestPayload.serializer(), raw)
        assertEquals("r1", approval.id)
        assertEquals("bash", approval.command)
        assertEquals(listOf("-c", "ls -la"), approval.commandArgv)
        assertEquals("/root", approval.cwd)
    }

    @Test
    fun helloOkDeserializesWithDefaults() {
        val raw = """{"type":"hello-ok","protocol":3}"""
        val helloOk = json.decodeFromString(HelloOk.serializer(), raw)
        assertEquals(3, helloOk.protocol)
        assertNull(helloOk.policy)
    }

    @Test
    fun chatSendParamsRoundTrip() {
        val original = ChatSendParams(message = "hello world", sessionKey = "custom", idempotencyKey = "key-1")
        val text = json.encodeToString(ChatSendParams.serializer(), original)
        val decoded = json.decodeFromString(ChatSendParams.serializer(), text)
        assertEquals(original, decoded)
    }

    @Test
    fun chatSendParamsWithoutOptionalFieldsOmitOptionalValues() {
        val params = ChatSendParams(message = "hello", idempotencyKey = "k-2")
        val text = json.encodeToString(ChatSendParams.serializer(), params)
        assertTrue(!text.contains("\"thinking\""))
        assertTrue(!text.contains("\"deliver\""))
        assertTrue(!text.contains("\"timeoutMs\""))
    }

    @Test
    fun approvalResolveParamsRoundTrip() {
        val original = ApprovalResolveParams(id = "r1", decision = "allow")
        val text = json.encodeToString(ApprovalResolveParams.serializer(), original)
        val decoded = json.decodeFromString(ApprovalResolveParams.serializer(), text)
        assertEquals(original, decoded)
        assertTrue(text.contains("\"decision\":\"allow\""))
    }

    @Test
    fun connectParamsDefaultScopesIncludeOperatorAdmin() {
        val params = ConnectParams(client = ClientInfo())
        assertTrue(params.scopes.contains("operator.admin"))
        assertTrue(params.scopes.contains("operator.read"))
        assertTrue(params.scopes.contains("operator.write"))
    }

    @Test
    fun connectParamsDefaultCapsIncludeToolEvents() {
        val params = ConnectParams(client = ClientInfo())
        assertTrue(params.caps.contains("tool-events"))
    }

    @Test
    fun sessionsResetParamsUseKeyWireField() {
        val params = SessionsResetParams(sessionKey = "test-session")
        val text = json.encodeToString(SessionsResetParams.serializer(), params)
        assertTrue(text.contains("\"key\":\"test-session\""))
        assertTrue(!text.contains("\"sessionKey\""))
    }

    @Test
    fun gatewayRequestDefaultParamsIsEmptyObject() {
        val req = GatewayRequest(id = "x", method = "test")
        assertEquals(JsonObject(emptyMap()), req.params)
    }
}
