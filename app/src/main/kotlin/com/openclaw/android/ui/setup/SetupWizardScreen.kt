package com.openclaw.android.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openclaw.android.bootstrap.BootstrapState

@Composable
fun SetupWizardScreen(
    onSetupComplete: () -> Unit = {},
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val bootstrapState by viewModel.bootstrapState.collectAsStateWithLifecycle()
    val currentStep by viewModel.currentStep.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AnimatedContent(targetState = currentStep, label = "setup_step") { step ->
            when (step) {
                SetupStep.WELCOME -> WelcomePage(onNext = { viewModel.nextStep() })
                SetupStep.DEVICE_CHECK -> DeviceCheckPage(viewModel = viewModel)
                SetupStep.DOWNLOAD -> DownloadPage(bootstrapState = bootstrapState, viewModel = viewModel)
                SetupStep.API_KEY -> ApiKeyPage(viewModel = viewModel)
                SetupStep.COMPLETE -> CompletePage(onFinish = { viewModel.finishSetup() })
            }
        }
    }
}

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Text(text = "🦞", style = MaterialTheme.typography.displayLarge)
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Welcome to OpenClaw",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Your personal AI assistant, running entirely on your phone.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(48.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth(0.6f)) {
            Text("Get Started")
        }
    }
}

@Composable
private fun DeviceCheckPage(viewModel: SetupViewModel) {
    val deviceCheck by viewModel.deviceCheck.collectAsStateWithLifecycle()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Text(
            text = "Checking Your Device",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(24.dp))

        CheckItem("RAM: ${deviceCheck.totalRamMb}MB", deviceCheck.ramOk)
        CheckItem("Storage: ${deviceCheck.freeStorageMb}MB free", deviceCheck.storageOk)
        CheckItem("Network: ${if (deviceCheck.networkOk) "Connected" else "No connection"}", deviceCheck.networkOk)

        Spacer(modifier = Modifier.height(32.dp))

        if (deviceCheck.allOk) {
            Button(
                onClick = { viewModel.nextStep() },
                modifier = Modifier.fillMaxWidth(0.6f),
            ) {
                Text("Continue")
            }
        } else {
            Text(
                text = "Your device may not meet the minimum requirements.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = { viewModel.nextStep() },
                modifier = Modifier.fillMaxWidth(0.6f),
            ) {
                Text("Continue Anyway")
            }
        }
    }
}

@Composable
private fun CheckItem(text: String, ok: Boolean) {
    Text(
        text = "${if (ok) "✓" else "✗"} $text",
        style = MaterialTheme.typography.bodyLarge,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun DownloadPage(bootstrapState: BootstrapState, viewModel: SetupViewModel) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Text(
            text = "Installing Environment",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(24.dp))

        when (bootstrapState) {
            is BootstrapState.NotInstalled, is BootstrapState.Checking -> {
                Text("Ready to download the Linux runtime environment (~300MB)")
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { viewModel.startInstallation() }) {
                    Text("Download & Install")
                }
            }
            is BootstrapState.Downloading -> {
                Text("Downloading... ${(bootstrapState.progress * 100).toInt()}%")
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { bootstrapState.progress },
                    modifier = Modifier.fillMaxWidth(0.8f),
                )
                Spacer(modifier = Modifier.height(8.dp))
                val mbDownloaded = bootstrapState.bytesDownloaded / 1_048_576
                val mbTotal = bootstrapState.totalBytes / 1_048_576
                Text(
                    "${mbDownloaded}MB / ${mbTotal}MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is BootstrapState.Extracting -> {
                Text("Extracting files...")
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
            }
            is BootstrapState.Configuring -> {
                Text("Configuring environment...")
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
            }
            is BootstrapState.Installed -> {
                Text("Installation complete!", color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { viewModel.nextStep() }) {
                    Text("Continue")
                }
            }
            is BootstrapState.Error -> {
                Text(
                    "Installation failed: ${bootstrapState.message}",
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { viewModel.startInstallation() }) {
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
private fun ApiKeyPage(viewModel: SetupViewModel) {
    var apiKey by rememberSaveable { mutableStateOf("") }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Text(
            text = "Configure AI Provider",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Enter at least one API key to get started.\nYou can add more later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("Anthropic API Key (sk-ant-...)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = { viewModel.saveApiKey(apiKey) },
            modifier = Modifier.fillMaxWidth(0.6f),
            enabled = apiKey.isNotBlank(),
        ) {
            Text("Save & Continue")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = { viewModel.nextStep() },
            modifier = Modifier.fillMaxWidth(0.6f),
        ) {
            Text("Skip for Now")
        }
    }
}

@Composable
private fun CompletePage(onFinish: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Text(text = "🎉", style = MaterialTheme.typography.displayLarge)
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "You're All Set!",
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "OpenClaw is ready to assist you.\nStart a conversation in the Chat tab.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(48.dp))
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth(0.6f)) {
            Text("Start Chatting")
        }
    }
}

enum class SetupStep { WELCOME, DEVICE_CHECK, DOWNLOAD, API_KEY, COMPLETE }
