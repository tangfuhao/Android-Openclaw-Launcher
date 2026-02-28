package com.openclaw.android.ui.settings

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Link
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openclaw.android.data.PreferencesManager.ApiProvider
import com.openclaw.android.data.PreferencesManager.ApiType

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val processState by viewModel.processState.collectAsStateWithLifecycle()
    val backgroundEnabled by viewModel.backgroundEnabled.collectAsStateWithLifecycle()

    val anthropicKey by viewModel.anthropicKey.collectAsStateWithLifecycle()
    val openaiKey by viewModel.openaiKey.collectAsStateWithLifecycle()
    val openrouterKey by viewModel.openrouterKey.collectAsStateWithLifecycle()

    val anthropicBaseUrl by viewModel.anthropicBaseUrl.collectAsStateWithLifecycle()
    val openaiBaseUrl by viewModel.openaiBaseUrl.collectAsStateWithLifecycle()
    val openrouterBaseUrl by viewModel.openrouterBaseUrl.collectAsStateWithLifecycle()

    val anthropicApiType by viewModel.anthropicApiType.collectAsStateWithLifecycle()
    val openaiApiType by viewModel.openaiApiType.collectAsStateWithLifecycle()
    val openrouterApiType by viewModel.openrouterApiType.collectAsStateWithLifecycle()

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

        // --- API Providers ---
        SectionHeader("API Providers")

        ProviderCard(
            name = "Anthropic (Claude)",
            apiKey = anthropicKey,
            baseUrl = anthropicBaseUrl,
            apiType = anthropicApiType,
            defaultApiType = ApiType.ANTHROPIC_MESSAGES,
            onSave = { key, url, type ->
                viewModel.saveProviderConfig(ApiProvider.ANTHROPIC, key, url, type)
            },
        )

        Spacer(modifier = Modifier.height(12.dp))

        ProviderCard(
            name = "OpenAI (GPT)",
            apiKey = openaiKey,
            baseUrl = openaiBaseUrl,
            apiType = openaiApiType,
            defaultApiType = ApiType.OPENAI_COMPLETIONS,
            onSave = { key, url, type ->
                viewModel.saveProviderConfig(ApiProvider.OPENAI, key, url, type)
            },
        )

        Spacer(modifier = Modifier.height(12.dp))

        ProviderCard(
            name = "OpenRouter",
            apiKey = openrouterKey,
            baseUrl = openrouterBaseUrl,
            apiType = openrouterApiType,
            defaultApiType = ApiType.OPENAI_COMPLETIONS,
            onSave = { key, url, type ->
                viewModel.saveProviderConfig(ApiProvider.OPENROUTER, key, url, type)
            },
        )

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
private fun ProviderCard(
    name: String,
    apiKey: String,
    baseUrl: String,
    apiType: String,
    defaultApiType: String,
    onSave: (apiKey: String, baseUrl: String, apiType: String) -> Unit,
) {
    var localKey by remember(apiKey) { mutableStateOf(apiKey) }
    var localBaseUrl by remember(baseUrl) { mutableStateOf(baseUrl) }
    var localApiType by remember(apiType) { mutableStateOf(apiType) }
    var showAdvanced by rememberSaveable { mutableStateOf(baseUrl.isNotBlank()) }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = localKey,
                onValueChange = {
                    localKey = it
                    onSave(it, localBaseUrl, localApiType)
                },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
            )

            Spacer(modifier = Modifier.height(4.dp))

            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(
                    if (showAdvanced) "Hide Base URL" else "Custom Base URL",
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            AnimatedVisibility(visible = showAdvanced) {
                Column {
                    OutlinedTextField(
                        value = localBaseUrl,
                        onValueChange = {
                            localBaseUrl = it
                            onSave(localKey, it, localApiType)
                        },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://your-relay.example.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                    )

                    if (localBaseUrl.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ApiTypeSelector(
                            selectedType = localApiType.ifBlank { defaultApiType },
                            onTypeSelected = {
                                localApiType = it
                                onSave(localKey, localBaseUrl, it)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiTypeSelector(
    selectedType: String,
    onTypeSelected: (String) -> Unit,
) {
    val options = listOf(
        ApiType.ANTHROPIC_MESSAGES to "Anthropic Messages",
        ApiType.OPENAI_COMPLETIONS to "OpenAI Completions",
    )

    Column {
        Text(
            "API Compatibility",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        options.forEach { (value, label) ->
            val isSelected = selectedType == value
            TextButton(onClick = { onTypeSelected(value) }) {
                Text(
                    text = "${if (isSelected) "\u25C9" else "\u25CB"} $label",
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
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
