package com.synapse.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.synapse.model.CapturedContext

@Composable
fun ContextCard(
    context: CapturedContext,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = when (context) {
                    is CapturedContext.SelectedText -> Icons.Default.FormatQuote
                    is CapturedContext.RegionText -> Icons.Default.CropFree
                    is CapturedContext.RegionImage -> Icons.Default.Image
                    is CapturedContext.AutoContext -> Icons.Default.Language
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                when (context) {
                    is CapturedContext.AutoContext -> {
                        Text(
                            context.sourceApp.toAppName(),
                            style = MaterialTheme.typography.labelMedium
                        )
                        context.sourceUrl?.let { url ->
                            Text(
                                url.toDisplayUrl(),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    is CapturedContext.SelectedText -> {
                        context.sourceApp?.let { app ->
                            Text(
                                app.toAppName(),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Text(
                            context.text,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    is CapturedContext.RegionText -> {
                        Text(
                            "Selected region",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            context.text,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    is CapturedContext.RegionImage -> {
                        Text(
                            "Image region",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            context.description ?: "Will be described by AI",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun ContextSection(
    contexts: List<CapturedContext>,
    onDeleteContext: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (contexts.isEmpty()) return

    Column(modifier = modifier) {
        Text(
            "Context",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        contexts.forEach { context ->
            ContextCard(
                context = context,
                onDelete = { onDeleteContext(context.id) }
            )
        }
    }
}

// Extension functions for display formatting
fun String.toAppName(): String {
    return when {
        contains("chrome") -> "Chrome"
        contains("firefox") -> "Firefox"
        contains("brave") -> "Brave"
        contains("samsung") -> "Samsung Browser"
        contains("obsidian") -> "Obsidian"
        contains("notion") -> "Notion"
        contains("discord") -> "Discord"
        contains("slack") -> "Slack"
        else -> split(".").lastOrNull() ?: this
    }
}

fun String.toDisplayUrl(): String {
    return removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .take(50)
        .let { if (length > 50) "$it..." else it }
}
