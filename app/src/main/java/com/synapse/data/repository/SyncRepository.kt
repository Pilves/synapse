package com.synapse.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.synapse.api.ChunkData
import com.synapse.api.QuestionAnswerService
import com.synapse.api.TranscriptionError
import com.synapse.api.TranscriptionService
import com.synapse.model.CapturedContext
import com.synapse.model.LlmConfig
import com.synapse.data.storage.ChunkStorage
import com.synapse.data.storage.ProjectStorage
import com.synapse.data.storage.SessionStorage
import com.synapse.data.storage.SyncStorage
import com.synapse.model.Chunk
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
        private const val MAX_CHUNKS_PER_REQUEST = 10
        private const val MAX_BATCH_BYTES = 50L * 1024 * 1024 // 50MB
        private const val NETWORK_STABILIZATION_DELAY_MS = 3000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)

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

                when {
                    // Context-only segment: write context text as markdown quotes,
                    // but send RegionImage contexts through vision LLM for transcription
                    segment.contexts.isNotEmpty() && segment.chunks.isEmpty() -> {
                        val sb = StringBuilder()
                        for (ctx in segment.contexts) {
                            if (ctx is CapturedContext.RegionImage && transcriptionService != null) {
                                // Send screenshot image to LLM for transcription
                                val transcribed = transcribeRegionImage(ctx, transcriptionService)
                                if (transcribed != null) {
                                    sb.append(transcribed)
                                    sb.append("\n\n")
                                } else {
                                    sb.append("> (image transcription failed)\n\n")
                                }
                            } else {
                                val text = contextToText(ctx)
                                if (transcriptionService != null) {
                                    val formatted = formatContextText(text, transcriptionService)
                                    sb.append(formatted)
                                    sb.append("\n\n")
                                } else {
                                    sb.append("> $text\n\n")
                                }
                            }
                        }
                        segmentResults.add(sb.toString())
                        Log.d(TAG, "Segment $segIndex: context-only (${segment.contexts.size} contexts)")
                    }

                    // Chunk-only segment: transcribe and write
                    segment.contexts.isEmpty() && segment.chunks.isNotEmpty() -> {
                        val result = transcribeChunks(
                            sessionId, segment.chunks, requireNotNull(transcriptionService) { "Transcription service not available" }
                        )
                        failedCount += result.failedCount
                        if (result.notes.isNotEmpty()) {
                            segmentResults.add(result.notes.joinToString("\n\n"))
                        }
                        if (result.lastError != null) {
                            lastTranscriptionError = result.lastError
                        }
                        Log.d(TAG, "Segment $segIndex: chunks-only (${segment.chunks.size} chunks, ${result.failedCount} failed, error=${result.lastError})")
                    }

                    // Context + Chunks segment: Q&A flow
                    segment.contexts.isNotEmpty() && segment.chunks.isNotEmpty() -> {
                        val hasImageContext = segment.contexts.any { it is CapturedContext.RegionImage }

                        // Load chunk image bytes for sending directly to the vision LLM (with size cap)
                        val chunkImageBytes = mutableListOf<ByteArray>()
                        if (hasImageContext) {
                            var cumulativeSize = 0L
                            for (chunk in segment.chunks.filter { !it.isCorrupted }) {
                                if (cumulativeSize >= MAX_BATCH_BYTES) {
                                    Log.w(TAG, "Chunk image batch size cap reached ($cumulativeSize bytes), skipping remaining")
                                    break
                                }
                                val bytes = chunkStorage.loadChunkBytes(sessionId, chunk.id) ?: continue
                                cumulativeSize += bytes.size
                                chunkImageBytes.add(bytes)
                            }
                        }

                        // Try transcribing chunks to text (for the question)
                        val transcribeResult = transcribeChunks(
                            sessionId, segment.chunks, requireNotNull(transcriptionService) { "Transcription service not available" }
                        )
                        val notes = transcribeResult.notes
                        val failed = transcribeResult.failedCount
                        if (transcribeResult.lastError != null) {
                            lastTranscriptionError = transcribeResult.lastError
                        }
                        // Track failed transcriptions, but if we send images directly
                        // and get an answer, we'll subtract them back
                        failedCount += failed
                        val chunkFailedCount = failed

                        var answerText: String? = null
                        Log.d(TAG, "Segment $segIndex: qaService=${questionAnswerService != null}, configProvider=${llmConfigProvider != null}, notes=${notes.size}, failed=$failed, chunkImages=${chunkImageBytes.size}")
                        try {
                        if (questionAnswerService != null && llmConfigProvider != null) {
                            val llmConfig = llmConfigProvider.invoke()
                            Log.d(TAG, "Segment $segIndex: llmConfig=${llmConfig != null}")
                            if (llmConfig != null) {
                                val question = notes.joinToString(" ")
                                val sendImages = if (hasImageContext && chunkImageBytes.isNotEmpty()) chunkImageBytes.map { it.copyOf() } else emptyList()
                                Log.d(TAG, "Segment $segIndex: question='${question.take(80)}', sendImages=${sendImages.size}, hasImageCtx=$hasImageContext")

                                if (question.isNotBlank() || sendImages.isNotEmpty()) {
                                    try {
                                        val rawAnswer = questionAnswerService.answerQuestion(
                                            question = question,
                                            config = llmConfig,
                                            contexts = segment.contexts,
                                            additionalImages = sendImages
                                        )
                                        if (OutputSanitizer.isLlmErrorContent(rawAnswer)) {
                                            Log.w(TAG, "Segment $segIndex Q&A returned LLM error content: ${rawAnswer.take(80)}")
                                        } else {
                                            answerText = rawAnswer
                                        }
                                        Log.d(TAG, "Segment $segIndex Q&A answer: ${(answerText ?: rawAnswer).take(80)}")
                                        // If transcription failed but vision Q&A succeeded,
                                        // don't count those chunks as failed
                                        if (chunkFailedCount > 0 && sendImages.isNotEmpty()) {
                                            failedCount -= chunkFailedCount
                                            Log.d(TAG, "Segment $segIndex: recovered $chunkFailedCount failed chunks via vision Q&A")
                                        }
                                    } catch (e: TranscriptionError) {
                                        Log.e(TAG, "Segment $segIndex Q&A transcription failed", e)
                                    } catch (e: IOException) {
                                        Log.e(TAG, "Segment $segIndex Q&A I/O failed", e)
                                    } catch (e: IllegalStateException) {
                                        Log.e(TAG, "Segment $segIndex Q&A illegal state", e)
                                    }
                                }
                            }
                        }
                        } finally {
                            // Safe to clear now — sendImages holds deep copies of the byte arrays,
                            // so the QuestionAnswerService is not affected by this clear.
                            chunkImageBytes.clear()
                        }

                        val content = buildString {
                            if (answerText != null) {
                                // LLM produced a result — output it directly
                                append(answerText)
                                append("\n\n")
                            } else {
                                // Fallback: no LLM answer, show raw context + notes
                                for (ctx in segment.contexts) {
                                    append("> ${contextToText(ctx)}\n\n")
                                }
                                notes.forEach { note ->
                                    append(note)
                                    append("\n\n")
                                }
                            }
                        }
                        segmentResults.add(content)
                        Log.d(TAG, "Segment $segIndex: Q&A (${segment.contexts.size} contexts, ${segment.chunks.size} chunks, ${chunkImageBytes.size} chunk images sent)")
                    }
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
     * Extracts display text from a CapturedContext.
     */
    private fun contextToText(ctx: CapturedContext): String = when (ctx) {
        is CapturedContext.RegionText -> ctx.text
        is CapturedContext.SelectedText -> ctx.text
        is CapturedContext.AutoContext -> buildString {
            append(ctx.sourceApp)
            if (ctx.sourceUrl != null) append(" (${ctx.sourceUrl})")
            if (ctx.pageTitle != null) append(" - ${ctx.pageTitle}")
        }
        is CapturedContext.RegionImage -> ctx.description ?: "(image)"
    }

    /**
     * Sends a RegionImage to the LLM vision API for transcription.
     * Returns the transcribed text, or null if transcription fails.
     */
    private suspend fun transcribeRegionImage(
        ctx: CapturedContext.RegionImage,
        transcriptionService: TranscriptionService
    ): String? {
        return try {
            val file = File(ctx.imagePath)
            if (!file.exists() || !file.canRead()) {
                Log.w(TAG, "RegionImage file not accessible: ${ctx.imagePath}")
                return null
            }
            val imageBytes = file.readBytes()
            if (imageBytes.size < 1024) {
                Log.w(TAG, "RegionImage file too small (${imageBytes.size} bytes), likely corrupt: ${ctx.imagePath}")
                return null
            }

            val result = transcriptionService.visionQuery(
                SyncPrompts.REGION_IMAGE_TRANSCRIPTION_PROMPT, listOf(imageBytes)
            )
            if (OutputSanitizer.isLlmErrorContent(result)) {
                Log.w(TAG, "RegionImage transcription returned LLM error content: ${result.take(80)}")
                return null
            }
            Log.d(TAG, "RegionImage transcribed: ${result.take(80)}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to transcribe RegionImage: ${ctx.imagePath}", e)
            null
        }
    }

    /**
     * Sends raw captured text through the LLM to format it as clean markdown.
     * Falls back to a blockquote if the LLM call fails.
     */
    private suspend fun formatContextText(
        rawText: String,
        transcriptionService: TranscriptionService
    ): String {
        return try {
            transcriptionService.textQuery(rawText, SyncPrompts.CONTEXT_FORMAT_SYSTEM_PROMPT)
        } catch (e: Exception) {
            Log.w(TAG, "Context text formatting failed, using raw", e)
            "> $rawText"
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
     * Result of transcribing chunks: notes, failed count, and optional error message.
     */
    private data class TranscribeResult(
        val notes: List<String>,
        val failedCount: Int,
        val lastError: String? = null
    )

    /**
     * Transcribes a list of chunks and returns the transcribed notes, failed count,
     * and the last error message (if any chunks failed).
     */
    private suspend fun transcribeChunks(
        sessionId: String,
        chunks: List<Chunk>,
        transcriptionService: TranscriptionService
    ): TranscribeResult {
        val chunkDataList = mutableListOf<ChunkData>()
        val validChunks = chunks.filter { !it.isCorrupted }

        // Load sequentially — local disk I/O doesn't benefit from parallelism
        // and sequential loading reduces peak memory allocation
        for ((index, chunk) in validChunks.withIndex()) {
            val imageBytes = chunkStorage.loadChunkBytes(sessionId, chunk.id)
            if (imageBytes != null) {
                chunkDataList.add(
                    ChunkData(
                        image = imageBytes,
                        timestampSeconds = chunk.timestampSeconds,
                        index = index
                    )
                )
            } else {
                Log.w(TAG, "Failed to load chunk image: ${chunk.id}")
            }
        }

        if (chunkDataList.isEmpty()) {
            Log.e(TAG, "All ${chunks.size} chunk images failed to load from disk")
            return TranscribeResult(emptyList(), chunks.size, "Failed to load chunk images from disk")
        }

        val transcribedNotes = mutableListOf<String>()
        var failedCount = 0
        var lastError: String? = null

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
            } catch (e: TranscriptionError) {
                Log.e(TAG, "Transcription error on batch $batchIndex", e)
                failedCount += batch.size
                lastError = e.message ?: e::class.simpleName
            }
        }

        return TranscribeResult(transcribedNotes, failedCount, lastError)
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
            val projectUri = Uri.parse(projectPathUri)
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
