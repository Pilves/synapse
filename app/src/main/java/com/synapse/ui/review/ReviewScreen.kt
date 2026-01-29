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
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inbox
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.synapse.model.Chunk
import com.synapse.model.Project
import com.synapse.model.SyncStatus
import com.synapse.model.CostEstimate
import com.synapse.model.Destination
import com.synapse.ui.components.DestinationSelectionRow
import com.synapse.ui.components.IntentConfirmationDialog
import com.synapse.ui.components.QuestionAnswerDialog
import com.synapse.ui.components.ReminderDialog
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
    val snackbarHostState = remember { SnackbarHostState() }

    // Reload sessions when screen appears
    LaunchedEffect(Unit) {
        viewModel.loadPendingSessions()
    }

    // Show error in snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                ReviewTopBar(
                    viewMode = uiState.viewMode,
                    onViewModeChange = viewModel::setViewMode,
                    queueStatus = uiState.queueStatus,
                    onRetrySync = viewModel::retrySyncQueue
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Column {
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
                        onDismiss = viewModel::resetSyncStatus
                    )

                    // Bottom controls (project, filename, sync button)
                    BottomControls(
                        projects = uiState.projects,
                        selectedProject = uiState.selectedProject,
                        filename = uiState.filename,
                        syncStatus = uiState.syncStatus,
                        selectedCount = if (uiState.viewMode == ViewMode.SEPARATE) {
                            uiState.selectedChunkIds.size
                        } else {
                            uiState.sessions.sumOf { it.chunks.size }
                        },
                        availableDestinations = uiState.availableDestinations,
                        selectedDestinations = uiState.selectedDestinations,
                        costEstimate = uiState.costEstimate,
                        onProjectSelected = viewModel::selectProject,
                        onFilenameChanged = viewModel::updateFilename,
                        onSyncAll = viewModel::syncAll,
                        onDestinationsChanged = viewModel::updateSelectedDestinations
                    )
                }
            }
        ) { paddingValues ->
            ReviewContent(
                sessions = uiState.sessions,
                viewMode = uiState.viewMode,
                selectedChunkIds = uiState.selectedChunkIds,
                isLoading = uiState.isLoading,
                onChunkSelected = { chunkId, _ -> viewModel.toggleChunkSelection(chunkId) },
                onSelectAllChunksInSession = viewModel::selectAllChunksInSession,
                onDeselectAllChunksInSession = viewModel::deselectAllChunksInSession,
                onDeleteChunk = viewModel::deleteChunk,
                onDeleteSession = viewModel::deleteSession,
                onPreviewChunk = viewModel::setPreviewChunk,
                onDeleteContext = viewModel::removeContext,
                modifier = Modifier.padding(paddingValues)
            )
        }

        // Full-size preview dialog
        uiState.previewChunk?.let { chunk ->
            ChunkPreviewDialog(
                chunk = chunk,
                onDismiss = { viewModel.setPreviewChunk(null) }
            )
        }

        // Intent confirmation dialog
        uiState.pendingIntentConfirmation?.let { pending ->
            IntentConfirmationDialog(
                noteText = pending.noteText,
                suggestedType = pending.suggestedType,
                onConfirm = viewModel::confirmIntent,
                onDismiss = viewModel::dismissIntentConfirmation
            )
        }

        // Question/answer dialog
        uiState.pendingQuestionAnswer?.let { pending ->
            QuestionAnswerDialog(
                question = pending.question,
                answer = pending.answer,
                onSaveBoth = viewModel::saveQuestionAndAnswer,
                onSaveQuestionOnly = viewModel::saveQuestionOnly,
                onDiscard = viewModel::dismissQuestionAnswer
            )
        }

        // Reminder dialog
        uiState.pendingReminder?.let { pending ->
            ReminderDialog(
                reminderText = pending.reminderText,
                timeText = pending.timeText,
                onCreateAlarm = viewModel::dismissReminder,
                onCreateCalendarEvent = viewModel::dismissReminder,
                onSaveAsNote = viewModel::dismissReminder
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
                            onDeleteContext = onDeleteContext
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
    syncStatus: SyncStatus,
    selectedCount: Int,
    availableDestinations: List<Destination>,
    selectedDestinations: List<String>,
    costEstimate: CostEstimate?,
    onProjectSelected: (Project) -> Unit,
    onFilenameChanged: (String) -> Unit,
    onSyncAll: () -> Unit,
    onDestinationsChanged: (List<String>) -> Unit
) {
    val isSyncing = syncStatus is SyncStatus.InProgress || syncStatus is SyncStatus.Queued

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

            // Destination selector
            if (availableDestinations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))

                DestinationSelectionRow(
                    selectedDestinations = selectedDestinations,
                    availableDestinations = availableDestinations,
                    onDestinationChange = onDestinationsChanged,
                    onAddDestination = { /* TODO: Open destination picker */ }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Filename input
            OutlinedTextField(
                value = filename,
                onValueChange = onFilenameChanged,
                label = { Text("File") },
                singleLine = true,
                enabled = !isSyncing,
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
                enabled = !isSyncing && selectedCount > 0 && selectedProject != null,
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
