package com.openclaw.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openclaw.android.gateway.GatewayState
import com.openclaw.android.service.ProcessManager
import com.openclaw.android.ui.components.ServiceStartupView
import com.openclaw.android.ui.components.StatusDot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val processState by viewModel.processState.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val pendingApproval by viewModel.pendingApproval.collectAsStateWithLifecycle()
    val activeRunId by viewModel.activeRunId.collectAsStateWithLifecycle()

    val isReady = processState is ProcessManager.ProcessState.Running &&
        connectionState.isConnected

    val listState = rememberLazyListState()
    var inputText by rememberSaveable { mutableStateOf("") }

    val lastMessage = messages.lastOrNull()
    val scrollTrigger = lastMessage?.let {
        "${it.id}-${it.textContent.length}-${it.runPhase}-${it.status}-${it.isStreaming}"
    }

    LaunchedEffect(messages.size, scrollTrigger) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(connectionState) {
        if (connectionState is GatewayState.Connected) {
            viewModel.loadHistory()
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        connectionState = connectionState,
                        activeRunId = activeRunId,
                        size = 10.dp,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "OpenClaw",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            },
            actions = {
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            },
        )

        if (!isReady) {
            ServiceStartupView(
                processState = processState,
                gatewayState = connectionState,
                modifier = Modifier
                    .weight(1f)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            )
        } else {
            Box(modifier = Modifier.weight(1f)) {
                if (messages.isEmpty() && !isLoading) {
                    EmptyState()
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item { Spacer(modifier = Modifier.height(8.dp)) }

                        items(messages, key = { it.id }) { message ->
                            MessageBubble(message = message)
                        }

                        if (isLoading) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }

                        item { Spacer(modifier = Modifier.height(8.dp)) }
                    }
                }
            }

            HorizontalDivider()

            ChatInputBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                onSend = {
                    viewModel.sendMessage(inputText)
                    inputText = ""
                },
                enabled = connectionState.isConnected,
            )
        }
    }

    pendingApproval?.let { approval ->
        ApprovalDialog(
            command = approval.command,
            description = approval.displayDescription,
            onApprove = { viewModel.resolveApproval(approval.requestId, true) },
            onDeny = { viewModel.resolveApproval(approval.requestId, false) },
        )
    }
}

@Composable
private fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
) {
    val canSend = enabled && inputText.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message OpenClaw...") },
            maxLines = 5,
            shape = MaterialTheme.shapes.extraLarge,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = { if (canSend) onSend() },
            ),
        )

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = onSend,
            enabled = canSend,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (canSend) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "OpenClaw",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Send a text message to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ApprovalDialog(
    command: String,
    description: String,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text("Approval Required") },
        text = {
            Column {
                Text(
                    text = command,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = description, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onApprove) { Text("Allow") }
        },
        dismissButton = {
            TextButton(onClick = onDeny) { Text("Deny") }
        },
    )
}
