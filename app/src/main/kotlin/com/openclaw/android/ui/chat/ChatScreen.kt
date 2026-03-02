package com.openclaw.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openclaw.android.gateway.GatewayState
import com.openclaw.android.ui.components.ConnectionStatusBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val pendingApproval by viewModel.pendingApproval.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var inputText by rememberSaveable { mutableStateOf("") }
    var showCommandPalette by rememberSaveable { mutableStateOf(false) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var currentMatchIdx by rememberSaveable { mutableStateOf(0) }

    val searchMatches = remember(messages, searchQuery) { searchMessages(messages, searchQuery) }

    val lastMessage = messages.lastOrNull()
    val scrollTrigger = lastMessage?.let {
        "${it.id}-${it.textContent.length}-${it.toolActivities.size}-${it.runPhase}"
    }

    LaunchedEffect(messages.size, scrollTrigger) {
        if (messages.isNotEmpty() && !searchVisible) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(currentMatchIdx, searchMatches) {
        if (searchMatches.isNotEmpty() && currentMatchIdx in searchMatches.indices) {
            listState.animateScrollToItem(searchMatches[currentMatchIdx])
        }
    }

    LaunchedEffect(connectionState) {
        if (connectionState is GatewayState.Connected) {
            viewModel.loadHistory()
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        ConnectionStatusBar(connectionState)

        TopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        viewModel.sendMessage("/status")
                    },
                ) {
                    Text(
                        text = "OpenClaw",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ConnectionDot(connectionState)
                }
            },
            actions = {
                IconButton(onClick = { searchVisible = !searchVisible }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            },
        )

        ChatSearchBar(
            visible = searchVisible,
            query = searchQuery,
            onQueryChange = { searchQuery = it; currentMatchIdx = 0 },
            matchCount = searchMatches.size,
            currentMatch = currentMatchIdx,
            onPrevious = { if (searchMatches.isNotEmpty()) currentMatchIdx = (currentMatchIdx - 1 + searchMatches.size) % searchMatches.size },
            onNext = { if (searchMatches.isNotEmpty()) currentMatchIdx = (currentMatchIdx + 1) % searchMatches.size },
            onClose = { searchVisible = false; searchQuery = "" },
        )

        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty() && !isLoading) {
                EmptyState()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { Spacer(modifier = Modifier.height(8.dp)) }

                    items(messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            fileBridge = viewModel.fileBridge,
                            onRetry = { viewModel.retryMessage(it) },
                            onDelete = { viewModel.deleteMessage(it) },
                        )
                    }

                    if (isLoading) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
        }

        CommandSuggestions(
            inputText = inputText,
            visible = inputText.startsWith("/") && inputText.length < 20,
            onCommandSelected = { cmd ->
                if (cmd.hasArgs) {
                    inputText = cmd.name + " "
                } else {
                    viewModel.sendMessage(cmd.name)
                    inputText = ""
                }
            },
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(onClick = { showCommandPalette = true }) {
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                AttachmentPicker(
                    onImagePicked = { uri -> viewModel.sendImageAttachment(uri) },
                    onFilePicked = { uri -> viewModel.sendFileAttachment(uri) },
                )

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message OpenClaw...") },
                    maxLines = 5,
                    shape = MaterialTheme.shapes.large,
                    enabled = connectionState.isConnected,
                )
                Spacer(modifier = Modifier.width(4.dp))

                if (inputText.isNotBlank()) {
                    IconButton(
                        onClick = {
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        },
                        enabled = connectionState.isConnected,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (connectionState.isConnected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                } else {
                    VoiceRecordButton(
                        onRecordingComplete = { file ->
                            viewModel.sendVoiceMessage(file)
                        },
                    )
                }
            }
        }
    }

    CommandPalette(
        visible = showCommandPalette,
        onDismiss = { showCommandPalette = false },
        onCommandSelected = { cmd ->
            showCommandPalette = false
            if (cmd.hasArgs) {
                inputText = cmd.name + " "
            } else {
                viewModel.sendMessage(cmd.name)
            }
        },
    )

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
private fun ConnectionDot(state: GatewayState) {
    val color = when (state) {
        is GatewayState.Connected -> Color(0xFF4CAF50)
        is GatewayState.Connecting, is GatewayState.Handshaking, is GatewayState.Reconnecting -> Color(0xFFFFC107)
        else -> Color(0xFFE53935)
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "\uD83E\uDD9E",
                style = MaterialTheme.typography.displayLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Welcome to OpenClaw",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Send a message to get started",
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
        title = { Text("Tool Approval Required") },
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
            TextButton(onClick = onApprove) { Text("Approve") }
        },
        dismissButton = {
            TextButton(onClick = onDeny) { Text("Deny") }
        },
    )
}
