package com.openclaw.android.ui.chat

import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.android.data.ChatMessage
import com.openclaw.android.data.ContentBlock
import com.openclaw.android.data.RunPhase
import com.openclaw.android.data.ToolActivity
import com.openclaw.android.data.ToolPhase
import com.openclaw.android.gateway.ApprovalApi
import com.openclaw.android.gateway.ChatApi
import com.openclaw.android.gateway.ChatAttachment
import com.openclaw.android.gateway.GatewayClient
import com.openclaw.android.gateway.GatewayState
import com.openclaw.android.gateway.SessionApi
import com.openclaw.android.proot.FileBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val gatewayClient: GatewayClient,
    val fileBridge: FileBridge,
) : ViewModel() {

    private val chatApi = ChatApi(gatewayClient)
    private val sessionApi = SessionApi(gatewayClient)
    private val approvalApi = ApprovalApi(gatewayClient)

    companion object {
        private const val SESSION_KEY = "main"
    }

    val connectionState: StateFlow<GatewayState> = gatewayClient.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GatewayState.Idle)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _pendingApproval = MutableStateFlow<ApprovalApi.ApprovalUiRequest?>(null)
    val pendingApproval: StateFlow<ApprovalApi.ApprovalUiRequest?> = _pendingApproval.asStateFlow()

    private val _activeRunId = MutableStateFlow<String?>(null)
    val activeRunId: StateFlow<String?> = _activeRunId.asStateFlow()

    private val runToMessageId = mutableMapOf<String, String>()

    /**
     * The id of an assistant placeholder message that has been added to the
     * message list but not yet linked to a runId. This is set immediately when
     * the user sends a message and cleared once the RPC returns or the first
     * event claims it.
     */
    private var pendingPlaceholderId: String? = null

    init {
        observeChatEvents()
        observeApprovalRequests()
        observeAgentToolEvents()
    }

    // ── Mapping resolution ──────────────────────────────────────────

    /**
     * Resolves a runId to the corresponding assistant message id.
     * If no mapping exists yet, attempts to claim the pending placeholder.
     * Returns null if no matching message can be found.
     */
    private fun getOrClaimMessageId(runId: String): String? {
        runToMessageId[runId]?.let { return it }

        val pending = pendingPlaceholderId
        if (pending != null) {
            runToMessageId[runId] = pending
            pendingPlaceholderId = null
            return pending
        }

        return null
    }

    // ── Public API ──────────────────────────────────────────────────

    fun sendMessage(
        text: String,
        attachments: List<ChatAttachment>? = null,
    ) {
        if (text.isBlank() && attachments.isNullOrEmpty()) return

        val trimmed = text.trim()

        if (trimmed == "/clear") {
            _messages.value = emptyList()
            runToMessageId.clear()
            pendingPlaceholderId = null
            return
        }

        if (trimmed.matches(Regex("^/(reset|new)(\\s.*)?$"))) {
            resetSession()
            return
        }

        val blocks = buildList {
            if (trimmed.isNotBlank()) add(ContentBlock.Text(trimmed))
        }

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatMessage.Role.USER,
            contentBlocks = blocks,
            status = ChatMessage.Status.SENDING,
        )

        val placeholderId = "assistant-${UUID.randomUUID()}"
        val placeholder = ChatMessage(
            id = placeholderId,
            role = ChatMessage.Role.ASSISTANT,
            runPhase = RunPhase.THINKING,
            isStreaming = true,
        )

        _messages.value = _messages.value + userMessage + placeholder
        pendingPlaceholderId = placeholderId

        viewModelScope.launch {
            try {
                val runId = chatApi.sendMessage(
                    text = trimmed,
                    sessionKey = SESSION_KEY,
                    attachments = attachments,
                )
                _activeRunId.value = runId

                if (!runToMessageId.containsKey(runId)) {
                    runToMessageId[runId] = placeholderId
                    updateMessage(placeholderId) { it.copy(runId = runId) }
                } else {
                    // Events already claimed the placeholder or created their own message.
                    // If the placeholder is still orphaned, remove it.
                    if (pendingPlaceholderId == placeholderId) {
                        _messages.value = _messages.value.filter { it.id != placeholderId }
                    }
                }

                if (pendingPlaceholderId == placeholderId) {
                    pendingPlaceholderId = null
                }

                updateMessage(userMessage.id) { it.copy(status = ChatMessage.Status.SENT) }
            } catch (e: Exception) {
                _messages.value = _messages.value.filter { it.id != placeholderId }
                if (pendingPlaceholderId == placeholderId) pendingPlaceholderId = null
                updateMessage(userMessage.id) { it.copy(status = ChatMessage.Status.ERROR) }
            }
        }
    }

    fun resetSession() {
        viewModelScope.launch {
            try {
                sessionApi.reset(SESSION_KEY, reason = "reset")
            } catch (_: Exception) {}
            _messages.value = emptyList()
            runToMessageId.clear()
            pendingPlaceholderId = null
            _activeRunId.value = null
        }
    }

    fun compactSession() {
        viewModelScope.launch {
            try {
                sessionApi.compact(SESSION_KEY)
            } catch (_: Exception) {}
        }
    }

    fun sendImageAttachment(uri: Uri) {
        viewModelScope.launch {
            try {
                val bridged = fileBridge.importToProotFromUri(uri)
                val bytes = fileBridge.readProotFile(bridged.prootPath)
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

                val attachment = ChatAttachment(
                    type = "image",
                    mimeType = bridged.mimeType,
                    fileName = bridged.fileName,
                    content = base64,
                )

                val userMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = ChatMessage.Role.USER,
                    contentBlocks = listOf(
                        ContentBlock.UserAttachment(
                            localUri = uri.toString(),
                            prootPath = bridged.prootPath,
                            mimeType = bridged.mimeType,
                            fileName = bridged.fileName,
                            size = bridged.size,
                        ),
                    ),
                    status = ChatMessage.Status.SENDING,
                )

                val placeholderId = "assistant-${UUID.randomUUID()}"
                val placeholder = ChatMessage(
                    id = placeholderId,
                    role = ChatMessage.Role.ASSISTANT,
                    runPhase = RunPhase.THINKING,
                    isStreaming = true,
                )
                _messages.value = _messages.value + userMsg + placeholder
                pendingPlaceholderId = placeholderId

                val runId = chatApi.sendMessage(
                    text = "[Sent image: ${bridged.fileName}]",
                    sessionKey = SESSION_KEY,
                    attachments = listOf(attachment),
                )
                _activeRunId.value = runId
                if (!runToMessageId.containsKey(runId)) {
                    runToMessageId[runId] = placeholderId
                    updateMessage(placeholderId) { it.copy(runId = runId) }
                } else if (pendingPlaceholderId == placeholderId) {
                    _messages.value = _messages.value.filter { it.id != placeholderId }
                }
                if (pendingPlaceholderId == placeholderId) pendingPlaceholderId = null
                updateMessage(userMsg.id) { it.copy(status = ChatMessage.Status.SENT) }
            } catch (e: Exception) {
                pendingPlaceholderId = null
                val errMsg = ChatMessage.ofText(
                    id = UUID.randomUUID().toString(),
                    role = ChatMessage.Role.SYSTEM,
                    text = "Failed to send image: ${e.message}",
                    status = ChatMessage.Status.ERROR,
                )
                _messages.value = _messages.value + errMsg
            }
        }
    }

    fun sendFileAttachment(uri: Uri) {
        viewModelScope.launch {
            try {
                val bridged = fileBridge.importToProotFromUri(uri)

                val userMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = ChatMessage.Role.USER,
                    contentBlocks = listOf(
                        ContentBlock.UserAttachment(
                            localUri = uri.toString(),
                            prootPath = bridged.prootPath,
                            mimeType = bridged.mimeType,
                            fileName = bridged.fileName,
                            size = bridged.size,
                        ),
                    ),
                    status = ChatMessage.Status.SENDING,
                )

                val placeholderId = "assistant-${UUID.randomUUID()}"
                val placeholder = ChatMessage(
                    id = placeholderId,
                    role = ChatMessage.Role.ASSISTANT,
                    runPhase = RunPhase.THINKING,
                    isStreaming = true,
                )
                _messages.value = _messages.value + userMsg + placeholder
                pendingPlaceholderId = placeholderId

                val runId = chatApi.sendMessage(
                    text = "I've shared a file at ${bridged.prootPath} (${bridged.fileName}, ${bridged.mimeType}). Please process it.",
                    sessionKey = SESSION_KEY,
                )
                _activeRunId.value = runId
                if (!runToMessageId.containsKey(runId)) {
                    runToMessageId[runId] = placeholderId
                    updateMessage(placeholderId) { it.copy(runId = runId) }
                } else if (pendingPlaceholderId == placeholderId) {
                    _messages.value = _messages.value.filter { it.id != placeholderId }
                }
                if (pendingPlaceholderId == placeholderId) pendingPlaceholderId = null
                updateMessage(userMsg.id) { it.copy(status = ChatMessage.Status.SENT) }
            } catch (e: Exception) {
                pendingPlaceholderId = null
                val errMsg = ChatMessage.ofText(
                    id = UUID.randomUUID().toString(),
                    role = ChatMessage.Role.SYSTEM,
                    text = "Failed to send file: ${e.message}",
                    status = ChatMessage.Status.ERROR,
                )
                _messages.value = _messages.value + errMsg
            }
        }
    }

    fun sendVoiceMessage(audioFile: java.io.File) {
        viewModelScope.launch {
            try {
                val bridged = fileBridge.importToProotFromBytes(
                    bytes = audioFile.readBytes(),
                    fileName = audioFile.name,
                    mimeType = "audio/mp4",
                )

                val userMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = ChatMessage.Role.USER,
                    contentBlocks = listOf(
                        ContentBlock.UserAttachment(
                            localUri = audioFile.toURI().toString(),
                            prootPath = bridged.prootPath,
                            mimeType = "audio/mp4",
                            fileName = bridged.fileName,
                            size = bridged.size,
                        ),
                    ),
                    status = ChatMessage.Status.SENDING,
                )

                val placeholderId = "assistant-${UUID.randomUUID()}"
                val placeholder = ChatMessage(
                    id = placeholderId,
                    role = ChatMessage.Role.ASSISTANT,
                    runPhase = RunPhase.THINKING,
                    isStreaming = true,
                )
                _messages.value = _messages.value + userMsg + placeholder
                pendingPlaceholderId = placeholderId

                val runId = chatApi.sendMessage(
                    text = "I've sent a voice message at ${bridged.prootPath}. Please transcribe and respond to it.",
                    sessionKey = SESSION_KEY,
                )
                _activeRunId.value = runId
                if (!runToMessageId.containsKey(runId)) {
                    runToMessageId[runId] = placeholderId
                    updateMessage(placeholderId) { it.copy(runId = runId) }
                } else if (pendingPlaceholderId == placeholderId) {
                    _messages.value = _messages.value.filter { it.id != placeholderId }
                }
                if (pendingPlaceholderId == placeholderId) pendingPlaceholderId = null
                updateMessage(userMsg.id) { it.copy(status = ChatMessage.Status.SENT) }
            } catch (e: Exception) {
                pendingPlaceholderId = null
                val errMsg = ChatMessage.ofText(
                    id = UUID.randomUUID().toString(),
                    role = ChatMessage.Role.SYSTEM,
                    text = "Failed to send voice message: ${e.message}",
                    status = ChatMessage.Status.ERROR,
                )
                _messages.value = _messages.value + errMsg
            }
        }
    }

    fun deleteMessage(messageId: String) {
        _messages.value = _messages.value.filter { it.id != messageId }
    }

    fun retryMessage(messageId: String) {
        val msg = _messages.value.find { it.id == messageId } ?: return
        val text = msg.textContent
        if (text.isBlank()) return
        deleteMessage(messageId)
        sendMessage(text)
    }

    fun loadHistory() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val history = chatApi.getHistory(sessionKey = SESSION_KEY, limit = 50)
                _messages.value = history
            } catch (_: Exception) {
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun resolveApproval(requestId: String, approved: Boolean) {
        viewModelScope.launch {
            try {
                approvalApi.resolve(requestId, approved)
                _pendingApproval.value = null
            } catch (_: Exception) { }
        }
    }

    // ── Event observers ─────────────────────────────────────────────

    private fun observeChatEvents() {
        viewModelScope.launch {
            chatApi.observeChatEvents().collect { event ->
                when (event) {
                    is ChatApi.ChatEvent.Delta -> handleDelta(event)
                    is ChatApi.ChatEvent.Final -> handleFinal(event)
                    is ChatApi.ChatEvent.Aborted -> handleAborted(event)
                    is ChatApi.ChatEvent.Error -> handleError(event)
                    ChatApi.ChatEvent.Unknown -> {}
                }
            }
        }
    }

    private fun handleDelta(event: ChatApi.ChatEvent.Delta) {
        val msgId = getOrClaimMessageId(event.runId)

        if (msgId != null) {
            updateMessage(msgId) {
                it.copy(
                    contentBlocks = event.contentBlocks,
                    runPhase = RunPhase.RESPONDING,
                    isStreaming = true,
                    runId = event.runId,
                )
            }
        } else {
            val newId = "assistant-${event.runId}"
            runToMessageId[event.runId] = newId
            _messages.value = _messages.value + ChatMessage(
                id = newId,
                role = ChatMessage.Role.ASSISTANT,
                contentBlocks = event.contentBlocks,
                runPhase = RunPhase.RESPONDING,
                isStreaming = true,
                runId = event.runId,
            )
        }
    }

    private fun handleFinal(event: ChatApi.ChatEvent.Final) {
        val runId = event.runId
        val existingMsgId = runToMessageId.remove(runId)
        if (runId == _activeRunId.value) _activeRunId.value = null

        if (existingMsgId != null) {
            val idx = _messages.value.indexOfFirst { it.id == existingMsgId }
            if (idx >= 0) {
                updateMessage(existingMsgId) {
                    it.copy(
                        contentBlocks = event.contentBlocks ?: it.contentBlocks,
                        runPhase = RunPhase.DONE,
                        isStreaming = false,
                    )
                }
                return
            }
        }

        if (!event.contentBlocks.isNullOrEmpty()) {
            val finalMsg = ChatMessage(
                id = "assistant-$runId",
                role = ChatMessage.Role.ASSISTANT,
                contentBlocks = event.contentBlocks,
                runPhase = RunPhase.DONE,
                isStreaming = false,
                runId = runId,
            )
            _messages.value = _messages.value + finalMsg
        }
    }

    private fun handleAborted(event: ChatApi.ChatEvent.Aborted) {
        val runId = event.runId
        val existingMsgId = runToMessageId.remove(runId)
        if (runId == _activeRunId.value) _activeRunId.value = null

        if (existingMsgId != null) {
            updateMessage(existingMsgId) {
                it.copy(
                    runPhase = RunPhase.DONE,
                    isStreaming = false,
                    status = ChatMessage.Status.SENT,
                )
            }
        }
    }

    private fun handleError(event: ChatApi.ChatEvent.Error) {
        val runId = event.runId
        val existingMsgId = runToMessageId.remove(runId)
        if (runId == _activeRunId.value) _activeRunId.value = null

        if (existingMsgId != null) {
            val idx = _messages.value.indexOfFirst { it.id == existingMsgId }
            if (idx >= 0) {
                updateMessage(existingMsgId) {
                    val errorBlock = ContentBlock.Text("[Error: ${event.errorMessage}]")
                    it.copy(
                        contentBlocks = it.contentBlocks + errorBlock,
                        runPhase = RunPhase.DONE,
                        isStreaming = false,
                        status = ChatMessage.Status.ERROR,
                    )
                }
                return
            }
        }

        val errorMsg = ChatMessage.ofText(
            id = "error-$runId",
            role = ChatMessage.Role.SYSTEM,
            text = "Error: ${event.errorMessage}",
            status = ChatMessage.Status.ERROR,
        )
        _messages.value = _messages.value + errorMsg
    }

    private fun observeApprovalRequests() {
        viewModelScope.launch {
            approvalApi.observeApprovalRequests().collect { request ->
                _pendingApproval.value = request
            }
        }
    }

    private fun observeAgentToolEvents() {
        viewModelScope.launch {
            chatApi.observeAgentToolEvents().collect { event ->
                when (event) {
                    is ChatApi.AgentToolEvent.ToolStream -> handleToolStream(event)
                    is ChatApi.AgentToolEvent.Lifecycle -> {}
                    is ChatApi.AgentToolEvent.Other -> {}
                }
            }
        }
    }

    /**
     * Tool events write ONLY to [ChatMessage.toolActivities], never to contentBlocks.
     * This avoids the delta-replaces-everything problem entirely.
     */
    private fun handleToolStream(event: ChatApi.AgentToolEvent.ToolStream) {
        val msgId = getOrClaimMessageId(event.runId) ?: return
        val data = event.data?.jsonObject ?: return

        val toolId = data["toolCallId"]?.jsonPrimitive?.content
            ?: data["toolId"]?.jsonPrimitive?.content
            ?: data["id"]?.jsonPrimitive?.content
            ?: UUID.randomUUID().toString()

        when (event.phase) {
            "start", "invoke" -> {
                val input = data["args"]?.jsonObject ?: data["input"]?.jsonObject
                val activity = ToolActivity(
                    toolId = toolId,
                    toolName = event.toolName ?: "unknown",
                    phase = ToolPhase.RUNNING,
                    input = input,
                )
                updateMessage(msgId) {
                    val updated = it.toolActivities.toMutableList()
                    val existingIdx = updated.indexOfFirst { a -> a.toolId == toolId }
                    if (existingIdx >= 0) {
                        updated[existingIdx] = activity
                    } else {
                        updated.add(activity)
                    }
                    it.copy(
                        toolActivities = updated,
                        runPhase = RunPhase.TOOL_EXECUTING,
                        isStreaming = true,
                        runId = event.runId,
                    )
                }
            }
            "result", "complete" -> {
                val content = data["meta"]?.jsonPrimitive?.content
                    ?: data["content"]?.jsonPrimitive?.content
                    ?: data["output"]?.jsonPrimitive?.content ?: ""
                val isError = data["isError"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

                updateMessage(msgId) {
                    val updated = it.toolActivities.toMutableList()
                    val existingIdx = updated.indexOfFirst { a -> a.toolId == toolId }
                    if (existingIdx >= 0) {
                        updated[existingIdx] = updated[existingIdx].copy(
                            phase = if (isError) ToolPhase.ERROR else ToolPhase.COMPLETED,
                            output = content.take(4000),
                            isError = isError,
                        )
                    } else {
                        val name = event.toolName
                            ?: data["name"]?.jsonPrimitive?.content ?: "unknown"
                        updated.add(ToolActivity(
                            toolId = toolId,
                            toolName = name,
                            phase = if (isError) ToolPhase.ERROR else ToolPhase.COMPLETED,
                            output = content.take(4000),
                            isError = isError,
                        ))
                    }
                    it.copy(toolActivities = updated)
                }
            }
        }
    }

    private fun updateMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map {
            if (it.id == id) transform(it) else it
        }
    }
}
