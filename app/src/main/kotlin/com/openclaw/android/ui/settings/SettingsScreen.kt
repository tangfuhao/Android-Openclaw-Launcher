package com.openclaw.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openclaw.android.ui.components.ModelConfigForm
import com.openclaw.android.ui.components.toFormState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onNavigateToTerminal: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val processState by viewModel.processState.collectAsStateWithLifecycle()
    val backgroundEnabled by viewModel.backgroundEnabled.collectAsStateWithLifecycle()
    val savedConfig by viewModel.modelConfig.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val saveMessage by viewModel.saveMessage.collectAsStateWithLifecycle()

    var formState by remember { mutableStateOf(savedConfig.toFormState()) }

    LaunchedEffect(savedConfig) {
        formState = savedConfig.toFormState()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        SectionHeader("Gateway")
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            ListItem(
                headlineContent = { Text("Status") },
                supportingContent = { Text(processState) },
                leadingContent = { Icon(Icons.Default.Memory, contentDescription = null) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Background Mode") },
                supportingContent = { Text("Keep OpenClaw running when app is in background") },
                leadingContent = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = backgroundEnabled,
                        onCheckedChange = { viewModel.setBackgroundEnabled(it) },
                    )
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Terminal") },
                supportingContent = { Text("Open a Linux shell session") },
                leadingContent = { Icon(Icons.Default.Terminal, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onNavigateToTerminal),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader("Model Config")
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            ModelConfigForm(
                state = formState,
                onStateChange = {
                    formState = it
                    viewModel.clearMessages()
                },
                testResult = testResult,
                isTesting = isTesting,
                onSave = { viewModel.saveModelConfig(formState) },
                onTest = { viewModel.testConnection(formState) },
                modifier = Modifier.padding(16.dp),
            )
        }

        if (saveMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = saveMessage!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader("Storage")
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            ListItem(
                headlineContent = { Text("Environment Size") },
                supportingContent = { Text("Linux environment and OpenClaw data") },
                leadingContent = { Icon(Icons.Default.Storage, contentDescription = null) },
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader("About")
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            ListItem(
                headlineContent = { Text("Android OpenClaw") },
                supportingContent = { Text("v0.1.0 — Open Source (GPLv3)") },
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
