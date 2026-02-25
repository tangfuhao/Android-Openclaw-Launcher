package com.openclaw.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val processState by viewModel.processState.collectAsStateWithLifecycle()
    val backgroundEnabled by viewModel.backgroundEnabled.collectAsStateWithLifecycle()
    val anthropicKey by viewModel.anthropicKey.collectAsStateWithLifecycle()
    val openaiKey by viewModel.openaiKey.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Gateway Status ---
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
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- API Keys ---
        SectionHeader("API Keys")
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                ApiKeyField(
                    label = "Anthropic (Claude)",
                    value = anthropicKey,
                    onValueChange = { viewModel.setAnthropicKey(it) },
                )
                Spacer(modifier = Modifier.height(12.dp))
                ApiKeyField(
                    label = "OpenAI (GPT)",
                    value = openaiKey,
                    onValueChange = { viewModel.setOpenaiKey(it) },
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- Storage ---
        SectionHeader("Storage")
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            ListItem(
                headlineContent = { Text("Environment Size") },
                supportingContent = { Text("Linux environment and OpenClaw data") },
                leadingContent = { Icon(Icons.Default.Storage, contentDescription = null) },
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- About ---
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

@Composable
private fun ApiKeyField(label: String, value: String, onValueChange: (String) -> Unit) {
    var localValue by remember(value) { mutableStateOf(value) }

    OutlinedTextField(
        value = localValue,
        onValueChange = {
            localValue = it
            onValueChange(it)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
    )
}
