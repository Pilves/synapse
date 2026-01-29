package com.synapse.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synapse.data.destination.DestinationRepository
import com.synapse.data.repository.ProjectRepository
import com.synapse.data.repository.SessionRepository
import com.synapse.data.repository.SyncRepository
import com.synapse.data.cost.LlmCostCalculator
import com.synapse.model.CapturedContext
import com.synapse.model.CostEstimate
import com.synapse.model.Destination
import com.synapse.model.QueueStatus
import com.synapse.model.Chunk
import com.synapse.model.Project
import com.synapse.model.Session
import com.synapse.model.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
 * UI state for the Review screen
 */
data class ReviewUiState(
    val sessions: List<Session> = emptyList(),
    val viewMode: ViewMode = ViewMode.STITCHED,
    val projects: List<Project> = emptyList(),
    val selectedProject: Project? = null,
    val filename: String = "quick notes.md",
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val selectedChunkIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val previewChunk: Chunk? = null,
    val contexts: List<CapturedContext> = emptyList(),
    val availableDestinations: List<Destination> = emptyList(),
    val selectedDestinations: List<String> = emptyList(),
    val costEstimate: CostEstimate? = null,
    val queueStatus: QueueStatus? = null,
    val pendingSyncCount: Int = 0,
    val queuedSyncCount: Int = 0,
    val failedSyncCount: Int = 0
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
    private val destinationRepository: DestinationRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    init {
        observeSessions()
        loadProjects()
        loadDestinations()
    }

    /**
     * Observe pending sessions - updates automatically when sessions change
     */
    private fun observeSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            sessionRepository.observeSessions().collectLatest { allSessions ->
                // Filter to only pending sessions (ended but not synced)
                val pendingSessions = allSessions.filter { it.endedAt != null }
                _uiState.update {
                    it.copy(
                        sessions = pendingSessions,
                        isLoading = false
                    )
                }
            }
        }
    }

    /**
     * Load pending sessions that haven't been synced yet
     */
    fun loadPendingSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val sessions = sessionRepository.getPendingSessions()
                _uiState.update {
                    it.copy(
                        sessions = sessions,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load sessions: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Observe projects for the dropdown - updates when projects change
     */
    private fun loadProjects() {
        viewModelScope.launch {
            projectRepository.observeProjects().collectLatest { projects ->
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
     * Load available destinations for the destination selector
     */
    private fun loadDestinations() {
        viewModelScope.launch {
            val destinations = destinationRepository?.getAllDestinations() ?: emptyList()
            val mainDest = destinationRepository?.mainDestination?.value
            _uiState.update { state ->
                state.copy(
                    availableDestinations = destinations,
                    selectedDestinations = if (mainDest != null) listOf(mainDest) else emptyList()
                )
            }
        }
    }

    /**
     * Update the selected destinations list
     */
    fun updateSelectedDestinations(destinations: List<String>) {
        _uiState.update { it.copy(selectedDestinations = destinations) }
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
     * Update the filename for sync
     */
    fun updateFilename(filename: String) {
        _uiState.update { it.copy(filename = filename) }
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
    }

    /**
     * Select all chunks in a session
     */
    fun selectAllChunksInSession(session: Session) {
        _uiState.update { state ->
            val chunkIds = session.chunks.map { it.id }.toSet()
            state.copy(selectedChunkIds = state.selectedChunkIds + chunkIds)
        }
    }

    /**
     * Deselect all chunks in a session
     */
    fun deselectAllChunksInSession(session: Session) {
        _uiState.update { state ->
            val chunkIds = session.chunks.map { it.id }.toSet()
            state.copy(selectedChunkIds = state.selectedChunkIds - chunkIds)
        }
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
    }

    /**
     * Clear all chunk selections
     */
    fun clearSelection() {
        _uiState.update { it.copy(selectedChunkIds = emptySet()) }
    }

    /**
     * Delete an individual chunk
     */
    fun deleteChunk(chunk: Chunk) {
        viewModelScope.launch {
            try {
                sessionRepository.deleteChunk(chunk.sessionId, chunk.id)
                _uiState.update { state ->
                    val updatedSessions = state.sessions.map { session ->
                        if (session.id == chunk.sessionId) {
                            session.copy(chunks = session.chunks.filter { it.id != chunk.id })
                        } else {
                            session
                        }
                    }.filter { it.chunks.isNotEmpty() }

                    state.copy(
                        sessions = updatedSessions,
                        selectedChunkIds = state.selectedChunkIds - chunk.id
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to delete chunk: ${e.message}")
                }
            }
        }
    }

    /**
     * Delete an entire session with all its chunks
     */
    fun deleteSession(session: Session) {
        viewModelScope.launch {
            try {
                sessionRepository.deleteSession(session.id)
                _uiState.update { state ->
                    val chunkIds = session.chunks.map { it.id }.toSet()
                    state.copy(
                        sessions = state.sessions.filter { it.id != session.id },
                        selectedChunkIds = state.selectedChunkIds - chunkIds
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to delete session: ${e.message}")
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
            if (state.filename.isBlank()) {
                _uiState.update { it.copy(error = "Please enter a filename") }
                return@launch
            }

            _uiState.update { it.copy(syncStatus = SyncStatus.Queued) }

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

                for ((index, session) in sessionsToSync.withIndex()) {
                    _uiState.update {
                        it.copy(syncStatus = SyncStatus.InProgress((index.toFloat()) / totalSessions))
                    }

                    val result = syncRepository.syncSession(
                        sessionId = session.id,
                        projectId = state.selectedProject.id,
                        filename = state.filename
                    )

                    when (result) {
                        is SyncStatus.Success -> {
                            syncedCount++
                            // Delete successfully synced session
                            sessionRepository.deleteSession(session.id)
                        }
                        is SyncStatus.PartialSuccess -> {
                            syncedCount++
                            failedCount += result.failedCount
                            // Still delete the session - partial success means some chunks synced
                            sessionRepository.deleteSession(session.id)
                        }
                        is SyncStatus.Error -> failedCount++
                        else -> {}
                    }
                }

                val finalStatus = when {
                    failedCount == 0 && syncedCount > 0 -> SyncStatus.Success
                    syncedCount == 0 -> SyncStatus.Error("All sessions failed to sync")
                    else -> SyncStatus.PartialSuccess(syncedCount, failedCount)
                }

                _uiState.update { it.copy(syncStatus = finalStatus) }

                // If successful, refresh the session list
                if (finalStatus == SyncStatus.Success || finalStatus is SyncStatus.PartialSuccess) {
                    loadPendingSessions()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(syncStatus = SyncStatus.Error(e.message ?: "Unknown error"))
                }
            }
        }
    }

    /**
     * Reset sync status to idle
     */
    fun resetSyncStatus() {
        _uiState.update { it.copy(syncStatus = SyncStatus.Idle) }
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
