package com.synapse.data.repository

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.synapse.api.QuestionAnswerService
import com.synapse.api.TranscriptionService
import com.synapse.model.CapturedContext
import com.synapse.model.LlmConfig
import com.synapse.data.storage.ChunkStorage
import com.synapse.data.storage.ProjectStorage
import com.synapse.data.storage.SessionStorage
import com.synapse.data.storage.SyncStorage
import com.synapse.model.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.synapse.util.NetworkMonitor
import java.io.Closeable
import com.synapse.util.OutputSanitizer
import java.io.File
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
    private val transcriptionServiceProvider: suspend () -> TranscriptionService?,
    private val questionAnswerService: QuestionAnswerService? = null,
    private val llmConfigProvider: (suspend () -> LlmConfig?)? = null,
    private val networkMonitor: NetworkMonitor? = null
) : SyncRepository, Closeable {

    companion object {
        private const val TAG = "SyncRepository"
        private const val MAX_BATCH_BYTES = 50L * 1024 * 1024 // 50MB
        private const val NETWORK_STABILIZATION_DELAY_MS = 3000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)

    // Owns per-segment processing; an internal implementation detail (not wired via Koin).
    private val segmentTranscriber = SegmentTranscriber(
        chunkStorage = chunkStorage,
        questionAnswerService = questionAnswerService,
        llmConfigProvider = llmConfigProvider,
        maxBatchBytes = MAX_BATCH_BYTES
    )

    init {
        observeNetwork()
    }

    /**
     * Observes network connectivity and retries failed syncs when
     * connectivity is restored (offline -> online transition).
     *
     * A small delay is added after reconnection to let the network stabilize
     * before attempting retries.
     */
    private fun observeNetwork() {
        val monitor = networkMonitor ?: return
        scope.launch {
            monitor.isOnline
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    delay(NETWORK_STABILIZATION_DELAY_MS)
                    Log.d(TAG, "Network restored, retrying failed syncs")
                    retryFailed()
                }
        }
    }

    override suspend fun syncSession(sessionId: String, projectId: String, filename: String): SyncStatus = withContext(Dispatchers.IO) {
        _syncStatus.value = SyncStatus.InProgress(0f)

        try {
            // Get session and validate
            val session = sessionStorage.getSession(sessionId)
            if (session == null) {
                Log.e(TAG, "Session not found: $sessionId")
                val error = SyncStatus.Error("Session not found")
                _syncStatus.value = error
                return@withContext error
            }

            if (session.chunks.isEmpty() && session.contexts.isEmpty()) {
                Log.w(TAG, "Session has no chunks or contexts: $sessionId")
                val error = SyncStatus.Error("Session has no content to sync")
                _syncStatus.value = error
                return@withContext error
            }

            // Get project and validate
            val project = projectStorage.getProject(projectId)
            if (project == null) {
                Log.e(TAG, "Project not found: $projectId")
                val error = SyncStatus.Error("Project not found")
                _syncStatus.value = error
                return@withContext error
            }

            // Get transcription service (used for chunks, images, and context formatting)
            val transcriptionService = transcriptionServiceProvider()?.takeIf { it.isConfigured() }
            val hasImages = session.contexts.any { it is CapturedContext.RegionImage }
            val needsLlm = session.chunks.isNotEmpty() || hasImages
            if (needsLlm && transcriptionService == null) {
                Log.e(TAG, "Transcription service not configured")
                val error = SyncStatus.Error("LLM service not configured")
                _syncStatus.value = error
                return@withContext error
            }

            // Segment the session by timestamp
            val segments = SessionSegmenter.segmentSession(session.chunks, session.contexts)
            Log.d(TAG, "Session segmented into ${segments.size} segment(s)")

            _syncStatus.value = SyncStatus.InProgress(0.1f)
            var failedCount = 0
            var lastTranscriptionError: String? = null
            val segmentResults = mutableListOf<String>() // markdown content per segment

            for ((segIndex, segment) in segments.withIndex()) {
                val segProgress = 0.1f + 0.8f * segIndex / segments.size
                _syncStatus.value = SyncStatus.InProgress(segProgress)

                val output = segmentTranscriber.processSegment(
                    segIndex, segment, sessionId, transcriptionService
                )
                failedCount += output.failedDelta
                output.markdown?.let { segmentResults.add(it) }
                if (output.lastError != null) {
                    lastTranscriptionError = output.lastError
                }
            }

            if (segmentResults.isEmpty()) {
                val errorMsg = if (lastTranscriptionError != null) {
                    "Transcription failed: $lastTranscriptionError"
                } else {
                    "No content produced from session"
                }
                val error = SyncStatus.Error(errorMsg)
                _syncStatus.value = error
                return@withContext error
            }

            // Polish formatting via LLM (best-effort)
            val polishedResults = if (transcriptionService != null) {
                try {
                    polishMarkdownFormatting(segmentResults, transcriptionService)
                } catch (e: Exception) {
                    Log.w(TAG, "Formatting polish failed, using raw content", e)
                    segmentResults
                }
            } else {
                segmentResults
            }

            // Sanitize all content before writing to vault
            val sanitizedResults = polishedResults.map { OutputSanitizer.sanitize(it) }

            // Write all segments to file under one header
            _syncStatus.value = SyncStatus.InProgress(0.9f)
            val writeSuccess = writeSegmentsToProjectFile(
                project.pathUri, filename, sanitizedResults
            )

            if (!writeSuccess) {
                val error = SyncStatus.Error("Failed to write to file")
                _syncStatus.value = error
                return@withContext error
            }

            // Update last used file
            projectStorage.setLastUsedFile(projectId, filename)

            // Determine final status
            _syncStatus.value = SyncStatus.InProgress(1f)
            val totalChunks = session.chunks.size
            val finalStatus = if (failedCount > 0) {
                SyncStatus.PartialSuccess(
                    syncedCount = totalChunks - failedCount,
                    failedCount = failedCount
                )
            } else {
                SyncStatus.Success
            }

            // Clean up temporary screenshot files now that sync is done
            cleanupScreenshots()

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

    /**
     * Sends all segments in a single LLM call to clean up formatting for Obsidian.
     * Uses a delimiter to split the response back into individual segments.
     * Falls back to raw segments if the LLM call fails or returns unexpected output.
     */
    private suspend fun polishMarkdownFormatting(
        rawSegments: List<String>,
        transcriptionService: TranscriptionService
    ): List<String> {
        if (rawSegments.size == 1) {
            return try {
                listOf(transcriptionService.textQuery(rawSegments[0], SyncPrompts.POLISH_MARKDOWN_SYSTEM_PROMPT))
            } catch (e: Exception) {
                Log.w(TAG, "Segment polish failed, using raw", e)
                rawSegments
            }
        }

        // Batch all segments into a single call with a UUID-style delimiter
        // that is extremely unlikely to appear in LLM output
        val delimiter = "===8f3a9c7b-SEGMENT-BREAK-4e2d1a6f==="
        val combined = rawSegments.joinToString("\n$delimiter\n")
        val batchPrompt = "${SyncPrompts.POLISH_MARKDOWN_SYSTEM_PROMPT}\n\nIMPORTANT: The input contains multiple segments separated by '$delimiter'. Polish each segment independently and keep the '$delimiter' separators in your output."

        return try {
            val result = transcriptionService.textQuery(combined, batchPrompt)
            val polished = result.split(delimiter).map { it.trim() }
            if (polished.size == rawSegments.size) {
                polished
            } else {
                Log.w(TAG, "Batch polish returned ${polished.size} segments, expected ${rawSegments.size} — falling back to per-segment polish")
                rawSegments.map { segment ->
                    try {
                        transcriptionService.textQuery(segment, SyncPrompts.POLISH_MARKDOWN_SYSTEM_PROMPT)
                    } catch (e: Exception) {
                        Log.w(TAG, "Per-segment polish failed, using raw", e)
                        segment
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Batch polish failed, using raw", e)
            rawSegments
        }
    }

    /**
     * Writes all segment results to a project file under a single Notes header.
     */
    private suspend fun writeSegmentsToProjectFile(
        projectPathUri: String,
        filename: String,
        segmentResults: List<String>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val projectUri = projectPathUri.toUri()
            val projectDir = DocumentFile.fromTreeUri(context, projectUri)
                ?: return@withContext false

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

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date())

            val content = buildString {
                append("\n\n---\n\n## Notes - $timestamp\n\n")
                segmentResults.forEachIndexed { index, segmentContent ->
                    if (segmentResults.size > 1 && index > 0) {
                        append("\n---\n\n")
                    }
                    append(segmentContent)
                }
            }

            context.contentResolver.openOutputStream(targetFile.uri, "wa")?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: return@withContext false

            Log.d(TAG, "Successfully wrote ${segmentResults.size} segment(s) to $filename")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to project file", e)
            false
        }
    }

    override suspend fun queueForSync(sessionId: String, projectId: String, filename: String) {
        syncStorage.addToQueue(sessionId, projectId, filename)
        _syncStatus.value = SyncStatus.Queued
        // Kick off background processing
        scope.launch { processQueue() }
    }

    override suspend fun retryFailed() {
        val resetCount = syncStorage.resetFailedItems()
        if (resetCount > 0) {
            Log.d(TAG, "Reset $resetCount failed items for retry")
            processQueue()
        }
    }

    override fun observeSyncStatus(): Flow<SyncStatus> = _syncStatus.asStateFlow()

    override fun close() {
        scope.cancel()
    }

    /**
     * Deletes temporary screenshot files from internal storage.
     */
    private fun cleanupScreenshots() {
        try {
            val dir = File(context.filesDir, "screenshots")
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles() ?: return
                var deleted = 0
                for (file in files) {
                    if (file.delete()) deleted++
                }
                if (deleted > 0) Log.d(TAG, "Cleaned up $deleted temporary screenshot(s) after sync")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up screenshots", e)
        }
    }

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

}
