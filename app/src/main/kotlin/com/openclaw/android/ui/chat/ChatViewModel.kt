package com.openclaw.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.android.data.ChatMessage
import com.openclaw.android.data.ContentBlock
import com.openclaw.android.data.RunPhase
import com.openclaw.android.gateway.ApprovalApi
import com.openclaw.android.gateway.ChatApi
import com.openclaw.android.gateway.GatewayClient
import com.openclaw.android.gateway.GatewayState
import com.openclaw.android.gateway.SessionApi
import com.openclaw.android.service.ProcessManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val gatewayClient: GatewayClient,
    private val processManager: ProcessManager,
) : ViewModel() {

    companion object {
        private const val SESSION_KEY = "main"
    }

    private val chatApi = ChatApi(gatewayClient)
    private val sessionApi = SessionApi(gatewayClient)
    private val approvalApi = ApprovalApi(gatewayClient)

    val connectionState: StateFlow<GatewayState> = gatewayClient.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GatewayState.Idle)

    val processState: StateFlow<ProcessManager.ProcessState> = processManager.processState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProcessManager.ProcessState.Stopped)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _pendingApproval = MutableStateFlow<ApprovalApi.ApprovalUiRequest?>(null)
    val pendingApproval: StateFlow<ApprovalApi.ApprovalUiRequest?> = _pendingApproval.asStateFlow()

    private val _activeRunId = MutableStateFlow<String?>(null)
    val activeRunId: StateFlow<String?> = _activeRunId.asStateFlow()

    private val runToMessageId = mutableMapOf<String, String>()
    private var pendingPlaceholderId: String? = null

    init {
        observeChatEvents()
        observeApprovalRequests()
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        when (trimmed) {
            "/clear" -> {
                clearLocalMessages()
                return
            }
            "/reset", "/new" -> {
                resetSession()
                return
            }
        }

        val userMessage = ChatMessage.ofText(
            id = UUID.randomUUID().toString(),
            role = ChatMessage.Role.USER,
            text = trimmed,
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
                )
                _activeRunId.value = runId

                if (!runToMessageId.containsKey(runId)) {
                    runToMessageId[runId] = placeholderId
                    updateMessage(placeholderId) { it.copy(runId = runId) }
                } else if (pendingPlaceholderId == placeholderId) {
                    _messages.value = _messages.value.filter { it.id != placeholderId }
                }

                if (pendingPlaceholderId == placeholderId) {
                    pendingPlaceholderId = null
                }

                updateMessage(userMessage.id) { it.copy(status = ChatMessage.Status.SENT) }
            } catch (_: Exception) {
                _messages.value = _messages.value.filter { it.id != placeholderId }
                if (pendingPlaceholderId == placeholderId) {
                    pendingPlaceholderId = null
                }
                updateMessage(userMessage.id) { it.copy(status = ChatMessage.Status.ERROR) }
            }
        }
    }

    fun resetSession() {
        viewModelScope.launch {
            try {
                sessionApi.reset(SESSION_KEY, reason = "reset")
            } catch (_: Exception) {
                // Clear local state even when the server reset fails.
            }
            clearLocalMessages(clearActiveRun = true)
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val history = chatApi.getHistory(sessionKey = SESSION_KEY, limit = 50)
                _messages.value = history
            } catch (_: Exception) {
                // Leave the current UI state untouched on history failures.
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
            } catch (_: Exception) {
                // Keep the pending request visible so the user can try again.
            }
        }
    }

    private fun observeChatEvents() {
        viewModelScope.launch {
            chatApi.observeChatEvents().collect { event ->
                when (event) {
                    is ChatApi.ChatEvent.Delta -> handleDelta(event)
                    is ChatApi.ChatEvent.Final -> handleFinal(event)
                    is ChatApi.ChatEvent.Aborted -> handleAborted(event)
                    is ChatApi.ChatEvent.Error -> handleError(event)
                    ChatApi.ChatEvent.Unknown -> Unit
                }
            }
        }
    }

    private fun observeApprovalRequests() {
        viewModelScope.launch {
            approvalApi.observeApprovalRequests().collect { request ->
                _pendingApproval.value = request
            }
        }
    }

    private fun handleDelta(event: ChatApi.ChatEvent.Delta) {
        if (event.contentBlocks.isEmpty()) return

        val messageId = getOrClaimMessageId(event.runId)
        if (messageId != null) {
            updateMessage(messageId) {
                it.copy(
                    contentBlocks = event.contentBlocks,
                    runPhase = RunPhase.RESPONDING,
                    isStreaming = true,
                    runId = event.runId,
                )
            }
            return
        }

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

    private fun handleFinal(event: ChatApi.ChatEvent.Final) {
        val existingMessageId = runToMessageId.remove(event.runId)
        if (event.runId == _activeRunId.value) {
            _activeRunId.value = null
        }

        if (existingMessageId != null) {
            updateMessage(existingMessageId) { current ->
                current.copy(
                    contentBlocks = if (event.contentBlocks.isNotEmpty()) event.contentBlocks else current.contentBlocks,
                    runPhase = RunPhase.DONE,
                    isStreaming = false,
                )
            }
            return
        }

        if (event.contentBlocks.isEmpty()) return

        _messages.value = _messages.value + ChatMessage(
            id = "assistant-${event.runId}",
            role = ChatMessage.Role.ASSISTANT,
            contentBlocks = event.contentBlocks,
            runPhase = RunPhase.DONE,
            isStreaming = false,
            runId = event.runId,
        )
    }

    private fun handleAborted(event: ChatApi.ChatEvent.Aborted) {
        val existingMessageId = runToMessageId.remove(event.runId)
        if (event.runId == _activeRunId.value) {
            _activeRunId.value = null
        }

        if (existingMessageId != null) {
            updateMessage(existingMessageId) {
                it.copy(
                    runPhase = RunPhase.DONE,
                    isStreaming = false,
                    status = ChatMessage.Status.SENT,
                )
            }
        }
    }

    private fun handleError(event: ChatApi.ChatEvent.Error) {
        val existingMessageId = runToMessageId.remove(event.runId)
        if (event.runId == _activeRunId.value) {
            _activeRunId.value = null
        }

        if (existingMessageId != null) {
            updateMessage(existingMessageId) { current ->
                current.copy(
                    contentBlocks = current.contentBlocks + ContentBlock.Text("[Error: ${event.errorMessage}]"),
                    runPhase = RunPhase.DONE,
                    isStreaming = false,
                    status = ChatMessage.Status.ERROR,
                )
            }
            return
        }

        _messages.value = _messages.value + ChatMessage.ofText(
            id = "error-${event.runId}",
            role = ChatMessage.Role.SYSTEM,
            text = "Error: ${event.errorMessage}",
            status = ChatMessage.Status.ERROR,
        )
    }

    private fun clearLocalMessages(clearActiveRun: Boolean = false) {
        _messages.value = emptyList()
        runToMessageId.clear()
        pendingPlaceholderId = null
        if (clearActiveRun) {
            _activeRunId.value = null
        }
    }

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

    private fun updateMessage(messageId: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { message ->
            if (message.id == messageId) transform(message) else message
        }
    }
}
