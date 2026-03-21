package com.openclaw.android.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.openclaw.android.data.ChatMessage
import com.openclaw.android.data.RunPhase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatMessage.Role.USER
    var showMenu by remember { mutableStateOf(false) }

    val maxWidth = LocalConfiguration.current.screenWidthDp.dp * 0.84f
    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp)
    }
    val bubbleColor = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isSystemInDarkTheme() -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Surface(
                shape = bubbleShape,
                color = bubbleColor,
                contentColor = contentColor,
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { if (message.textContent.isNotBlank()) showMenu = true },
                    ),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (!isUser && message.runPhase == RunPhase.THINKING && message.textContent.isBlank()) {
                        ThinkingIndicator()
                    }

                    if (message.textContent.isNotBlank()) {
                        if (isUser) {
                            Text(
                                text = message.textContent,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        } else {
                            Markdown(
                                content = message.textContent,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    if (!isUser && message.isStreaming && message.runPhase != RunPhase.THINKING) {
                        Spacer(modifier = Modifier.height(6.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    FooterRow(message = message, isUser = isUser)
                }
            }

            MessageContextMenu(
                expanded = showMenu,
                textContent = message.textContent,
                onDismiss = { showMenu = false },
            )
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = "Thinking...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FooterRow(message: ChatMessage, isUser: Boolean) {
    val statusLabel = when (message.status) {
        ChatMessage.Status.SENDING -> "Sending"
        ChatMessage.Status.ERROR -> "Failed"
        ChatMessage.Status.SENT -> null
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (statusLabel != null && isUser) {
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (message.status == ChatMessage.Status.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.End,
            )
            Spacer(modifier = Modifier.size(6.dp))
        }

        Text(
            text = formatTimestamp(message.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.End,
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
