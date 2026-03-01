package com.openclaw.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.android.data.ChatMessage
import com.openclaw.android.gateway.ApprovalApi
import com.openclaw.android.gateway.ChatApi
import com.openclaw.android.gateway.GatewayClient
import com.openclaw.android.gateway.GatewayState
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
) : ViewModel() {

    private val chatApi = ChatApi(gatewayClient)
    private val approvalApi = ApprovalApi(gatewayClient)

    val connectionState: StateFlow<GatewayState> = gatewayClient.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GatewayState.Idle)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _pendingApproval = MutableStateFlow<ApprovalApi.ApprovalUiRequest?>(null)
    val pendingApproval: StateFlow<ApprovalApi.ApprovalUiRequest?> = _pendingApproval.asStateFlow()

    // Maps runId -> message ID in our list for tracking streaming responses
    private val runToMessageId = mutableMapOf<String, String>()

    init {
        observeChatEvents()
        observeApprovalRequests()
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatMessage.Role.USER,
            content = text.trim(),
            status = ChatMessage.Status.SENDING,
        )

        _messages.value = _messages.value + userMessage

        viewModelScope.launch {
            try {
                val runId = chatApi.sendMessage(text.trim())
                runToMessageId[runId] = runId
                updateMessage(userMessage.id) { it.copy(status = ChatMessage.Status.SENT) }
            } catch (e: Exception) {
                updateMessage(userMessage.id) { it.copy(status = ChatMessage.Status.ERROR) }
            }
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val history = chatApi.getHistory(limit = 50)
                _messages.value = history
            } catch (_: Exception) {
                // History load failure is non-fatal
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

    private fun observeChatEvents() {
        viewModelScope.launch {
            chatApi.observeChatEvents().collect { event ->
                when (event) {
                    is ChatApi.ChatEvent.Delta -> handleDelta(event)
                    is ChatApi.ChatEvent.Final -> handleFinal(event)
                    is ChatApi.ChatEvent.Error -> handleError(event)
                    ChatApi.ChatEvent.Unknown -> {}
                }
            }
        }
    }

    private fun handleDelta(event: ChatApi.ChatEvent.Delta) {
        val runId = event.runId
        val existingMsgId = runToMessageId[runId]

        if (existingMsgId != null) {
            val idx = _messages.value.indexOfFirst { it.id == existingMsgId }
            if (idx >= 0) {
                updateMessage(existingMsgId) {
                    it.copy(content = event.text, isStreaming = true)
                }
            }
        } else {
            val msgId = "assistant-$runId"
            runToMessageId[runId] = msgId
            val streamingMsg = ChatMessage(
                id = msgId,
                role = ChatMessage.Role.ASSISTANT,
                content = event.text,
                isStreaming = true,
            )
            _messages.value = _messages.value + streamingMsg
        }
    }

    private fun handleFinal(event: ChatApi.ChatEvent.Final) {
        val runId = event.runId
        val existingMsgId = runToMessageId.remove(runId)

        if (existingMsgId != null) {
            val idx = _messages.value.indexOfFirst { it.id == existingMsgId }
            if (idx >= 0) {
                updateMessage(existingMsgId) {
                    it.copy(
                        content = event.text ?: it.content,
                        isStreaming = false,
                    )
                }
                return
            }
        }

        if (!event.text.isNullOrBlank()) {
            val finalMsg = ChatMessage(
                id = "assistant-$runId",
                role = ChatMessage.Role.ASSISTANT,
                content = event.text,
                isStreaming = false,
            )
            _messages.value = _messages.value + finalMsg
        }
    }

    private fun handleError(event: ChatApi.ChatEvent.Error) {
        val runId = event.runId
        val existingMsgId = runToMessageId.remove(runId)

        if (existingMsgId != null) {
            val idx = _messages.value.indexOfFirst { it.id == existingMsgId }
            if (idx >= 0) {
                updateMessage(existingMsgId) {
                    it.copy(
                        content = it.content + "\n\n[Error: ${event.errorMessage}]",
                        isStreaming = false,
                        status = ChatMessage.Status.ERROR,
                    )
                }
                return
            }
        }

        val errorMsg = ChatMessage(
            id = "error-$runId",
            role = ChatMessage.Role.SYSTEM,
            content = "Error: ${event.errorMessage}",
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

    private fun updateMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map {
            if (it.id == id) transform(it) else it
        }
    }
}
