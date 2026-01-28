package com.synapse.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synapse.model.Chunk
import com.synapse.model.Project
import com.synapse.model.Session
import com.synapse.model.SyncStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val filename: String = "quick-notes.md",
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val selectedChunkIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val previewChunk: Chunk? = null
)

/**
 * ViewModel for the Review screen
 *
 * Manages pending sessions and chunks, view mode toggle, project selection,
 * filename input, sync status, and deletion of chunks/sessions.
 */
class ReviewViewModel(
    // TODO: Inject repositories when available
    // private val sessionRepository: SessionRepository,
    // private val projectRepository: ProjectRepository,
    // private val syncRepository: SyncRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    init {
        loadPendingSessions()
        loadProjects()
    }

    /**
     * Load pending sessions that haven't been synced yet
     */
    fun loadPendingSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // TODO: Replace with actual repository call
                // val sessions = sessionRepository.getPendingSessions()
                val sessions = getMockSessions()
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
     * Load available projects for the dropdown
     */
    private fun loadProjects() {
        viewModelScope.launch {
            try {
                // TODO: Replace with actual repository call
                // val projects = projectRepository.getProjects()
                val projects = getMockProjects()
                _uiState.update {
                    it.copy(
                        projects = projects,
                        selectedProject = projects.firstOrNull()
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to load projects: ${e.message}")
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
                // TODO: Replace with actual repository call
                // sessionRepository.deleteChunk(chunk.id)
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
                // TODO: Replace with actual repository call
                // sessionRepository.deleteSession(session.id)
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
     * Sync all pending chunks
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
                val chunksToSync = if (state.viewMode == ViewMode.SEPARATE && state.selectedChunkIds.isNotEmpty()) {
                    // Sync only selected chunks
                    state.sessions.flatMap { it.chunks }.filter { it.id in state.selectedChunkIds }
                } else {
                    // Sync all chunks
                    state.sessions.flatMap { it.chunks }
                }

                if (chunksToSync.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            syncStatus = SyncStatus.Idle,
                            error = "No chunks to sync"
                        )
                    }
                    return@launch
                }

                val totalChunks = chunksToSync.size
                var syncedCount = 0
                var failedCount = 0

                for ((index, chunk) in chunksToSync.withIndex()) {
                    _uiState.update {
                        it.copy(syncStatus = SyncStatus.InProgress((index + 1).toFloat() / totalChunks))
                    }

                    // TODO: Replace with actual sync call
                    // val result = syncRepository.syncChunk(chunk, state.selectedProject!!, state.filename)
                    delay(500) // Simulate network delay
                    val success = !chunk.isCorrupted // Simulate: corrupted chunks fail

                    if (success) {
                        syncedCount++
                    } else {
                        failedCount++
                    }
                }

                val finalStatus = when {
                    failedCount == 0 -> SyncStatus.Success
                    syncedCount == 0 -> SyncStatus.Error("All chunks failed to sync")
                    else -> SyncStatus.PartialSuccess(syncedCount, failedCount)
                }

                _uiState.update { it.copy(syncStatus = finalStatus) }

                // If successful, remove synced chunks from the list
                if (finalStatus == SyncStatus.Success) {
                    delay(1500) // Show success briefly
                    loadPendingSessions() // Refresh the list
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
     * Get the timestamp offset for a chunk relative to session start
     */
    fun getChunkOffset(session: Session, chunk: Chunk): Float {
        val sessionStartSeconds = session.startedAt / 1000f
        return chunk.timestampSeconds - sessionStartSeconds
    }

    // Mock data for testing
    private fun getMockSessions(): List<Session> {
        val now = System.currentTimeMillis()
        return listOf(
            Session(
                id = "session1",
                startedAt = now - 3600000, // 1 hour ago
                endedAt = now - 3000000,
                chunks = listOf(
                    Chunk("chunk1", "session1", 0, "/storage/chunk1.jpg", 0f, now - 3600000),
                    Chunk("chunk2", "session1", 1, "/storage/chunk2.jpg", 4f, now - 3596000),
                    Chunk("chunk3", "session1", 2, "/storage/chunk3.jpg", 8f, now - 3592000),
                    Chunk("chunk4", "session1", 3, "/storage/chunk4.jpg", 12f, now - 3588000, isCorrupted = true)
                )
            ),
            Session(
                id = "session2",
                startedAt = now - 1800000, // 30 mins ago
                endedAt = now - 1200000,
                chunks = listOf(
                    Chunk("chunk5", "session2", 0, "/storage/chunk5.jpg", 0f, now - 1800000),
                    Chunk("chunk6", "session2", 1, "/storage/chunk6.jpg", 4f, now - 1796000),
                    Chunk("chunk7", "session2", 2, "/storage/chunk7.jpg", 8f, now - 1792000)
                )
            )
        )
    }

    private fun getMockProjects(): List<Project> {
        return listOf(
            Project("proj1", "Work Notes", "/storage/projects/work", "notes.md", "meeting-notes.md"),
            Project("proj2", "Personal Journal", "/storage/projects/personal", "journal.md"),
            Project("proj3", "Research", "/storage/projects/research", "research.md")
        )
    }
}
