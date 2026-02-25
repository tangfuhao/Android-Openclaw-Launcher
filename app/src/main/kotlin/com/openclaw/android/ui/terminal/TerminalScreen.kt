package com.openclaw.android.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

/**
 * Terminal tab providing direct shell access to the embedded Linux environment.
 * Wraps the real Termux TerminalView (PTY-backed) inside a Compose layout.
 */
@Composable
fun TerminalScreen(viewModel: TerminalViewModel = hiltViewModel()) {
    val bootstrapInstalled by viewModel.bootstrapInstalled.collectAsStateWithLifecycle()
    val sessionTitle by viewModel.sessionTitle.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = sessionTitle,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        if (!bootstrapInstalled) {
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
