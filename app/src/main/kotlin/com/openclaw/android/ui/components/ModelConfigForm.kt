package com.openclaw.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openclaw.android.data.ModelApiType
import com.openclaw.android.data.ModelConfig
import com.openclaw.android.data.ModelProviderEntry

data class ModelConfigFormState(
    val primaryModel: String = "",
    val providers: List<ModelProviderEntry> = listOf(ModelProviderEntry()),
)

fun ModelConfigFormState.toModelConfig(): ModelConfig =
    ModelConfig(primaryModel = primaryModel.trim(), providers = providers)

fun ModelConfig.toFormState(): ModelConfigFormState =
    ModelConfigFormState(
        primaryModel = primaryModel,
        providers = providers.ifEmpty { listOf(ModelProviderEntry()) },
    )

@Composable
fun ModelConfigForm(
    state: ModelConfigFormState,
    onStateChange: (ModelConfigFormState) -> Unit,
    testResult: String?,
    isTesting: Boolean,
    onSave: () -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
    showSaveHint: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.primaryModel,
            onValueChange = { onStateChange(state.copy(primaryModel = it)) },
            label = { Text("Primary Model") },
            placeholder = { Text("provider/model-id") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Providers",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))

        state.providers.forEachIndexed { index, entry ->
            ProviderEntryCard(
                entry = entry,
                index = index,
                canRemove = state.providers.size > 1,
                onChange = { updated ->
                    val newList = state.providers.toMutableList()
                    newList[index] = updated
                    onStateChange(state.copy(providers = newList))
                },
                onRemove = {
                    val newList = state.providers.toMutableList()
                    newList.removeAt(index)
                    onStateChange(state.copy(providers = newList))
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        OutlinedButton(
            onClick = {
                onStateChange(state.copy(providers = state.providers + ModelProviderEntry()))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("Add Provider", modifier = Modifier.padding(start = 8.dp))
        }

        if (testResult != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = testResult,
                style = MaterialTheme.typography.bodySmall,
                color = if (testResult.startsWith("Connected")) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        if (showSaveHint) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Save writes openclaw.json. Restart Gateway for env changes to take effect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "API reachable does not guarantee Agent tool support.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onTest,
                enabled = !isTesting,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (isTesting) "Testing…" else "Test Connection")
            }
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
            ) {
                Text("Save")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEntryCard(
    entry: ModelProviderEntry,
    index: Int,
    canRemove: Boolean,
    onChange: (ModelProviderEntry) -> Unit,
    onRemove: () -> Unit,
) {
    var apiTypeExpanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Provider #${index + 1}", style = MaterialTheme.typography.titleSmall)
                if (canRemove) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove provider")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = entry.providerId,
                onValueChange = { onChange(entry.copy(providerId = it)) },
                label = { Text("Provider ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = entry.baseUrl,
                onValueChange = { onChange(entry.copy(baseUrl = it)) },
                label = { Text("Base URL") },
                placeholder = { Text("Optional for built-in providers") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = entry.apiKey,
                onValueChange = { onChange(entry.copy(apiKey = it)) },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = apiTypeExpanded,
                onExpandedChange = { apiTypeExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = entry.apiType,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("API Type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(apiTypeExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = apiTypeExpanded,
                    onDismissRequest = { apiTypeExpanded = false },
                ) {
                    ModelApiType.ALL.forEach { apiType ->
                        DropdownMenuItem(
                            text = { Text(apiType) },
                            onClick = {
                                onChange(entry.copy(apiType = apiType))
                                apiTypeExpanded = false
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = entry.modelId,
                onValueChange = { onChange(entry.copy(modelId = it)) },
                label = { Text("Model ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}
