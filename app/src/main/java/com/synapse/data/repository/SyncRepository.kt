package com.synapse.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.synapse.api.ChunkData
import com.synapse.api.TranscriptionError
import com.synapse.api.TranscriptionService
import com.synapse.data.storage.ChunkStorage
import com.synapse.data.storage.ProjectStorage
import com.synapse.data.storage.SessionStorage
import com.synapse.data.storage.SyncItemStatus
import com.synapse.data.storage.SyncStorage
import com.synapse.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Repository interface for managing sync operations.
 *
 * Handles the process of transcribing session chunks using LLM services
 * and writing the results to project files.
 */
interface SyncRepository {

    /**
     * Syncs a session to a project file.
     *
     * This performs the full sync operation:
     * 1. Load chunks from the session
     * 2. Transcribe using the configured LLM service
     * 3. Append results to the target file
     *
     * @param sessionId The session to sync
     * @param projectId The target project
     * @param filename The target filename
     * @return The sync status result
     */
    suspend fun syncSession(sessionId: String, projectId: String, filename: String): SyncStatus

    /**
     * Queues a session for sync without blocking.
     *
     * The sync will be processed in the background.
     *
     * @param sessionId The session to sync
     * @param projectId The target project
     * @param filename The target filename
     */
    suspend fun queueForSync(sessionId: String, projectId: String, filename: String)

    /**
     * Retries all failed sync operations.
     */
    suspend fun retryFailed()

    /**
     * Observes the current sync status.
     *
     * @return Flow of sync status updates
     */
    fun observeSyncStatus(): Flow<SyncStatus>
}

/**
 * Default implementation of SyncRepository.
 *
 * Coordinates between storage layers and transcription services
 * to perform sync operations.
 */
class SyncRepositoryImpl(
    private val context: Context,
    private val sessionStorage: SessionStorage,
    private val chunkStorage: ChunkStorage,
    private val projectStorage: ProjectStorage,
    private val syncStorage: SyncStorage,
    private val transcriptionServiceProvider: () -> TranscriptionService?
) : SyncRepository {

    companion object {
        private const val TAG = "SyncRepository"
        private const val MAX_CHUNKS_PER_REQUEST = 10
    }

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)

    override suspend fun syncSession(sessionId: String, projectId: String, filename: String): SyncStatus {
        _syncStatus.value = SyncStatus.InProgress(0f)

        return try {
            // Get session and validate
            val session = sessionStorage.getSession(sessionId)
            if (session == null) {
                Log.e(TAG, "Session not found: $sessionId")
                val error = SyncStatus.Error("Session not found")
                _syncStatus.value = error
                return error
            }

            if (session.chunks.isEmpty()) {
                Log.w(TAG, "Session has no chunks: $sessionId")
                val error = SyncStatus.Error("Session has no chunks to sync")
                _syncStatus.value = error
                return error
            }

            // Get project and validate
            val project = projectStorage.getProject(projectId)
            if (project == null) {
                Log.e(TAG, "Project not found: $projectId")
                val error = SyncStatus.Error("Project not found")
                _syncStatus.value = error
                return error
            }

            // Get transcription service
            val transcriptionService = transcriptionServiceProvider()
            if (transcriptionService == null || !transcriptionService.isConfigured()) {
                Log.e(TAG, "Transcription service not configured")
                val error = SyncStatus.Error("LLM service not configured")
                _syncStatus.value = error
                return error
            }

            // Load chunk images
            _syncStatus.value = SyncStatus.InProgress(0.1f)
            val chunkDataList = mutableListOf<ChunkData>()
            val validChunks = session.chunks.filter { !it.isCorrupted }

            for ((index, chunk) in validChunks.withIndex()) {
                val imageBytes = chunkStorage.loadChunkBytes(sessionId, chunk.id)
                if (imageBytes != null) {
                    chunkDataList.add(ChunkData(
                        image = imageBytes,
                        timestampSeconds = chunk.timestampSeconds,
                        index = index
                    ))
                } else {
                    Log.w(TAG, "Failed to load chunk image: ${chunk.id}")
                }
            }

            if (chunkDataList.isEmpty()) {
                Log.e(TAG, "No valid chunks to transcribe")
                val error = SyncStatus.Error("No valid chunks to transcribe")
                _syncStatus.value = error
                return error
            }

            // Transcribe in batches
            _syncStatus.value = SyncStatus.InProgress(0.2f)
            val transcribedNotes = mutableListOf<String>()
            var failedCount = 0
            val totalBatches = (chunkDataList.size + MAX_CHUNKS_PER_REQUEST - 1) / MAX_CHUNKS_PER_REQUEST

            chunkDataList.chunked(MAX_CHUNKS_PER_REQUEST).forEachIndexed { batchIndex, batch ->
                try {
                    val result = transcriptionService.transcribe(
                        chunks = batch,
                        cleanupEnabled = true,
                        advancedFormatting = false
                    )

                    transcribedNotes.addAll(result.notes.map { it.text })
                    failedCount += result.failedChunks.size

                    // Update progress
                    val progress = 0.2f + (0.6f * (batchIndex + 1) / totalBatches)
                    _syncStatus.value = SyncStatus.InProgress(progress)
                } catch (e: TranscriptionError) {
                    Log.e(TAG, "Transcription error on batch $batchIndex", e)
                    failedCount += batch.size
                }
            }

            if (transcribedNotes.isEmpty()) {
                Log.e(TAG, "All chunks failed to transcribe")
                val error = SyncStatus.Error("Transcription failed for all chunks")
                _syncStatus.value = error
                return error
            }

            // Write to file
            _syncStatus.value = SyncStatus.InProgress(0.85f)
            val writeSuccess = writeToProjectFile(project.pathUri, filename, transcribedNotes)

            if (!writeSuccess) {
                val error = SyncStatus.Error("Failed to write to file")
                _syncStatus.value = error
                return error
            }

            // Update last used file
            projectStorage.setLastUsedFile(projectId, filename)

            // Determine final status
            _syncStatus.value = SyncStatus.InProgress(1f)
            val finalStatus = if (failedCount > 0) {
                SyncStatus.PartialSuccess(
                    syncedCount = chunkDataList.size - failedCount,
                    failedCount = failedCount
                )
            } else {
                SyncStatus.Success
            }

            _syncStatus.value = finalStatus
            Log.d(TAG, "Sync completed: $finalStatus")
            finalStatus

        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            val error = SyncStatus.Error(e.message ?: "Unknown error")
            _syncStatus.value = error
            error
        }
    }

    override suspend fun queueForSync(sessionId: String, projectId: String, filename: String) {
        syncStorage.addToQueue(sessionId, projectId, filename)
        _syncStatus.value = SyncStatus.Queued
    }

    override suspend fun retryFailed() {
        val resetCount = syncStorage.resetFailedItems()
        if (resetCount > 0) {
            Log.d(TAG, "Reset $resetCount failed items for retry")
            processQueue()
        }
    }

    override fun observeSyncStatus(): Flow<SyncStatus> = _syncStatus.asStateFlow()

    /**
     * Processes pending items in the sync queue.
     */
    private suspend fun processQueue() {
        val pendingItems = syncStorage.getPendingItems()
        if (pendingItems.isEmpty()) return

        for (item in pendingItems) {
            syncStorage.markInProgress(item.id)

            val result = syncSession(item.sessionId, item.projectId, item.filename)

            when (result) {
                is SyncStatus.Success, is SyncStatus.PartialSuccess -> {
                    syncStorage.markCompleted(item.id)
                }
                is SyncStatus.Error -> {
                    syncStorage.markFailed(item.id, result.message)
                }
                else -> {
                    // Keep as in progress
                }
            }
        }
    }

    /**
     * Writes transcribed notes to a project file using SAF.
     */
    private suspend fun writeToProjectFile(
        projectPathUri: String,
        filename: String,
        notes: List<String>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val projectUri = Uri.parse(projectPathUri)
            val projectDir = DocumentFile.fromTreeUri(context, projectUri)
                ?: return@withContext false

            // Find or create the target file
            var targetFile = projectDir.findFile(filename)
            if (targetFile == null) {
                val mimeType = when {
                    filename.endsWith(".md") -> "text/markdown"
                    filename.endsWith(".txt") -> "text/plain"
                    else -> "text/plain"
                }
                targetFile = projectDir.createFile(mimeType, filename)
            }

            if (targetFile == null) {
                Log.e(TAG, "Failed to create/find file: $filename")
                return@withContext false
            }

            // Build content to append
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date())
            val header = "\n\n---\n\n## Notes - $timestamp\n\n"
            val content = buildString {
                append(header)
                notes.forEach { note ->
                    append(note)
                    append("\n\n")
                }
            }

            // Append to file
            context.contentResolver.openOutputStream(targetFile.uri, "wa")?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: return@withContext false

            Log.d(TAG, "Successfully wrote ${notes.size} notes to $filename")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to project file", e)
            false
        }
    }
}
