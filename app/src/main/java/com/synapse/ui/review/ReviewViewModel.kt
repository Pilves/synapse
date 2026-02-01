package com.synapse.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synapse.data.cost.LlmCostCalculator
import com.synapse.data.LlmSettingsProvider
import com.synapse.data.repository.ProjectRepository
import com.synapse.data.repository.SessionRepository
import com.synapse.data.repository.SyncRepository
import com.synapse.model.CapturedContext
import com.synapse.model.CostEstimate
import com.synapse.model.QueueStatus
import com.synapse.model.Chunk
import com.synapse.model.Project
import com.synapse.model.Session
import com.synapse.model.SyncStatus
import com.synapse.util.NetworkMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * View mode for displaying chunks in the review screen
 */
enum class ViewMode {
    STITCHED,
    SEPARATE
}

/**
 * One-shot events emitted by ReviewViewModel to the UI layer.
 */
sealed class ReviewEvent {
    data class ShowUndoSnackbar(val message: String, val tag: String) : ReviewEvent()
}

/**
 * UI state for the Review screen
 */
data class ReviewUiState(
    val sessions: List<Session> = emptyList(),
    val viewMode: ViewMode = ViewMode.STITCHED,
    val projects: List<Project> = emptyList(),
    val selectedProject: Project? = null,
    val filename: String = "quick notes.md",
    val filenameError: String? = null,
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val selectedChunkIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val previewChunk: Chunk? = null,
    val contexts: List<CapturedContext> = emptyList(),
    val costEstimate: CostEstimate? = null,
    val costEstimateError: String? = null,
    val queueStatus: QueueStatus? = null,
    val pendingSyncCount: Int = 0,
    val queuedSyncCount: Int = 0,
    val failedSyncCount: Int = 0,
    val failedChunkIds: Set<String> = emptySet()
)

/**
 * ViewModel for the Review screen
 *
 * Manages pending sessions and chunks, view mode toggle, project selection,
 * filename input, sync status, and deletion of chunks/sessions.
 */
class ReviewViewModel(
    private val sessionRepository: SessionRepository,
    private val projectRepository: ProjectRepository,
    private val syncRepository: SyncRepository,
    private val llmSettingsProvider: LlmSettingsProvider? = null,
    networkMonitor: NetworkMonitor? = null
) : ViewModel() {

    val isOffline: StateFlow<Boolean> = networkMonitor?.isOnline
        ?.map { !it }
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        ?: MutableStateFlow(false)

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ReviewEvent>()
    val events = _events.asSharedFlow()

    // Pending deletions awaiting undo timeout
    private data class PendingDeletion(
        val chunk: Chunk? = null,
        val session: Session? = null,
        val tag: String
    )
    private val pendingDeletions = mutableMapOf<String, PendingDeletion>()
    private val pendingDeletionJobs = mutableMapOf<String, Job>()

    // Debounce job for cost estimate refresh
    private var costEstimateJob: Job? = null

    companion object {
        private const val UNDO_TIMEOUT_MS = 5000L
        private const val COST_DEBOUNCE_MS = 300L
        private val INVALID_FILENAME_CHARS = Regex("[/\\\\:*?\"<>|]")
    }

    init {
        observeSessions()
        loadProjects()
    }

    /**
     * Observe pending sessions - updates automatically when sessions change
     */
    private fun observeSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            sessionRepository.observeSessions()
                .distinctUntilChanged()
                .collectLatest { allSessions ->
                // Filter to only pending sessions (ended but not synced)
                val pendingSessions = allSessions.filter { it.endedAt != null }
                // Aggregate contexts from all pending sessions
                val allContexts = pendingSessions.flatMap { it.contexts }
                _uiState.update {
                    it.copy(
                        sessions = pendingSessions,
                        contexts = allContexts,
                        isLoading = false
                    )
                }
                refreshCostEstimate()
            }
        }
    }

    /**
     * Recalculate cost estimate from the configured LLM provider.
     */
    private fun refreshCostEstimate() {
        viewModelScope.launch {
            try {
                val (provider, _, _) = llmSettingsProvider?.readLlmSettings()
                    ?: return@launch
                updateCostEstimate(provider.defaultModel)
                _uiState.update { it.copy(costEstimateError = null) }
            } catch (e: Exception) {
                android.util.Log.w("ReviewViewModel", "Failed to refresh cost estimate", e)
                _uiState.update { it.copy(costEstimateError = "Failed to refresh cost estimate") }
            }
        }
    }

    /**
     * Clears the cost estimate error after the UI has consumed it.
     */
    fun clearCostEstimateError() {
        _uiState.update { it.copy(costEstimateError = null) }
    }

    /**
     * Debounced cost estimate refresh triggered by selection changes.
     */
    private fun debouncedRefreshCostEstimate() {
        costEstimateJob?.cancel()
        costEstimateJob = viewModelScope.launch {
            delay(COST_DEBOUNCE_MS)
            refreshCostEstimate()
        }
    }

    /**
     * Observe projects for the dropdown - updates when projects change
     */
    private fun loadProjects() {
        viewModelScope.launch {
            projectRepository.observeProjects()
                .distinctUntilChanged()
                .collectLatest { projects ->
                _uiState.update { state ->
                    // Keep current selection if still valid, otherwise select first
                    val currentSelection = state.selectedProject
                    val newSelection = if (currentSelection != null && projects.any { it.id == currentSelection.id }) {
                        currentSelection
                    } else {
                        projects.firstOrNull()
                    }
                    state.copy(
                        projects = projects,
                        selectedProject = newSelection
                    )
                }
            }
        }
    }

    /**
     * Toggle between stitched and separate view modes
     */
    fun toggleViewMode() {
        _uiState.update { state ->
            val newMode = if (state.viewMode == ViewMode.STITCHED) {
                ViewMode.SEPARATE
            } else {
                ViewMode.STITCHED
            }
            state.copy(
                viewMode = newMode,
                // Clear selections when switching to stitched mode
                selectedChunkIds = if (newMode == ViewMode.STITCHED) emptySet() else state.selectedChunkIds
            )
        }
    }

    /**
     * Set the view mode directly
     */
    fun setViewMode(mode: ViewMode) {
        _uiState.update { state ->
            state.copy(
                viewMode = mode,
                selectedChunkIds = if (mode == ViewMode.STITCHED) emptySet() else state.selectedChunkIds
            )
        }
    }

    /**
     * Select a project from the dropdown
     */
    fun selectProject(project: Project) {
        _uiState.update { it.copy(selectedProject = project) }
    }

    /**
     * Update the filename for sync with inline validation.
     */
    fun updateFilename(filename: String) {
        val error = validateFilename(filename)
        _uiState.update { it.copy(filename = filename, filenameError = error) }
    }

    private fun validateFilename(filename: String): String? {
        return when {
            filename.isBlank() -> "Filename required"
            INVALID_FILENAME_CHARS.containsMatchIn(filename) -> "Invalid characters: / \\ : * ? \" < > |"
            else -> null
        }
    }

    /**
     * Toggle chunk selection (for separate view mode)
     */
    fun toggleChunkSelection(chunkId: String) {
        _uiState.update { state ->
            val newSelection = if (chunkId in state.selectedChunkIds) {
                state.selectedChunkIds - chunkId
            } else {
                state.selectedChunkIds + chunkId
            }
            state.copy(selectedChunkIds = newSelection)
        }
        debouncedRefreshCostEstimate()
    }

    /**
     * Select all chunks in a session
     */
    fun selectAllChunksInSession(session: Session) {
        _uiState.update { state ->
            val chunkIds = session.chunks.map { it.id }.toSet()
            state.copy(selectedChunkIds = state.selectedChunkIds + chunkIds)
        }
        debouncedRefreshCostEstimate()
    }

    /**
     * Deselect all chunks in a session
     */
    fun deselectAllChunksInSession(session: Session) {
        _uiState.update { state ->
            val chunkIds = session.chunks.map { it.id }.toSet()
            state.copy(selectedChunkIds = state.selectedChunkIds - chunkIds)
        }
        debouncedRefreshCostEstimate()
    }

    /**
     * Select all chunks across all sessions
     */
    fun selectAllChunks() {
        _uiState.update { state ->
            val allChunkIds = state.sessions.flatMap { session ->
                session.chunks.map { it.id }
            }.toSet()
            state.copy(selectedChunkIds = allChunkIds)
        }
        debouncedRefreshCostEstimate()
    }

    /**
     * Clear all chunk selections
     */
    fun clearSelection() {
        _uiState.update { it.copy(selectedChunkIds = emptySet()) }
        debouncedRefreshCostEstimate()
    }

    /**
     * Soft-delete an individual chunk with undo support.
     * The chunk is removed from the UI immediately. Permanent deletion
     * happens after a timeout unless the user taps Undo.
     */
    fun deleteChunk(chunk: Chunk) {
        val tag = "chunk_${chunk.id}"

        // Remove from UI immediately
        _uiState.update { state ->
            val updatedSessions = state.sessions.map { session ->
                if (session.id == chunk.sessionId) {
                    session.copy(chunks = session.chunks.filter { it.id != chunk.id })
                } else {
                    session
                }
            }.filter { it.chunks.isNotEmpty() || it.contexts.isNotEmpty() }

            state.copy(
                sessions = updatedSessions,
                selectedChunkIds = state.selectedChunkIds - chunk.id
            )
        }

        // Store pending deletion
        pendingDeletions[tag] = PendingDeletion(chunk = chunk, tag = tag)

        // Schedule permanent deletion after timeout
        pendingDeletionJobs[tag]?.cancel()
        pendingDeletionJobs[tag] = viewModelScope.launch {
            delay(UNDO_TIMEOUT_MS)
            commitDeletion(tag)
        }

        // Emit undo snackbar event
        viewModelScope.launch {
            _events.emit(ReviewEvent.ShowUndoSnackbar("Chunk deleted", tag))
        }
    }

    /**
     * Soft-delete an entire session with undo support.
     */
    fun deleteSession(session: Session) {
        val tag = "session_${session.id}"

        // Remove from UI immediately
        _uiState.update { state ->
            val chunkIds = session.chunks.map { it.id }.toSet()
            state.copy(
                sessions = state.sessions.filter { it.id != session.id },
                selectedChunkIds = state.selectedChunkIds - chunkIds
            )
        }

        // Store pending deletion
        pendingDeletions[tag] = PendingDeletion(session = session, tag = tag)

        // Schedule permanent deletion after timeout
        pendingDeletionJobs[tag]?.cancel()
        pendingDeletionJobs[tag] = viewModelScope.launch {
            delay(UNDO_TIMEOUT_MS)
            commitDeletion(tag)
        }

        // Emit undo snackbar event
        viewModelScope.launch {
            _events.emit(ReviewEvent.ShowUndoSnackbar("Session deleted", tag))
        }
    }

    /**
     * Undo a pending deletion, restoring the chunk or session to the UI.
     */
    fun undoDelete(tag: String) {
        val pending = pendingDeletions.remove(tag) ?: return
        pendingDeletionJobs.remove(tag)?.cancel()

        _uiState.update { state ->
            when {
                pending.chunk != null -> {
                    val chunk = pending.chunk
                    val updatedSessions = state.sessions.map { session ->
                        if (session.id == chunk.sessionId) {
                            session.copy(chunks = (session.chunks + chunk).sortedBy { it.index })
                        } else {
                            session
                        }
                    }
                    // If the session was removed because it became empty, re-add it
                    val hasSession = updatedSessions.any { it.id == chunk.sessionId }
                    if (hasSession) {
                        state.copy(sessions = updatedSessions)
                    } else {
                        // Session was filtered out; we can't fully restore without re-fetching
                        // Force a refresh from the repository
                        state
                    }
                }
                pending.session != null -> {
                    val session = pending.session
                    // Re-insert session in original position (sorted by startedAt)
                    val updatedSessions = (state.sessions + session).sortedByDescending { it.startedAt }
                    state.copy(sessions = updatedSessions)
                }
                else -> state
            }
        }
    }

    /**
     * Permanently deletes a pending item from the repository.
     */
    private fun commitDeletion(tag: String) {
        val pending = pendingDeletions.remove(tag) ?: return
        pendingDeletionJobs.remove(tag)

        viewModelScope.launch {
            try {
                when {
                    pending.chunk != null -> {
                        sessionRepository.deleteChunk(pending.chunk.sessionId, pending.chunk.id)
                    }
                    pending.session != null -> {
                        sessionRepository.deleteSession(pending.session.id)
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to delete: ${e.message}")
                }
            }
        }
    }

    /**
     * Sync all pending sessions
     */
    fun syncAll() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.selectedProject == null) {
                _uiState.update { it.copy(error = "Please select a project") }
                return@launch
            }
            if (state.filename.isBlank() || state.filenameError != null) {
                _uiState.update { it.copy(error = "Please enter a valid filename") }
                return@launch
            }

            _uiState.update { it.copy(syncStatus = SyncStatus.Queued, failedChunkIds = emptySet()) }

            // Observe granular progress from the repository
            val progressJob = viewModelScope.launch {
                syncRepository.observeSyncStatus().collectLatest { repoStatus ->
                    if (repoStatus is SyncStatus.InProgress) {
                        _uiState.update { it.copy(syncStatus = repoStatus) }
                    }
                }
            }

            try {
                // Determine which sessions to sync
                val sessionsToSync = if (state.viewMode == ViewMode.SEPARATE && state.selectedChunkIds.isNotEmpty()) {
                    // Sync only sessions that have selected chunks
                    state.sessions.filter { session ->
                        session.chunks.any { it.id in state.selectedChunkIds }
                    }
                } else {
                    // Sync all sessions
                    state.sessions
                }

                if (sessionsToSync.isEmpty()) {
                    progressJob.cancelAndJoin()
                    _uiState.update {
                        it.copy(
                            syncStatus = SyncStatus.Idle,
                            error = "No sessions to sync"
                        )
                    }
                    return@launch
                }

                val totalSessions = sessionsToSync.size
                var syncedCount = 0
                var failedCount = 0
                var lastErrorMessage: String? = null
                val syncedIds = mutableSetOf<String>()
                val failedIds = mutableSetOf<String>()

                for ((index, session) in sessionsToSync.withIndex()) {
                    val result = syncRepository.syncSession(
                        sessionId = session.id,
                        projectId = state.selectedProject.id,
                        filename = state.filename
                    )

                    when (result) {
                        is SyncStatus.Success -> {
                            syncedCount++
                            syncedIds.add(session.id)
                        }
                        is SyncStatus.PartialSuccess -> {
                            syncedCount++
                            failedCount += result.failedCount
                            failedIds.addAll(result.failedChunkIds)
                        }
                        is SyncStatus.Error -> {
                            failedCount++
                            lastErrorMessage = result.message
                            // All chunks in this session failed
                            failedIds.addAll(session.chunks.map { it.id })
                        }
                        else -> {}
                    }
                }

                val finalStatus = when {
                    failedCount == 0 && syncedCount > 0 -> SyncStatus.Success
                    syncedCount == 0 -> SyncStatus.Error(
                        lastErrorMessage ?: "All sessions failed to sync"
                    )
                    else -> SyncStatus.PartialSuccess(syncedCount, failedCount, failedIds.toList())
                }

                progressJob.cancelAndJoin()

                // Delete successfully synced sessions immediately
                for (id in syncedIds) {
                    try {
                        sessionRepository.deleteSession(id)
                    } catch (e: Exception) {
                        android.util.Log.w("ReviewViewModel", "Failed to cleanup session $id", e)
                    }
                }

                _uiState.update {
                    it.copy(
                        syncStatus = finalStatus,
                        failedChunkIds = failedIds
                    )
                }
            } catch (e: Exception) {
                progressJob.cancelAndJoin()
                _uiState.update {
                    it.copy(syncStatus = SyncStatus.Error(e.message ?: "Unknown error"))
                }
            }
        }
    }

    /**
     * Retry sync for only the chunks that failed in the last sync.
     */
    fun retryFailedChunks() {
        val failedIds = _uiState.value.failedChunkIds
        if (failedIds.isEmpty()) return

        // Select only failed chunks and re-sync
        _uiState.update {
            it.copy(
                viewMode = ViewMode.SEPARATE,
                selectedChunkIds = failedIds,
                failedChunkIds = emptySet()
            )
        }
        syncAll()
    }

    /**
     * Reset sync status to idle
     */
    fun resetSyncStatus() {
        _uiState.update { it.copy(syncStatus = SyncStatus.Idle, failedChunkIds = emptySet()) }
    }

    /**
     * Clear any displayed error
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Set the chunk to preview in full size
     */
    fun setPreviewChunk(chunk: Chunk?) {
        _uiState.update { it.copy(previewChunk = chunk) }
    }

    /**
     * Add a captured context to the current review
     */
    fun addContext(context: CapturedContext) {
        _uiState.update { state ->
            state.copy(contexts = state.contexts + context)
        }
    }

    /**
     * Remove a captured context by ID
     */
    fun removeContext(contextId: String) {
        _uiState.update { state ->
            state.copy(contexts = state.contexts.filter { it.id != contextId })
        }
    }

    /**
     * Recalculate cost estimate based on current sessions and model.
     * Uses a default chunk size estimate of 50KB per chunk.
     */
    fun updateCostEstimate(model: String) {
        val state = _uiState.value
        val chunks = state.sessions.flatMap { it.chunks }
        if (chunks.isEmpty()) {
            _uiState.update { it.copy(costEstimate = null) }
            return
        }

        // Estimate ~50KB per chunk image as a reasonable default
        val chunkSizes = chunks.map { 50 * 1024 }
        val estimate = LlmCostCalculator.estimateCost(
            chunkSizes = chunkSizes,
            contextCount = state.contexts.size,
            model = model
        )
        _uiState.update { it.copy(costEstimate = estimate) }
    }

    /**
     * Update the sync queue summary counts
     */
    fun updateQueueSummary(pendingCount: Int, queuedCount: Int, failedCount: Int) {
        _uiState.update { state ->
            state.copy(
                pendingSyncCount = pendingCount,
                queuedSyncCount = queuedCount,
                failedSyncCount = failedCount
            )
        }
    }

    /**
     * Update the current queue status indicator
     */
    fun updateQueueStatus(status: QueueStatus?) {
        _uiState.update { it.copy(queueStatus = status) }
    }

    /**
     * Retry failed sync operations
     */
    fun retrySyncQueue() {
        viewModelScope.launch {
            _uiState.update { it.copy(queueStatus = QueueStatus.PENDING) }
            syncAll()
        }
    }

    /**
     * Get the timestamp offset for a chunk relative to session start
     */
    fun getChunkOffset(session: Session, chunk: Chunk): Float {
        val sessionStartSeconds = session.startedAt / 1000f
        return chunk.timestampSeconds - sessionStartSeconds
    }
}
