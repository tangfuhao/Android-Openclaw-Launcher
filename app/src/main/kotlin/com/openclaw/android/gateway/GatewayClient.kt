package com.openclaw.android.gateway

import android.util.Log
import com.openclaw.android.core.OpenClawConstants
import com.openclaw.android.data.PreferencesManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.os.Build
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket client for the OpenClaw Gateway protocol v3.
 *
 * Manages the full lifecycle: connect -> handshake -> operate -> disconnect.
 * Provides typed APIs for chat, approvals, and tool catalog queries.
 */
class GatewayClient(
    private val httpClient: OkHttpClient,
    private val preferencesManager: PreferencesManager,
    private val paths: OpenClawConstants.Paths,
) {

    companion object {
        private const val TAG = "GatewayClient"
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val REQUEST_TIMEOUT_MS = 30_000L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var tickWatchdogJob: Job? = null
    private val connectMutex = Mutex()

    // Tick heartbeat monitoring
    private var tickIntervalMs: Long = 15_000
    @Volatile private var lastTickAt: Long = 0

    // Seq gap detection
    @Volatile private var lastSeq: Long = -1

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

    private val _agentEvents = MutableSharedFlow<AgentEventPayload>(extraBufferCapacity = 64)
    val agentEvents: SharedFlow<AgentEventPayload> = _agentEvents.asSharedFlow()

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
        reconnectJob?.cancel()
        reconnectJob = null
        tickWatchdogJob?.cancel()
        tickWatchdogJob = null
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = GatewayState.Disconnected("Client initiated")
        failAllPendingRequests("Disconnected")
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

        return try {
            withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            pendingRequests.remove(id)
            throw e
        }
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

        checkSeqGap(event.seq)
        scope.launch { _events.emit(event) }

        when (event.event) {
            "connect.challenge" -> handleChallenge(event)
            "chat" -> dispatchChatEvent(event)
            "agent" -> dispatchAgentEvent(event)
            "exec.approval.requested" -> dispatchApproval(event)
            "tick" -> handleTick(event)
            "shutdown" -> handleShutdown(event)
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

    /**
     * Reads the auth token from the gateway's internal config file.
     * The gateway generates and writes this token on first startup.
     */
    private fun readGatewayAuthToken(): String? {
        val configFile = File(paths.hostOpenclawConfig, "openclaw.json")
        if (!configFile.exists()) return null
        return try {
            val parsed = json.parseToJsonElement(configFile.readText()).jsonObject
            parsed["gateway"]?.jsonObject?.get("auth")?.jsonObject?.get("token")?.jsonPrimitive?.content
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read gateway auth token", e)
            null
        }
    }

    private suspend fun performHandshake(challengePayload: JsonObject) {
        val challenge = json.decodeFromJsonElement(ConnectChallenge.serializer(), challengePayload)

        val authToken = readGatewayAuthToken()
        Log.d(TAG, "Auth token available: ${authToken != null}")

        val connectParams = ConnectParams(
            client = ClientInfo(
                deviceFamily = Build.MANUFACTURER,
                modelIdentifier = Build.MODEL,
            ),
            device = null,
            auth = if (authToken != null) AuthInfo(token = authToken) else AuthInfo(),
        )

        val paramsJson = json.encodeToJsonElement(ConnectParams.serializer(), connectParams).jsonObject
        val response = request("connect", paramsJson)

        if (response.ok) {
            val helloOk = response.payload?.let {
                try { json.decodeFromJsonElement(HelloOk.serializer(), it) } catch (_: Exception) { null }
            }
            val protocol = helloOk?.protocol ?: OpenClawConstants.GATEWAY_PROTOCOL_VERSION
            helloOk?.policy?.tickIntervalMs?.let { tickIntervalMs = it }
            _connectionState.value = GatewayState.Connected(protocol)
            reconnectAttempt = 0
            lastSeq = -1
            startTickWatchdog()
            Log.i(TAG, "Connected to gateway (protocol v$protocol)")
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

    private fun dispatchAgentEvent(event: GatewayEvent) {
        try {
            val agentEvent = json.decodeFromJsonElement(AgentEventPayload.serializer(), event.payload)
            scope.launch { _agentEvents.emit(agentEvent) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse agent event", e)
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

    private fun handleTick(event: GatewayEvent) {
        lastTickAt = System.currentTimeMillis()
    }

    private fun handleShutdown(event: GatewayEvent) {
        try {
            val payload = json.decodeFromJsonElement(ShutdownEventPayload.serializer(), event.payload)
            Log.i(TAG, "Gateway shutting down: ${payload.reason}, restart in ${payload.restartExpectedMs}ms")
            tickWatchdogJob?.cancel()
            _connectionState.value = GatewayState.Disconnected("Gateway shutdown: ${payload.reason}")
            payload.restartExpectedMs?.let { expectedMs ->
                reconnectAttempt = 0
                scope.launch {
                    delay(expectedMs + 1000)
                    connect()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse shutdown event", e)
        }
    }

    private fun startTickWatchdog() {
        tickWatchdogJob?.cancel()
        lastTickAt = System.currentTimeMillis()
        tickWatchdogJob = scope.launch {
            while (true) {
                delay(tickIntervalMs * 2)
                val elapsed = System.currentTimeMillis() - lastTickAt
                if (lastTickAt > 0 && elapsed > tickIntervalMs * 3) {
                    Log.w(TAG, "Tick timeout (${elapsed}ms since last tick), forcing reconnect")
                    webSocket?.close(4000, "Tick timeout")
                    break
                }
            }
        }
    }

    private fun checkSeqGap(seq: Long?) {
        if (seq == null) return
        if (lastSeq >= 0 && seq > lastSeq + 1) {
            Log.w(TAG, "Event seq gap: expected ${lastSeq + 1}, got $seq (missed ${seq - lastSeq - 1} events)")
        }
        lastSeq = seq
    }

    private fun failAllPendingRequests(reason: String) {
        val error = IllegalStateException(reason)
        pendingRequests.values.forEach { it.completeExceptionally(error) }
        pendingRequests.clear()
    }

    private fun scheduleReconnect() {
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = GatewayState.Error("Max reconnect attempts reached")
            return
        }

        reconnectAttempt++
        failAllPendingRequests("Connection lost, reconnecting")
        _connectionState.value = GatewayState.Reconnecting
        val delayMs = RECONNECT_DELAY_MS * reconnectAttempt.coerceAtMost(5)
        Log.i(TAG, "Scheduling reconnect attempt $reconnectAttempt in ${delayMs}ms")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            connectMutex.withLock {
                if (_connectionState.value is GatewayState.Reconnecting) {
                    connect()
                }
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
