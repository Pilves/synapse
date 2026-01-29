package com.synapse.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synapse.model.CapturedContext
import com.synapse.model.Chunk
import com.synapse.model.Session
import com.synapse.ui.components.ContextSection
import com.synapse.ui.theme.SynapseTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Card displaying a session with its chunks
 *
 * Features:
 * - Session timestamp header
 * - Chunk thumbnails (horizontal scrollable or grid)
 * - In separate view: checkbox per chunk, delete button, timestamp offset
 * - Expandable/collapsible chunks section
 */
@Composable
fun SessionCard(
    session: Session,
    viewMode: ViewMode,
    selectedChunkIds: Set<String>,
    onChunkSelected: (String, Boolean) -> Unit,
    onSelectAllChunks: () -> Unit,
    onDeselectAllChunks: () -> Unit,
    onDeleteChunk: (Chunk) -> Unit,
    onDeleteSession: () -> Unit,
    onPreviewChunk: (Chunk) -> Unit,
    onDeleteContext: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }

    val sessionChunksSelected = session.chunks.count { it.id in selectedChunkIds }
    val allSelected = sessionChunksSelected == session.chunks.size && session.chunks.isNotEmpty()
    val someSelected = sessionChunksSelected > 0 && !allSelected

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // Session header
            SessionHeader(
                session = session,
                isExpanded = isExpanded,
                viewMode = viewMode,
                allSelected = allSelected,
                someSelected = someSelected,
                onToggleExpand = { isExpanded = !isExpanded },
                onSelectAll = {
                    if (allSelected) onDeselectAllChunks() else onSelectAllChunks()
                },
                onDelete = onDeleteSession
            )

            // Chunks content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    when (viewMode) {
                        ViewMode.STITCHED -> StitchedChunksView(
                            chunks = session.chunks,
                            session = session,
                            onPreviewChunk = onPreviewChunk,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        ViewMode.SEPARATE -> SeparateChunksView(
                            chunks = session.chunks,
                            session = session,
                            selectedChunkIds = selectedChunkIds,
                            onChunkSelected = onChunkSelected,
                            onDeleteChunk = onDeleteChunk,
                            onPreviewChunk = onPreviewChunk,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }

                    // Show contexts that belong to this session
                    if (session.contexts.isNotEmpty()) {
                        ContextSection(
                            contexts = session.contexts,
                            onDeleteContext = onDeleteContext,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionHeader(
    session: Session,
    isExpanded: Boolean,
    viewMode: ViewMode,
    allSelected: Boolean,
    someSelected: Boolean,
    onToggleExpand: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Session time
        Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Session ${formatSessionTime(session.startedAt)}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )

        // Chunk count badge
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${session.chunks.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Select all button (only in separate mode)
        if (viewMode == ViewMode.SEPARATE) {
            IconButton(
                onClick = onSelectAll,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (allSelected) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                    contentDescription = if (allSelected) "Deselect all" else "Select all",
                    tint = when {
                        allSelected -> MaterialTheme.colorScheme.primary
                        someSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        // Delete session button
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete session",
                tint = MaterialTheme.colorScheme.error
            )
        }

        // Expand/collapse button
        IconButton(
            onClick = onToggleExpand,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Stitched view - shows thumbnails in a horizontal scroll without selection
 */
@Composable
private fun StitchedChunksView(
    chunks: List<Chunk>,
    session: Session,
    onPreviewChunk: (Chunk) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = chunks,
            key = { it.id }
        ) { chunk ->
            ChunkThumbnail(
                chunk = chunk,
                offsetSeconds = calculateOffset(session, chunk),
                isSelected = false,
                showCheckbox = false,
                onSelect = {},
                onPreview = { onPreviewChunk(chunk) }
            )
        }
    }
}

/**
 * Separate view - shows thumbnails with selection checkboxes and delete buttons
 */
@Composable
private fun SeparateChunksView(
    chunks: List<Chunk>,
    session: Session,
    selectedChunkIds: Set<String>,
    onChunkSelected: (String, Boolean) -> Unit,
    onDeleteChunk: (Chunk) -> Unit,
    onPreviewChunk: (Chunk) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = chunks,
            key = { it.id }
        ) { chunk ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ChunkThumbnail(
                    chunk = chunk,
                    offsetSeconds = calculateOffset(session, chunk),
                    isSelected = chunk.id in selectedChunkIds,
                    showCheckbox = true,
                    onSelect = { selected -> onChunkSelected(chunk.id, selected) },
                    onPreview = { onPreviewChunk(chunk) }
                )

                // Delete button for individual chunk
                IconButton(
                    onClick = { onDeleteChunk(chunk) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete chunk",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Calculate offset in seconds from session start
 */
private fun calculateOffset(session: Session, chunk: Chunk): Float {
    val sessionStartSeconds = session.startedAt / 1000f
    return chunk.timestampSeconds
}

/**
 * Format session timestamp to display time
 */
private fun formatSessionTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

@Preview(showBackground = true)
@Composable
private fun SessionCardStitchedPreview() {
    val session = Session(
        id = "session1",
        startedAt = System.currentTimeMillis() - 3600000,
        chunks = listOf(
            Chunk("c1", "session1", 0, "/path1", 0f, System.currentTimeMillis()),
            Chunk("c2", "session1", 1, "/path2", 4f, System.currentTimeMillis()),
            Chunk("c3", "session1", 2, "/path3", 8f, System.currentTimeMillis()),
            Chunk("c4", "session1", 3, "/path4", 12f, System.currentTimeMillis(), isCorrupted = true)
        )
    )

    SynapseTheme {
        SessionCard(
            session = session,
            viewMode = ViewMode.STITCHED,
            selectedChunkIds = emptySet(),
            onChunkSelected = { _, _ -> },
            onSelectAllChunks = {},
            onDeselectAllChunks = {},
            onDeleteChunk = {},
            onDeleteSession = {},
            onPreviewChunk = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SessionCardSeparatePreview() {
    val session = Session(
        id = "session1",
        startedAt = System.currentTimeMillis() - 3600000,
        chunks = listOf(
            Chunk("c1", "session1", 0, "/path1", 0f, System.currentTimeMillis()),
            Chunk("c2", "session1", 1, "/path2", 4f, System.currentTimeMillis()),
            Chunk("c3", "session1", 2, "/path3", 8f, System.currentTimeMillis())
        )
    )

    SynapseTheme {
        SessionCard(
            session = session,
            viewMode = ViewMode.SEPARATE,
            selectedChunkIds = setOf("c1", "c2"),
            onChunkSelected = { _, _ -> },
            onSelectAllChunks = {},
            onDeselectAllChunks = {},
            onDeleteChunk = {},
            onDeleteSession = {},
            onPreviewChunk = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
