package com.openclaw.android.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.openclaw.android.data.ChatMessage

@Composable
fun MessageContextMenu(
    expanded: Boolean,
    message: ChatMessage,
    onDismiss: () -> Unit,
    onRetry: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val textContent = message.textContent

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        if (textContent.isNotBlank()) {
            DropdownMenuItem(
                text = { Text("Copy") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("message", textContent))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
            )
        }

        if (textContent.isNotBlank()) {
            DropdownMenuItem(
                text = { Text("Share") },
                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, textContent)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share"))
                    onDismiss()
                },
            )
        }

        if (message.role == ChatMessage.Role.USER && onRetry != null) {
            DropdownMenuItem(
                text = { Text("Retry") },
                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                onClick = {
                    onRetry(message.id)
                    onDismiss()
                },
            )
        }

        if (onDelete != null) {
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    onDelete(message.id)
                    onDismiss()
                },
            )
        }
    }
}
