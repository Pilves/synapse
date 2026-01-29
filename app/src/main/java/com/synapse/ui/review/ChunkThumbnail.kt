package com.synapse.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.synapse.model.Chunk
import com.synapse.ui.theme.SynapseTheme

/**
 * Thumbnail display for a single chunk
 *
 * Features:
 * - Downsampled image display using Coil with memory caching
 * - Checkbox overlay (in separate view mode)
 * - Timestamp offset label (+0s, +4s, etc.)
 * - Corrupted state indicator (grey with warning icon)
 * - Tap to preview full size
 */
@Composable
fun ChunkThumbnail(
    chunk: Chunk,
    offsetSeconds: Float,
    isSelected: Boolean,
    showCheckbox: Boolean,
    onSelect: (Boolean) -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailSize: Int = 80
) {
    val context = LocalContext.current

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(thumbnailSize.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(8.dp)
                        )
                    } else {
                        Modifier
                    }
                )
                .clickable {
                    if (showCheckbox) {
                        onSelect(!isSelected)
                    } else {
                        onPreview()
                    }
                }
        ) {
            if (chunk.isCorrupted) {
                // Corrupted chunk indicator
                CorruptedThumbnail(
                    modifier = Modifier.matchParentSize()
                )
            } else {
                // Normal image thumbnail with Coil
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(chunk.filePath)
                        .crossfade(true)
                        .size(thumbnailSize * 2) // Load at 2x for better quality on high DPI
                        .memoryCacheKey("chunk_thumb_${chunk.id}")
                        .build(),
                    contentDescription = "Chunk ${chunk.index}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                    loading = {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            // Placeholder while loading
                        }
                    },
                    error = {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(MaterialTheme.colorScheme.errorContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.BrokenImage,
                                contentDescription = "Failed to load",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                )
            }

            // Checkbox overlay (only in separate view mode)
            if (showCheckbox) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                ) {
                    SelectionIndicator(
                        isSelected = isSelected,
                        enabled = !chunk.isCorrupted,
                        onToggle = { onSelect(!isSelected) }
                    )
                }
            }

            // Click ripple for preview
            if (!showCheckbox) {
                Surface(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(onClick = onPreview),
                    color = Color.Transparent
                ) {}
            }
        }

        // Timestamp offset label
        Text(
            text = formatOffset(offsetSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = if (chunk.isCorrupted) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun CorruptedThumbnail(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Corrupted",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = "Corrupted",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun SelectionIndicator(
    isSelected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                }
            )
            .border(
                width = 1.5.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = CircleShape
            )
            .clickable(enabled = enabled, onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Format offset seconds to display string
 */
private fun formatOffset(seconds: Float): String {
    val roundedSeconds = seconds.toInt()
    return if (roundedSeconds == 0) {
        "+0s"
    } else {
        "+${roundedSeconds}s"
    }
}

/**
 * Larger thumbnail variant for stitched view mode
 */
@Composable
fun ChunkThumbnailLarge(
    chunk: Chunk,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    ChunkThumbnail(
        chunk = chunk,
        offsetSeconds = 0f,
        isSelected = false,
        showCheckbox = false,
        onSelect = {},
        onPreview = onPreview,
        modifier = modifier,
        thumbnailSize = 100
    )
}

@Preview(showBackground = true)
@Composable
private fun ChunkThumbnailPreview() {
    SynapseTheme {
        ChunkThumbnail(
            chunk = Chunk(
                id = "chunk1",
                sessionId = "session1",
                index = 0,
                filePath = "/storage/chunk1.jpg",
                timestampSeconds = 0f,
                createdAt = System.currentTimeMillis()
            ),
            offsetSeconds = 0f,
            isSelected = false,
            showCheckbox = true,
            onSelect = {},
            onPreview = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChunkThumbnailSelectedPreview() {
    SynapseTheme {
        ChunkThumbnail(
            chunk = Chunk(
                id = "chunk2",
                sessionId = "session1",
                index = 1,
                filePath = "/storage/chunk2.jpg",
                timestampSeconds = 4f,
                createdAt = System.currentTimeMillis()
            ),
            offsetSeconds = 4f,
            isSelected = true,
            showCheckbox = true,
            onSelect = {},
            onPreview = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChunkThumbnailCorruptedPreview() {
    SynapseTheme {
        ChunkThumbnail(
            chunk = Chunk(
                id = "chunk3",
                sessionId = "session1",
                index = 2,
                filePath = "/storage/chunk3.jpg",
                timestampSeconds = 8f,
                createdAt = System.currentTimeMillis(),
                isCorrupted = true
            ),
            offsetSeconds = 8f,
            isSelected = false,
            showCheckbox = true,
            onSelect = {},
            onPreview = {}
        )
    }
}
