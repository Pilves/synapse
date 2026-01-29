package com.synapse.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun DestinationSetupScreen(
    onComplete: () -> Unit,
    onSkip: () -> Unit
) {
    var selectedDestination by remember { mutableStateOf("clipboard") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Send,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Where do your notes go?",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Choose a default destination for your transcribed notes. You can change this per session later.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Destination options
        DestinationOption(
            icon = Icons.Default.ContentCopy,
            title = "Clipboard",
            description = "Copy to clipboard for pasting anywhere",
            selected = selectedDestination == "clipboard",
            onClick = { selectedDestination = "clipboard" }
        )

        Spacer(modifier = Modifier.height(8.dp))

        DestinationOption(
            icon = Icons.Default.Folder,
            title = "Local Folder",
            description = "Save as markdown in your Obsidian vault",
            selected = selectedDestination == "local_folder",
            onClick = { selectedDestination = "local_folder" }
        )

        Spacer(modifier = Modifier.height(8.dp))

        DestinationOption(
            icon = Icons.Default.Share,
            title = "Share",
            description = "Share to any app via Android share sheet",
            selected = selectedDestination == "share",
            onClick = { selectedDestination = "share" }
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onSkip) {
            Text("Skip for now")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        border = if (selected)
            CardDefaults.outlinedCardBorder()
        else null
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
