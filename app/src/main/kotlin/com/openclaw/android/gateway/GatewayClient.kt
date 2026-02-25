package com.openclaw.android.gateway

import android.util.Log
import com.openclaw.android.core.OpenClawConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket client for the OpenClaw Gateway protocol v3.
 *
 * Manages the full lifecycle: connect -> handshake -> operate -> disconnect.
 * Provides typed APIs for chat, approvals, and tool catalog queries.
 */
class GatewayClient(private val httpClient: OkHttpClient) {

    companion object {
        private const val TAG = "GatewayClient"
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var reconnectAttempt = 0

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<GatewayResponse>>()

    private val _connectionState = MutableStateFlow<GatewayState>(GatewayState.Idle)
    val connectionState: StateFlow<GatewayState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    // Separate flow for chat events for easy collection in the UI
    private val _chatEvents = MutableSharedFlow<ChatEventPayload>(extraBufferCapacity = 64)
    val chatEvents: SharedFlow<ChatEventPayload> = _chatEvents.asSharedFlow()

    private val _approvalRequests = MutableSharedFlow<ApprovalRequestPayload>(extraBufferCapacity = 8)
    val approvalRequests: SharedFlow<ApprovalRequestPayload> = _approvalRequests.asSharedFlow()

    fun connect(
        host: String = OpenClawConstants.GATEWAY_HOST,
        port: Int = OpenClawConstants.GATEWAY_PORT,
    ) {
        if (_connectionState.value.isActive) return

        _connectionState.value = GatewayState.Connecting
        val url = "ws://$host:$port${OpenClawConstants.GATEWAY_WS_PATH}"
        val request = Request.Builder().url(url).build()

        Log.i(TAG, "Connecting to $url")
        webSocket = httpClient.newWebSocket(request, GatewayWebSocketListener())
    }

    fun disconnect() {
        reconnectAttempt = MAX_RECONNECT_ATTEMPTS // prevent auto-reconnect
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = GatewayState.Disconnected("Client initiated")
        pendingRequests.values.forEach {
            it.completeExceptionally(IllegalStateException("Disconnected"))
        }
        pendingRequests.clear()
    }

    /**
     * Sends a request and awaits the response.
     * @throws Exception on timeout, disconnect, or gateway error
     */
    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): GatewayResponse {
        val id = UUID.randomUUID().toString()
        val req = GatewayRequest(id = id, method = method, params = params)
        val deferred = CompletableDeferred<GatewayResponse>()
        pendingRequests[id] = deferred

        val text = json.encodeToString(GatewayRequest.serializer(), req)
        val sent = webSocket?.send(text) ?: false
        if (!sent) {
            pendingRequests.remove(id)
            throw IllegalStateException("WebSocket not connected")
        }

        return deferred.await()
    }

    /** Fire-and-forget: sends a request without waiting for a response. */
    fun send(method: String, params: JsonObject = JsonObject(emptyMap())) {
        val id = UUID.randomUUID().toString()
        val req = GatewayRequest(id = id, method = method, params = params)
        val text = json.encodeToString(GatewayRequest.serializer(), req)
        webSocket?.send(text)
    }

    private fun handleFrame(text: String) {
        try {
            val frame = json.decodeFromString(GatewayFrame.serializer(), text)

            when (frame.type) {
                "res" -> handleResponse(frame)
                "event" -> handleEvent(frame)
                "req" -> Log.d(TAG, "Received server request: ${frame.method}")
                else -> Log.w(TAG, "Unknown frame type: ${frame.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse frame: $text", e)
        }
    }

    private fun handleResponse(frame: GatewayFrame) {
        val id = frame.id ?: return
        val deferred = pendingRequests.remove(id) ?: return

        val response = GatewayResponse(
            id = id,
            ok = frame.ok ?: false,
            payload = frame.payload,
            error = frame.error?.let {
                try { json.decodeFromJsonElement(JsonError.serializer(), it) } catch (_: Exception) { null }
            },
        )
        deferred.complete(response)
    }

    private fun handleEvent(frame: GatewayFrame) {
        val event = GatewayEvent(
            event = frame.event ?: return,
            payload = frame.payload ?: JsonObject(emptyMap()),
            seq = frame.seq,
            stateVersion = frame.stateVersion,
        )

        scope.launch { _events.emit(event) }

        when (event.event) {
            "connect.challenge" -> handleChallenge(event)
            "chat" -> dispatchChatEvent(event)
            "exec.approval.requested" -> dispatchApproval(event)
        }
    }

    private fun handleChallenge(event: GatewayEvent) {
        _connectionState.value = GatewayState.Handshaking
        scope.launch {
            try {
                performHandshake(event.payload.jsonObject)
            } catch (e: Exception) {
                Log.e(TAG, "Handshake failed", e)
                _connectionState.value = GatewayState.Error("Handshake failed: ${e.message}")
            }
        }
    }

    private suspend fun performHandshake(challengePayload: JsonObject) {
        val challenge = json.decodeFromJsonElement(ConnectChallenge.serializer(), challengePayload)

        val deviceId = "android-${android.os.Build.MODEL.replace(" ", "-")}-${UUID.randomUUID().toString().take(8)}"

        val connectParams = ConnectParams(
            client = ClientInfo(),
            device = DeviceInfo(
                id = deviceId,
                nonce = challenge.nonce,
                signedAt = challenge.ts,
            ),
        )

        val paramsJson = json.encodeToJsonElement(ConnectParams.serializer(), connectParams).jsonObject
        val response = request("connect", paramsJson)

        if (response.ok) {
            val helloOk = response.payload?.let {
                try { json.decodeFromJsonElement(HelloOk.serializer(), it) } catch (_: Exception) { null }
            }
            val protocol = helloOk?.protocol ?: OpenClawConstants.GATEWAY_PROTOCOL_VERSION
            _connectionState.value = GatewayState.Connected(protocol)
            reconnectAttempt = 0
            Log.i(TAG, "Connected to gateway (protocol v$protocol)")

            // Subscribe to chat events
            send("chat.subscribe", buildJsonObject { put("sessionKey", "main") })
        } else {
            val errorMsg = response.error?.message ?: "Unknown handshake error"
            _connectionState.value = GatewayState.Error(errorMsg)
            Log.e(TAG, "Handshake rejected: $errorMsg")
        }
    }

    private fun dispatchChatEvent(event: GatewayEvent) {
        try {
            val chatEvent = json.decodeFromJsonElement(ChatEventPayload.serializer(), event.payload)
            scope.launch { _chatEvents.emit(chatEvent) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse chat event", e)
        }
    }

    private fun dispatchApproval(event: GatewayEvent) {
        try {
            val approval = json.decodeFromJsonElement(ApprovalRequestPayload.serializer(), event.payload)
            scope.launch { _approvalRequests.emit(approval) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse approval request", e)
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = GatewayState.Error("Max reconnect attempts reached")
            return
        }

        reconnectAttempt++
        _connectionState.value = GatewayState.Reconnecting
        val delayMs = RECONNECT_DELAY_MS * reconnectAttempt.coerceAtMost(5)
        Log.i(TAG, "Scheduling reconnect attempt $reconnectAttempt in ${delayMs}ms")

        scope.launch {
            delay(delayMs)
            if (_connectionState.value is GatewayState.Reconnecting) {
                connect()
            }
        }
    }

    private inner class GatewayWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket opened")
            // Wait for the connect.challenge event from gateway
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleFrame(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closing: $code $reason")
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: $code $reason")
            this@GatewayClient.webSocket = null
            if (code != 1000) {
                scheduleReconnect()
            } else {
                _connectionState.value = GatewayState.Disconnected(reason)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}", t)
            this@GatewayClient.webSocket = null
            _connectionState.value = GatewayState.Error(t.message ?: "Connection failed")
            scheduleReconnect()
        }
    }
}
