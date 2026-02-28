package com.openclaw.android.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.openclaw.android.data.PreferencesManager.ApiProvider
import com.openclaw.android.data.PreferencesManager.ApiType
import com.openclaw.android.proot.RootfsState

@Composable
fun SetupWizardScreen(
    onSetupComplete: () -> Unit = {},
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val rootfsState by viewModel.rootfsState.collectAsStateWithLifecycle()
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
                SetupStep.DOWNLOAD -> DownloadPage(rootfsState = rootfsState, viewModel = viewModel)
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
        Text(text = "\uD83E\uDD9E", style = MaterialTheme.typography.displayLarge)
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
        text = "${if (ok) "\u2713" else "\u2717"} $text",
        style = MaterialTheme.typography.bodyLarge,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun DownloadPage(rootfsState: RootfsState, viewModel: SetupViewModel) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Text(
            text = "Installing Linux Environment",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(24.dp))

        when (rootfsState) {
            is RootfsState.NotInstalled, is RootfsState.Checking -> {
                Text("Ready to download the Debian Linux environment (~200MB)")
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { viewModel.startInstallation() }) {
                    Text("Download & Install")
                }
            }
            is RootfsState.Downloading -> {
                Text("Downloading... ${(rootfsState.progress * 100).toInt()}%")
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { rootfsState.progress },
                    modifier = Modifier.fillMaxWidth(0.8f),
                )
                Spacer(modifier = Modifier.height(8.dp))
                val mbDownloaded = rootfsState.bytesDownloaded / 1_048_576
                val mbTotal = rootfsState.totalBytes / 1_048_576
                Text(
                    "${mbDownloaded}MB / ${mbTotal}MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is RootfsState.Extracting -> {
                Text("Extracting Debian filesystem...")
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
            }
            is RootfsState.Configuring -> {
                Text("Configuring environment...")
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
            }
            is RootfsState.Verifying -> {
                Text("Verifying Linux environment...")
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
            }
            is RootfsState.Installed -> {
                Text("Installation complete!", color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { viewModel.nextStep() }) {
                    Text("Continue")
                }
            }
            is RootfsState.Error -> {
                Text(
                    "Installation failed: ${rootfsState.message}",
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

private val SETUP_PROVIDERS = listOf(ApiProvider.ANTHROPIC, ApiProvider.OPENAI, ApiProvider.OPENROUTER)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeyPage(viewModel: SetupViewModel) {
    var selectedProvider by rememberSaveable { mutableStateOf(ApiProvider.ANTHROPIC.name) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var apiType by rememberSaveable { mutableStateOf("") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    val provider = ApiProvider.valueOf(selectedProvider)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 48.dp),
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
        Spacer(modifier = Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SETUP_PROVIDERS.forEach { p ->
                FilterChip(
                    selected = provider == p,
                    onClick = {
                        selectedProvider = p.name
                        apiKey = ""
                        baseUrl = ""
                        apiType = ""
                        showAdvanced = false
                    },
                    label = { Text(p.displayName) },
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            placeholder = { Text(providerKeyHint(provider)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "Hide Advanced" else "Advanced (Custom Base URL)")
        }

        AnimatedVisibility(visible = showAdvanced) {
            Column {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://your-relay.example.com/v1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(12.dp))

                ApiTypeDropdown(
                    selectedType = apiType.ifBlank { defaultApiType(provider) },
                    onTypeSelected = { apiType = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                viewModel.saveProviderConfig(
                    provider = provider,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    apiType = if (showAdvanced && baseUrl.isNotBlank()) {
                        apiType.ifBlank { defaultApiType(provider) }
                    } else "",
                )
            },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiTypeDropdown(
    selectedType: String,
    onTypeSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val options = listOf(
        ApiType.ANTHROPIC_MESSAGES to "Anthropic Messages",
        ApiType.OPENAI_COMPLETIONS to "OpenAI Completions",
    )
    val displayText = options.firstOrNull { it.first == selectedType }?.second ?: selectedType

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text("API Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onTypeSelected(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun providerKeyHint(provider: ApiProvider): String = when (provider) {
    ApiProvider.ANTHROPIC -> "sk-ant-..."
    ApiProvider.OPENAI -> "sk-..."
    ApiProvider.OPENROUTER -> "sk-or-..."
    ApiProvider.GOOGLE -> "AIza..."
}

private fun defaultApiType(provider: ApiProvider): String = when (provider) {
    ApiProvider.ANTHROPIC -> ApiType.ANTHROPIC_MESSAGES
    else -> ApiType.OPENAI_COMPLETIONS
}

@Composable
private fun CompletePage(onFinish: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Text(text = "\uD83C\uDF89", style = MaterialTheme.typography.displayLarge)
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
