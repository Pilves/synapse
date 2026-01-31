package com.synapse.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.synapse.model.Chunk
import com.synapse.model.Project
import com.synapse.model.SyncStatus
import com.synapse.model.CostEstimate
import com.synapse.ui.components.SyncCostBanner
import com.synapse.ui.components.SyncQueueSummary
import com.synapse.ui.components.SyncStatusIndicator
import com.synapse.ui.theme.SynapseTheme

/**
 * Main Review screen composable
 *
 * Layout:
 * ┌─────────────────────────────┐
 * │ [Stitched ↔ Separate]       │  ← Toggle view mode
 * ├─────────────────────────────┤
 * │   Session 14:32             │
 * │   ┌─────────────────────┐   │
 * │   │ (chunk thumbnails)  │   │
 * │   └─────────────────────┘   │
 * │   Session 15:10             │
 * │   ...                       │
 * ├─────────────────────────────┤
 * │ Project: [DROPDOWN    ▼]    │
 * │ File:    [quick-notes.md ]  │
 * ├─────────────────────────────┤
 * │         [Sync All]          │
 * └─────────────────────────────┘
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current

    // Confirmation dialog state
    var showSyncConfirmation by remember { mutableStateOf(false) }

    // Show error in snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Long
            )
            viewModel.clearError()
        }
    }

    // Collect undo events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ReviewEvent.ShowUndoSnackbar -> {
                    val result = snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.undoDelete(event.tag)
                    }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ReviewTopBar(
                viewMode = uiState.viewMode,
                onViewModeChange = { mode ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.setViewMode(mode)
                },
                queueStatus = uiState.queueStatus,
                onRetrySync = viewModel::retrySyncQueue
            )

            // Offline banner
            AnimatedVisibility(
                visible = isOffline,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "No internet connection \u2014 sync unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            ReviewContent(
                sessions = uiState.sessions,
                viewMode = uiState.viewMode,
                selectedChunkIds = uiState.selectedChunkIds,
                failedChunkIds = uiState.failedChunkIds,
                isLoading = uiState.isLoading,
                onChunkSelected = { chunkId, _ ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.toggleChunkSelection(chunkId)
                },
                onSelectAllChunksInSession = viewModel::selectAllChunksInSession,
                onDeselectAllChunksInSession = viewModel::deselectAllChunksInSession,
                onDeleteChunk = { chunk ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.deleteChunk(chunk)
                },
                onDeleteSession = { session ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.deleteSession(session)
                },
                onPreviewChunk = viewModel::setPreviewChunk,
                onDeleteContext = viewModel::removeContext,
                modifier = Modifier.weight(1f)
            )

            // Sync queue summary showing pending/queued/failed counts
            SyncQueueSummary(
                pendingCount = uiState.pendingSyncCount,
                queuedCount = uiState.queuedSyncCount,
                failedCount = uiState.failedSyncCount,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // Sync status bar
            SyncStatusBar(
                syncStatus = uiState.syncStatus,
                onDismiss = viewModel::resetSyncStatus,
                failedChunkIds = uiState.failedChunkIds,
                onRetryFailed = viewModel::retryFailedChunks
            )

            // Memoize selected count to avoid recalculating on every recomposition
            val selectedCount by remember {
                derivedStateOf {
                    val contextOnlySessions = uiState.sessions.count { it.chunks.isEmpty() && it.contexts.isNotEmpty() }
                    if (uiState.viewMode == ViewMode.SEPARATE) {
                        uiState.selectedChunkIds.size + contextOnlySessions
                    } else {
                        uiState.sessions.sumOf { it.chunks.size } + contextOnlySessions
                    }
                }
            }

            // Bottom controls (project, filename, sync button)
            BottomControls(
                projects = uiState.projects,
                selectedProject = uiState.selectedProject,
                filename = uiState.filename,
                filenameError = uiState.filenameError,
                syncStatus = uiState.syncStatus,
                selectedCount = selectedCount,
                costEstimate = uiState.costEstimate,
                isOffline = isOffline,
                onProjectSelected = viewModel::selectProject,
                onFilenameChanged = viewModel::updateFilename,
                onSyncAll = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (selectedCount > 5) {
                        showSyncConfirmation = true
                    } else {
                        viewModel.syncAll()
                    }
                }
            )
        }

        // Snackbar host
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Full-size preview dialog
        uiState.previewChunk?.let { chunk ->
            ChunkPreviewDialog(
                chunk = chunk,
                onDismiss = { viewModel.setPreviewChunk(null) }
            )
        }

        // Sync confirmation dialog
        if (showSyncConfirmation) {
            val syncCount by remember {
                derivedStateOf {
                    val contextOnlySessions = uiState.sessions.count { it.chunks.isEmpty() && it.contexts.isNotEmpty() }
                    if (uiState.viewMode == ViewMode.SEPARATE) {
                        uiState.selectedChunkIds.size + contextOnlySessions
                    } else {
                        uiState.sessions.sumOf { it.chunks.size } + contextOnlySessions
                    }
                }
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSyncConfirmation = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = { Text("Sync $syncCount items?") },
                text = {
                    Text("This will use API credits to transcribe all selected items.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        showSyncConfirmation = false
                        viewModel.syncAll()
                    }) {
                        Text("Sync")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSyncConfirmation = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewTopBar(
    viewMode: ViewMode,
    onViewModeChange: (ViewMode) -> Unit,
    queueStatus: com.synapse.model.QueueStatus? = null,
    onRetrySync: (() -> Unit)? = null
) {
    TopAppBar(
        title = {
            Text(
                text = "Review",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        actions = {
            // Sync status indicator in the top bar
            SyncStatusIndicator(
                status = queueStatus,
                onRetry = onRetrySync,
                modifier = Modifier.padding(end = 8.dp)
            )
            // View mode toggle
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.padding(end = 8.dp)
            ) {
                SegmentedButton(
                    selected = viewMode == ViewMode.STITCHED,
                    onClick = { onViewModeChange(ViewMode.STITCHED) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.ViewCarousel,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                ) {
                    Text("Stitched", style = MaterialTheme.typography.labelMedium)
                }
                SegmentedButton(
                    selected = viewMode == ViewMode.SEPARATE,
                    onClick = { onViewModeChange(ViewMode.SEPARATE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.GridView,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                ) {
                    Text("Separate", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun ReviewContent(
    sessions: List<com.synapse.model.Session>,
    viewMode: ViewMode,
    selectedChunkIds: Set<String>,
    failedChunkIds: Set<String>,
    isLoading: Boolean,
    onChunkSelected: (String, Boolean) -> Unit,
    onSelectAllChunksInSession: (com.synapse.model.Session) -> Unit,
    onDeselectAllChunksInSession: (com.synapse.model.Session) -> Unit,
    onDeleteChunk: (Chunk) -> Unit,
    onDeleteSession: (com.synapse.model.Session) -> Unit,
    onPreviewChunk: (Chunk) -> Unit,
    onDeleteContext: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            sessions.isEmpty() -> {
                EmptySessionsView(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(
                        items = sessions,
                        key = { it.id }
                    ) { session ->
                        SessionCard(
                            session = session,
                            viewMode = viewMode,
                            selectedChunkIds = selectedChunkIds,
                            onChunkSelected = onChunkSelected,
                            onSelectAllChunks = { onSelectAllChunksInSession(session) },
                            onDeselectAllChunks = { onDeselectAllChunksInSession(session) },
                            onDeleteChunk = onDeleteChunk,
                            onDeleteSession = { onDeleteSession(session) },
                            onPreviewChunk = onPreviewChunk,
                            onDeleteContext = onDeleteContext,
                            failedChunkIds = failedChunkIds
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySessionsView(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Inbox,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No pending sessions",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Captured sessions will appear here for review",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun BottomControls(
    projects: List<Project>,
    selectedProject: Project?,
    filename: String,
    filenameError: String?,
    syncStatus: SyncStatus,
    selectedCount: Int,
    costEstimate: CostEstimate?,
    isOffline: Boolean = false,
    onProjectSelected: (Project) -> Unit,
    onFilenameChanged: (String) -> Unit,
    onSyncAll: () -> Unit
) {
    val isSyncing = syncStatus is SyncStatus.InProgress || syncStatus is SyncStatus.Queued
    val isSyncEnabled = !isSyncing && !isOffline && selectedCount > 0 && selectedProject != null && filenameError == null && filename.isNotBlank()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Project dropdown
            ProjectDropdown(
                projects = projects,
                selectedProject = selectedProject,
                onProjectSelected = onProjectSelected,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Filename input with validation
            OutlinedTextField(
                value = filename,
                onValueChange = onFilenameChanged,
                label = { Text("File") },
                singleLine = true,
                enabled = !isSyncing,
                isError = filenameError != null,
                supportingText = filenameError?.let { error ->
                    { Text(error, color = MaterialTheme.colorScheme.error) }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Cost estimate banner
            costEstimate?.let { cost ->
                SyncCostBanner(
                    costEstimate = cost,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Sync button
            Button(
                onClick = onSyncAll,
                enabled = isSyncEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Syncing...")
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (selectedCount > 0) "Sync All ($selectedCount)" else "Sync All",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Disabled sync button reason
            if (!isSyncEnabled && !isSyncing) {
                val reason = when {
                    isOffline -> "No internet connection"
                    selectedCount == 0 -> "No chunks selected"
                    selectedProject == null -> "Select a project first"
                    filenameError != null -> filenameError
                    filename.isBlank() -> "Enter a filename"
                    else -> null
                }
                reason?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectDropdown(
    projects: List<Project>,
    selectedProject: Project?,
    onProjectSelected: (Project) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedTextField(
            value = selectedProject?.name ?: "Select Project",
            onValueChange = {},
            readOnly = true,
            label = { Text("Project") },
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Select project"
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            shape = RoundedCornerShape(12.dp)
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            projects.forEach { project ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = project.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = project.defaultFile,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onProjectSelected(project)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ChunkPreviewDialog(
    chunk: Chunk,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .clickable(onClick = onDismiss)
        ) {
            // Close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Full-size image
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(chunk.filePath)
                    .crossfade(true)
                    .build(),
                contentDescription = "Chunk preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .align(Alignment.Center)
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ReviewScreenPreview() {
    SynapseTheme {
        // Note: In a real preview, we'd use a mock ViewModel
        // For now, just showing the structure
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column {
                Text(
                    text = "Review Screen Preview",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.headlineMedium
                )
            }
        }
    }
}
