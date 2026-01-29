package com.synapse.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Help
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synapse.api.LlmProvider
import com.synapse.ui.components.LlmSettingsSection
import com.synapse.ui.overlay.InputMode
import com.synapse.ui.settings.components.SettingsDropdown
import com.synapse.ui.settings.components.SettingsSegmentedToggle
import com.synapse.ui.settings.components.SettingsSlider
import com.synapse.ui.settings.components.SettingsSwitch
import com.synapse.ui.settings.components.SettingsTextField
import com.synapse.ui.settings.components.formatDecimalSeconds
import com.synapse.ui.settings.components.formatMinutes
import com.synapse.ui.settings.components.formatSeconds
import com.synapse.ui.theme.SynapseTheme
import kotlin.math.roundToInt

/**
 * Main settings screen composable.
 *
 * @param viewModel The settings ViewModel
 * @param onNavigateBack Callback to navigate back
 * @param onNavigateToPromptEditor Callback to navigate to prompt editor
 * @param onNavigateToProjectManager Callback to navigate to project manager
 * @param onSelectVaultLocation Callback to open folder picker for vault location
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToPromptEditor: () -> Unit,
    onNavigateToProjectManager: () -> Unit,
    onSelectVaultLocation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Collect all settings states
    val chunkTimeout by viewModel.chunkTimeout.collectAsState()
    val fadeAnimation by viewModel.fadeAnimation.collectAsState()
    val sessionAutoEnd by viewModel.sessionAutoEnd.collectAsState()
    val inputMode by viewModel.inputMode.collectAsState()
    val defaultViewStitched by viewModel.defaultViewStitched.collectAsState()
    val llmProvider by viewModel.llmProvider.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val llmConfig by viewModel.llmConfig.collectAsState()
    val cleanupMode by viewModel.cleanupMode.collectAsState()
    val advancedFormatting by viewModel.advancedFormatting.collectAsState()
    val rateLimitingSafe by viewModel.rateLimitingSafe.collectAsState()
    val vaultLocation by viewModel.vaultLocation.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
        ) {
            // CAPTURE Section
            SettingsSection(title = "CAPTURE")

            SettingsSlider(
                label = "Chunk timeout",
                value = chunkTimeout,
                onValueChange = { viewModel.setChunkTimeout(it) },
                valueRange = 1f..10f,
                steps = 8,
                valueFormatter = ::formatSeconds
            )

            SettingsSlider(
                label = "Fade animation",
                value = fadeAnimation,
                onValueChange = { viewModel.setFadeAnimation(it) },
                valueRange = 0f..1f,
                steps = 9,
                valueFormatter = ::formatDecimalSeconds
            )

            SettingsSlider(
                label = "Session auto-end",
                value = sessionAutoEnd.toFloat(),
                onValueChange = { viewModel.setSessionAutoEnd(it.roundToInt()) },
                valueRange = 5f..60f,
                steps = 10,
                valueFormatter = ::formatMinutes
            )

            SettingsDropdown(
                label = "Input mode",
                options = InputMode.entries,
                selectedOption = inputMode,
                onOptionSelected = { viewModel.setInputMode(it) },
                optionLabel = { mode ->
                    when (mode) {
                        InputMode.STYLUS_WRITE_FINGER_SCROLL -> "Stylus writes, finger scrolls"
                        InputMode.BOTH_WRITE -> "Both stylus and finger write"
                        InputMode.STYLUS_ONLY -> "Stylus only"
                    }
                }
            )

            SettingsDivider()

            // REVIEW Section
            SettingsSection(title = "REVIEW")

            SettingsSegmentedToggle(
                label = "Default view",
                options = listOf("Stitched", "Separate"),
                selectedIndex = if (defaultViewStitched) 0 else 1,
                onSelectionChange = { viewModel.setDefaultViewStitched(it == 0) }
            )

            SettingsDivider()

            // TRANSCRIPTION Section
            SettingsSection(title = "TRANSCRIPTION")

            SettingsDropdown(
                label = "LLM Provider",
                options = LlmProvider.entries,
                selectedOption = llmProvider,
                onOptionSelected = { viewModel.setLlmProvider(it) },
                optionLabel = { it.displayName }
            )

            SettingsTextField(
                label = "API Key",
                value = apiKey,
                onValueChange = { viewModel.setApiKey(it) },
                placeholder = if (llmProvider == LlmProvider.OLLAMA) {
                    "Not required for Ollama"
                } else {
                    "Enter your ${llmProvider.displayName} API key"
                },
                isMasked = true,
                enabled = llmProvider.requiresApiKey,
                isError = uiState.apiKeyError != null,
                errorMessage = uiState.apiKeyError
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Multi-provider LLM configuration section
            LlmSettingsSection(
                config = llmConfig,
                onConfigChange = { viewModel.setLlmConfig(it) },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            SettingsSwitch(
                label = "Cleanup mode",
                checked = cleanupMode,
                onCheckedChange = { viewModel.setCleanupMode(it) },
                description = "Fix spelling and grammar errors"
            )

            SettingsSwitch(
                label = "Advanced formatting",
                checked = advancedFormatting,
                onCheckedChange = { viewModel.setAdvancedFormatting(it) },
                description = "Convert diagrams to Mermaid, equations to LaTeX"
            )

            SettingsSegmentedToggle(
                label = "Rate limiting",
                options = listOf("Safe", "Fast"),
                selectedIndex = if (rateLimitingSafe) 0 else 1,
                onSelectionChange = { viewModel.setRateLimitingSafe(it == 0) }
            )

            NavigationRow(
                label = "Edit prompt template",
                onClick = onNavigateToPromptEditor
            )

            SettingsDivider()

            // VAULT Section
            SettingsSection(title = "VAULT")

            ClickableSettingsRow(
                label = "Vault location",
                value = vaultLocation.ifEmpty { "Tap to select folder" },
                onClick = onSelectVaultLocation
            )

            NavigationRow(
                label = "Manage projects",
                onClick = onNavigateToProjectManager
            )

            SettingsDivider()

            // ABOUT Section
            SettingsSection(title = "ABOUT")

            LinkRow(
                label = "How to use",
                icon = Icons.Default.Help,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://github.com/synapse-app/synapse/wiki")
                    }
                    context.startActivity(intent)
                }
            )

            AboutRow(
                label = "Version",
                value = "1.0.0"
            )

            LinkRow(
                label = "Source code",
                icon = Icons.Default.Code,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://github.com/synapse-app/synapse")
                    }
                    context.startActivity(intent)
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Section header for grouping settings.
 */
@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

/**
 * Divider between sections.
 */
@Composable
private fun SettingsDivider(
    modifier: Modifier = Modifier
) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * Clickable row for settings that open a picker or action.
 */
@Composable
private fun ClickableSettingsRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Navigation row for settings that navigate to another screen.
 */
@Composable
private fun NavigationRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Row for external links.
 */
@Composable
private fun LinkRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Static about row with label and value.
 */
@Composable
private fun AboutRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    SynapseTheme {
        Surface {
            // Preview without ViewModel - showing static layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                SettingsSection(title = "CAPTURE")
                SettingsSlider(
                    label = "Chunk timeout",
                    value = 3f,
                    onValueChange = {},
                    valueRange = 1f..10f,
                    valueFormatter = ::formatSeconds
                )
                SettingsSlider(
                    label = "Fade animation",
                    value = 0.3f,
                    onValueChange = {},
                    valueRange = 0f..1f,
                    valueFormatter = ::formatDecimalSeconds
                )
                SettingsDivider()
                SettingsSection(title = "REVIEW")
                SettingsSegmentedToggle(
                    label = "Default view",
                    options = listOf("Stitched", "Separate"),
                    selectedIndex = 0,
                    onSelectionChange = {}
                )
            }
        }
    }
}
