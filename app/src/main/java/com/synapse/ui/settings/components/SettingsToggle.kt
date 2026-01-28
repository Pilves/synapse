package com.synapse.ui.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synapse.ui.theme.SynapseTheme

/**
 * A settings switch component for boolean options.
 *
 * @param label The label text for the setting
 * @param checked Current checked state
 * @param onCheckedChange Callback when the state changes
 * @param modifier Modifier for the component
 * @param description Optional description text
 * @param enabled Whether the switch is enabled
 */
@Composable
fun SettingsSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null, // Handled by row click
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

/**
 * A segmented button toggle for two-option settings.
 *
 * @param label The label text for the setting
 * @param options List of two option labels
 * @param selectedIndex The currently selected index (0 or 1)
 * @param onSelectionChange Callback when selection changes
 * @param modifier Modifier for the component
 * @param enabled Whether the toggle is enabled
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSegmentedToggle(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelectionChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    require(options.size == 2) { "SettingsSegmentedToggle requires exactly 2 options" }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier.padding(bottom = 8.dp)
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    ),
                    onClick = { onSelectionChange(index) },
                    selected = index == selectedIndex,
                    enabled = enabled,
                    label = {
                        Text(
                            text = option,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsSwitchPreview() {
    SynapseTheme {
        Surface {
            var checked by remember { mutableStateOf(true) }
            SettingsSwitch(
                label = "Cleanup mode",
                checked = checked,
                onCheckedChange = { checked = it },
                description = "Fix spelling and grammar errors"
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsSwitchDisabledPreview() {
    SynapseTheme {
        Surface {
            SettingsSwitch(
                label = "Disabled switch",
                checked = false,
                onCheckedChange = {},
                enabled = false
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsSegmentedTogglePreview() {
    SynapseTheme {
        Surface {
            var selected by remember { mutableIntStateOf(0) }
            SettingsSegmentedToggle(
                label = "Default view",
                options = listOf("Stitched", "Separate"),
                selectedIndex = selected,
                onSelectionChange = { selected = it }
            )
        }
    }
}
