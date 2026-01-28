package com.synapse.ui.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synapse.ui.theme.SynapseTheme
import kotlin.math.roundToInt

/**
 * A settings slider component with a label and value display.
 *
 * @param label The label text for the setting
 * @param value Current value of the slider
 * @param onValueChange Callback when the value changes
 * @param valueRange The range of values the slider can have
 * @param steps Number of discrete steps (0 for continuous)
 * @param valueFormatter Function to format the value for display
 * @param modifier Modifier for the component
 * @param enabled Whether the slider is enabled
 */
@Composable
fun SettingsSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    valueFormatter: (Float) -> String = { it.toString() },
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Text(
                text = valueFormatter(value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                disabledActiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                disabledInactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Convenience function to format seconds.
 */
fun formatSeconds(value: Float): String {
    return "${value.roundToInt()}s"
}

/**
 * Convenience function to format minutes.
 */
fun formatMinutes(value: Float): String {
    return "${value.roundToInt()}m"
}

/**
 * Convenience function to format decimal seconds.
 */
fun formatDecimalSeconds(value: Float): String {
    return String.format("%.1fs", value)
}

@Preview(showBackground = true)
@Composable
private fun SettingsSliderPreview() {
    SynapseTheme {
        Surface {
            var value by remember { mutableFloatStateOf(3f) }
            SettingsSlider(
                label = "Chunk timeout",
                value = value,
                onValueChange = { value = it },
                valueRange = 1f..10f,
                steps = 8,
                valueFormatter = ::formatSeconds
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsSliderDisabledPreview() {
    SynapseTheme {
        Surface {
            SettingsSlider(
                label = "Disabled Slider",
                value = 5f,
                onValueChange = {},
                valueRange = 1f..10f,
                enabled = false,
                valueFormatter = ::formatSeconds
            )
        }
    }
}
