package com.synapse.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.synapse.model.Destination

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationDropdown(
    selected: String?,
    options: List<Destination>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedDest = options.find { it.id == selected }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedDest?.displayName ?: "Select destination",
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { dest ->
                DropdownMenuItem(
                    text = { Text(dest.displayName) },
                    onClick = {
                        onSelect(dest.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun DestinationChip(
    destinationName: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(destinationName) },
        trailingIcon = {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove",
                modifier = Modifier.size(16.dp)
            )
        },
        modifier = modifier
    )
}

@Composable
fun DestinationSelectionRow(
    selectedDestinations: List<String>,
    availableDestinations: List<Destination>,
    onDestinationChange: (List<String>) -> Unit,
    onAddDestination: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Primary destination dropdown
        DestinationDropdown(
            selected = selectedDestinations.firstOrNull(),
            options = availableDestinations,
            onSelect = { dest ->
                onDestinationChange(listOf(dest) + selectedDestinations.drop(1))
            },
            modifier = Modifier.weight(1f)
        )

        // Additional destinations as chips
        selectedDestinations.drop(1).forEach { destId ->
            val dest = availableDestinations.find { it.id == destId }
            if (dest != null) {
                DestinationChip(
                    destinationName = dest.displayName,
                    onRemove = {
                        onDestinationChange(selectedDestinations - destId)
                    }
                )
            }
        }

        // Add another button
        IconButton(onClick = onAddDestination) {
            Icon(Icons.Default.Add, "Add destination")
        }
    }
}
