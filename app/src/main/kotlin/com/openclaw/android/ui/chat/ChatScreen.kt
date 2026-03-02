package com.openclaw.android.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
    var showCommandPalette by rememberSaveable { mutableStateOf(false) }
    var showActionSheet by rememberSaveable { mutableStateOf(false) }

    val lastMessage = messages.lastOrNull()
    val scrollTrigger = lastMessage?.let {
        "${it.id}-${it.textContent.length}-${it.toolActivities.size}-${it.runPhase}"
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
                    EmptyState(
                        onSuggestionClick = { suggestion ->
                            viewModel.sendMessage(suggestion)
                        },
                    )
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

            HorizontalDivider()

            ChatInputBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                onSend = {
                    viewModel.sendMessage(inputText)
                    inputText = ""
                },
                onPlusClick = { showActionSheet = true },
                onVoiceComplete = { file -> viewModel.sendVoiceMessage(file) },
                enabled = connectionState.isConnected,
            )
        }
    }

    if (showActionSheet) {
        ActionSheet(
            onDismiss = { showActionSheet = false },
            onCommandsClick = {
                showActionSheet = false
                showCommandPalette = true
            },
            onImagePicked = { uri ->
                showActionSheet = false
                viewModel.sendImageAttachment(uri)
            },
            onFilePicked = { uri ->
                showActionSheet = false
                viewModel.sendFileAttachment(uri)
            },
        )
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

// ── Input bar ───────────────────────────────────────────────────────

@Composable
private fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onPlusClick: () -> Unit,
    onVoiceComplete: (java.io.File) -> Unit,
    enabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = onPlusClick) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "More actions",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

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
                    onSend = { if (inputText.isNotBlank()) onSend() },
                ),
            )

            Spacer(modifier = Modifier.width(8.dp))

            AnimatedContent(
                targetState = inputText.isNotBlank(),
                label = "sendVoiceToggle",
            ) { hasText ->
                if (hasText) {
                    IconButton(
                        onClick = onSend,
                        enabled = enabled,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                } else {
                    VoiceRecordButton(onRecordingComplete = onVoiceComplete)
                }
            }
        }
    }
}

// ── Action sheet (+ button) ─────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionSheet(
    onDismiss: () -> Unit,
    onCommandsClick: () -> Unit,
    onImagePicked: (Uri) -> Unit,
    onFilePicked: (Uri) -> Unit,
) {
    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let { onImagePicked(it) } ?: onDismiss() }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let { onFilePicked(it) } ?: onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 24.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Text(
                text = "Actions",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            ActionSheetItem(
                icon = Icons.Default.Terminal,
                label = "Commands",
                subtitle = "Slash commands & quick actions",
                onClick = onCommandsClick,
            )
            ActionSheetItem(
                icon = Icons.Default.Image,
                label = "Photo / Image",
                subtitle = "Send an image",
                onClick = { imageLauncher.launch("image/*") },
            )
            ActionSheetItem(
                icon = Icons.Default.AttachFile,
                label = "File",
                subtitle = "Send a file attachment",
                onClick = { fileLauncher.launch("*/*") },
            )
        }
    }
}

@Composable
private fun ActionSheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Empty state ─────────────────────────────────────────────────────

@Composable
private fun EmptyState(onSuggestionClick: (String) -> Unit = {}) {
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
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SuggestionChip("Help me code", onSuggestionClick)
                SuggestionChip("Analyze a file", onSuggestionClick)
            }
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: (String) -> Unit) {
    androidx.compose.material3.SuggestionChip(
        onClick = { onClick(text) },
        label = { Text(text) },
    )
}

// ── Approval dialog ─────────────────────────────────────────────────

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
