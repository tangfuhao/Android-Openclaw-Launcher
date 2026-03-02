package com.openclaw.android.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.view.TerminalView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    onBack: () -> Unit = {},
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val rootfsInstalled by viewModel.rootfsInstalled.collectAsStateWithLifecycle()
    val sessionTitle by viewModel.sessionTitle.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(text = sessionTitle) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        if (!rootfsInstalled) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Linux environment not installed yet.\nComplete the setup first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            EmbeddedTerminalView(viewModel)
        }
    }
}

/**
 * Embeds the real Termux TerminalView via AndroidView interop.
 * The TerminalView is backed by a real PTY pseudo-terminal session
 * running bash inside the app's embedded Linux filesystem.
 */
@Composable
private fun EmbeddedTerminalView(viewModel: TerminalViewModel) {
    val context = LocalContext.current
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose { viewModel.detachView() }
    }

    AndroidView(
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                viewModel.attachView(this)
            }
        },
        update = { view ->
            view.setTextSize(fontSize)
        },
        modifier = Modifier.fillMaxSize(),
    )
}
