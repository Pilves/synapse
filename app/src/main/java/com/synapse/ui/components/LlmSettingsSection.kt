package com.synapse.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.synapse.model.LlmConfig

/**
 * Settings UI section for configuring multi-provider LLM settings.
 *
 * Allows users to select separate providers and API keys for transcription
 * and answering, with an optional toggle to enable a separate answering model.
 *
 * @param config The current LLM configuration
 * @param onConfigChange Callback invoked when any configuration value changes
 * @param modifier Optional modifier for the root layout
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmSettingsSection(
    config: LlmConfig,
    onConfigChange: (LlmConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            "TRANSCRIPTION",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        ProviderDropdown(
            selected = config.transcriptionProvider,
            onSelect = { provider ->
                onConfigChange(config.copy(transcriptionProvider = provider))
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = config.transcriptionApiKey,
            onValueChange = { key ->
                onConfigChange(config.copy(transcriptionApiKey = key))
            },
            label = { Text("API Key") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "ANSWERING (optional)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            "Use a different model for answering questions",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        var useSeparateAnswering by remember {
            mutableStateOf(config.answeringProvider != null)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Use separate answering model")
            Switch(
                checked = useSeparateAnswering,
                onCheckedChange = { enabled ->
                    useSeparateAnswering = enabled
                    if (!enabled) {
                        onConfigChange(
                            config.copy(
                                answeringProvider = null,
                                answeringApiKey = null
                            )
                        )
                    }
                }
            )
        }

        if (useSeparateAnswering) {
            Spacer(modifier = Modifier.height(8.dp))

            ProviderDropdown(
                selected = config.answeringProvider ?: "gemini",
                onSelect = { provider ->
                    onConfigChange(config.copy(answeringProvider = provider))
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = config.answeringApiKey ?: "",
                onValueChange = { key ->
                    onConfigChange(config.copy(answeringApiKey = key.ifBlank { null }))
                },
                label = { Text("Answering API Key") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

/**
 * Dropdown selector for choosing an LLM provider.
 *
 * @param selected The currently selected provider ID string
 * @param onSelect Callback invoked with the selected provider ID
 * @param modifier Optional modifier
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDropdown(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val providers = listOf(
        "gemini" to "Google Gemini (Free)",
        "claude" to "Anthropic Claude",
        "openai" to "OpenAI",
        "ollama" to "Ollama (Local)"
    )

    val displayName = providers.find { it.first == selected }?.second ?: selected

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayName,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            providers.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    }
                )
            }
        }
    }
}
