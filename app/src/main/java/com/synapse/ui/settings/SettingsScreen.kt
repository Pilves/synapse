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
import androidx.compose.material.icons.automirrored.filled.Help
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.synapse.BuildConfig
import com.synapse.R
import com.synapse.api.LlmProvider
import com.synapse.ui.components.LlmSettingsSection

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
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back)
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
            SettingsSection(title = stringResource(R.string.settings_capture_header))

            var localChunkTimeout by remember(chunkTimeout) { mutableFloatStateOf(chunkTimeout) }
            SettingsSlider(
                label = stringResource(R.string.settings_chunk_timeout),
                value = localChunkTimeout,
                onValueChange = { localChunkTimeout = it },
                onValueChangeFinished = { viewModel.setChunkTimeout(localChunkTimeout) },
                valueRange = 1f..10f,
                steps = 8,
                valueFormatter = ::formatSeconds
            )

            var localFadeAnimation by remember(fadeAnimation) { mutableFloatStateOf(fadeAnimation) }
            SettingsSlider(
                label = stringResource(R.string.settings_fade_animation),
                value = localFadeAnimation,
                onValueChange = { localFadeAnimation = it },
                onValueChangeFinished = { viewModel.setFadeAnimation(localFadeAnimation) },
                valueRange = 0f..1f,
                steps = 9,
                valueFormatter = ::formatDecimalSeconds
            )

            SettingsDivider()

            // REVIEW Section
            SettingsSection(title = stringResource(R.string.settings_review_header))

            SettingsSegmentedToggle(
                label = stringResource(R.string.settings_default_view),
                options = listOf(stringResource(R.string.review_view_stitched), stringResource(R.string.review_view_separate)),
                selectedIndex = if (defaultViewStitched) 0 else 1,
                onSelectionChange = { viewModel.setDefaultViewStitched(it == 0) }
            )

            SettingsDivider()

            // TRANSCRIPTION Section
            SettingsSection(title = stringResource(R.string.settings_transcription_header))

            // Multi-provider LLM configuration section
            LlmSettingsSection(
                config = llmConfig,
                onConfigChange = { viewModel.setLlmConfig(it) },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            SettingsSwitch(
                label = stringResource(R.string.settings_cleanup_mode),
                checked = cleanupMode,
                onCheckedChange = { viewModel.setCleanupMode(it) },
                description = stringResource(R.string.settings_cleanup_mode_description)
            )

            SettingsSwitch(
                label = stringResource(R.string.settings_advanced_formatting),
                checked = advancedFormatting,
                onCheckedChange = { viewModel.setAdvancedFormatting(it) },
                description = stringResource(R.string.settings_advanced_formatting_description)
            )

            SettingsSegmentedToggle(
                label = stringResource(R.string.settings_rate_limiting),
                options = listOf(stringResource(R.string.settings_rate_safe), stringResource(R.string.settings_rate_fast)),
                selectedIndex = if (rateLimitingSafe) 0 else 1,
                onSelectionChange = { viewModel.setRateLimitingSafe(it == 0) }
            )

            NavigationRow(
                label = stringResource(R.string.settings_edit_prompt),
                onClick = onNavigateToPromptEditor
            )

            SettingsDivider()

            // VAULT Section
            SettingsSection(title = stringResource(R.string.settings_vault_header))

            ClickableSettingsRow(
                label = stringResource(R.string.settings_vault_location),
                value = vaultLocation.ifEmpty { stringResource(R.string.settings_vault_tap_to_select) },
                onClick = onSelectVaultLocation
            )

            NavigationRow(
                label = stringResource(R.string.settings_manage_projects),
                onClick = onNavigateToProjectManager
            )

            SettingsDivider()

            // ABOUT Section
            SettingsSection(title = stringResource(R.string.settings_about_header))

            LinkRow(
                label = stringResource(R.string.settings_how_to_use),
                icon = Icons.AutoMirrored.Filled.Help,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://github.com/Pilves/synapse#readme")
                    }
                    context.startActivity(intent)
                }
            )

            AboutRow(
                label = stringResource(R.string.settings_version),
                value = BuildConfig.VERSION_NAME
            )

            LinkRow(
                label = stringResource(R.string.settings_source_code),
                icon = Icons.Default.Code,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://github.com/Pilves/synapse")
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
                SettingsSection(title = stringResource(R.string.settings_capture_header))
                SettingsSlider(
                    label = stringResource(R.string.settings_chunk_timeout),
                    value = 3f,
                    onValueChange = {},
                    valueRange = 1f..10f,
                    valueFormatter = ::formatSeconds
                )
                SettingsSlider(
                    label = stringResource(R.string.settings_fade_animation),
                    value = 0.3f,
                    onValueChange = {},
                    valueRange = 0f..1f,
                    valueFormatter = ::formatDecimalSeconds
                )
                SettingsDivider()
                SettingsSection(title = stringResource(R.string.settings_review_header))
                SettingsSegmentedToggle(
                    label = stringResource(R.string.settings_default_view),
                    options = listOf(stringResource(R.string.review_view_stitched), stringResource(R.string.review_view_separate)),
                    selectedIndex = 0,
                    onSelectionChange = {}
                )
            }
        }
    }
}
