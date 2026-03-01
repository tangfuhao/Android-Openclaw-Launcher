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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.openclaw.android.data.PreferencesManager.ModelOption

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val processState by viewModel.processState.collectAsStateWithLifecycle()
    val backgroundEnabled by viewModel.backgroundEnabled.collectAsStateWithLifecycle()
    val apiKeys by viewModel.apiKeys.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()

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

        // --- Model Selection ---
        SectionHeader("Model")
        ModelSelector(
            selectedModel = selectedModel,
            apiKeys = apiKeys,
            onModelSelected = { viewModel.setSelectedModel(it) },
        )

        Spacer(modifier = Modifier.height(24.dp))

        // --- API Providers ---
        SectionHeader("API Providers")

        ApiProvider.entries.forEachIndexed { index, provider ->
            if (index > 0) Spacer(modifier = Modifier.height(12.dp))
            ProviderCard(
                name = provider.displayName,
                apiKey = apiKeys[provider] ?: "",
                onSave = { key -> viewModel.saveProviderConfig(provider, key) },
            )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelector(
    selectedModel: String,
    apiKeys: Map<ApiProvider, String>,
    onModelSelected: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    val configuredProviders = ApiProvider.entries.filter { (apiKeys[it] ?: "").isNotBlank() }
    val allModels: List<Pair<ApiProvider, ModelOption>> = configuredProviders.flatMap { provider ->
        provider.availableModels.map { model -> provider to model }
    }

    val displayText = allModels
        .firstOrNull { it.second.id == selectedModel }
        ?.let { (provider, model) -> "${provider.displayName} / ${model.displayName}" }
        ?: if (selectedModel.isNotBlank()) selectedModel else "Not configured"

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = displayText,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Active Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    if (allModels.isEmpty()) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Please configure an API key first",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            onClick = { expanded = false },
                        )
                    } else {
                        allModels.forEach { (provider, model) ->
                            val isSelected = model.id == selectedModel
                            DropdownMenuItem(
                                text = { Text("${provider.displayName} / ${model.displayName}") },
                                onClick = {
                                    onModelSelected(model.id)
                                    expanded = false
                                },
                                trailingIcon = if (isSelected) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null,
                            )
                        }
                    }
                }
            }

            if (configuredProviders.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Fill in at least one API key below to select a model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(
    name: String,
    apiKey: String,
    onSave: (apiKey: String) -> Unit,
) {
    var localKey by remember(apiKey) { mutableStateOf(apiKey) }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = localKey,
                onValueChange = {
                    localKey = it
                    onSave(it)
                },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
            )
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
