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

    // Tracks streaming state per message ID
    private val streamingMessages = mutableMapOf<String, StringBuilder>()

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
                chatApi.sendMessage(text.trim())
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
            } catch (_: Exception) {
                // Show error in UI if needed
            }
        }
    }

    private fun observeChatEvents() {
        viewModelScope.launch {
            chatApi.observeChatEvents().collect { event ->
                when (event) {
                    is ChatApi.ChatEvent.Message -> {
                        // Complete message received — add or replace
                        val existing = _messages.value.indexOfFirst { it.id == event.message.id }
                        if (existing >= 0) {
                            updateMessage(event.message.id) { event.message }
                        } else {
                            _messages.value = _messages.value + event.message
                        }
                        streamingMessages.remove(event.message.id)
                    }
                    is ChatApi.ChatEvent.Chunk -> {
                        handleStreamingChunk(event)
                    }
                    ChatApi.ChatEvent.Unknown -> {}
                }
            }
        }
    }

    private fun handleStreamingChunk(chunk: ChatApi.ChatEvent.Chunk) {
        val buffer = streamingMessages.getOrPut(chunk.messageId) { StringBuilder() }
        buffer.append(chunk.delta)

        val existing = _messages.value.indexOfFirst { it.id == chunk.messageId }
        if (existing >= 0) {
            updateMessage(chunk.messageId) {
                it.copy(content = buffer.toString(), isStreaming = !chunk.done)
            }
        } else {
            val streamingMsg = ChatMessage(
                id = chunk.messageId,
                role = ChatMessage.Role.ASSISTANT,
                content = buffer.toString(),
                isStreaming = !chunk.done,
            )
            _messages.value = _messages.value + streamingMsg
        }

        if (chunk.done) {
            streamingMessages.remove(chunk.messageId)
        }
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
