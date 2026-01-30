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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val llmConfigProvider: (suspend () -> LlmConfig?)? = null
) : SyncRepository {

    companion object {
        private const val TAG = "SyncRepository"
        private const val MAX_CHUNKS_PER_REQUEST = 10
        private const val MAX_BATCH_BYTES = 50L * 1024 * 1024 // 50MB
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)

    /**
     * Represents a time-ordered item in a session: either a chunk or a context.
     */
    private sealed class SessionItem(val timestamp: Long) {
        class ChunkItem(val chunk: Chunk, ts: Long) : SessionItem(ts)
        class ContextItem(val context: CapturedContext, ts: Long) : SessionItem(ts)
    }

    /**
     * A segment is a logical grouping of session items separated by context boundaries.
     * - Context-only segments: written as markdown quotes (no LLM call)
     * - Chunk-only segments: transcribed and written
     * - Context + chunk segments: Q&A flow (context is the reference, chunks are the question)
     */
    private data class Segment(
        val contexts: List<CapturedContext> = emptyList(),
        val chunks: List<Chunk> = emptyList()
    )

    /**
     * Segments a session's flat lists of chunks and contexts into logical groups
     * based on timestamp ordering. Each new context starts a new segment.
     */
    private fun segmentSession(
        chunks: List<Chunk>,
        contexts: List<CapturedContext>
    ): List<Segment> {
        if (chunks.isEmpty() && contexts.isEmpty()) return emptyList()

        // Merge into a single timeline sorted by timestamp
        val items = mutableListOf<SessionItem>()
        chunks.forEach { items.add(SessionItem.ChunkItem(it, it.createdAt)) }
        contexts.forEach { items.add(SessionItem.ContextItem(it, it.timestamp)) }
        items.sortBy { it.timestamp }

        val segments = mutableListOf<Segment>()
        var currentContexts = mutableListOf<CapturedContext>()
        var currentChunks = mutableListOf<Chunk>()

        for (item in items) {
            when (item) {
                is SessionItem.ContextItem -> {
                    // A new context starts a new segment if we have accumulated content
                    if (currentContexts.isNotEmpty() || currentChunks.isNotEmpty()) {
                        segments.add(Segment(currentContexts, currentChunks))
                        currentContexts = mutableListOf()
                        currentChunks = mutableListOf()
                    }
                    currentContexts.add(item.context)
                }
                is SessionItem.ChunkItem -> {
                    currentChunks.add(item.chunk)
                }
            }
        }

        // Don't forget the last segment
        if (currentContexts.isNotEmpty() || currentChunks.isNotEmpty()) {
            segments.add(Segment(currentContexts, currentChunks))
        }

        return segments
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

            // Get transcription service (needed if any segment has chunks or RegionImage contexts)
            val hasImages = session.contexts.any { it is CapturedContext.RegionImage }
            val needsLlm = session.chunks.isNotEmpty() || hasImages
            val transcriptionService = if (needsLlm) {
                val service = transcriptionServiceProvider()
                if (service == null || !service.isConfigured()) {
                    Log.e(TAG, "Transcription service not configured")
                    val error = SyncStatus.Error("LLM service not configured")
                    _syncStatus.value = error
                    return@withContext error
                }
                service
            } else null

            // Segment the session by timestamp
            val segments = segmentSession(session.chunks, session.contexts)
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
                                sb.append("> $text\n\n")
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
                            val loadedImageBytes = coroutineScope {
                                segment.chunks.filter { !it.isCorrupted }.map { chunk ->
                                    async {
                                        chunkStorage.loadChunkBytes(sessionId, chunk.id)
                                    }
                                }.map { it.await() }
                            }
                            var cumulativeSize = 0L
                            for (bytes in loadedImageBytes) {
                                if (bytes == null) continue
                                if (cumulativeSize >= MAX_BATCH_BYTES) {
                                    Log.w(TAG, "Chunk image batch size cap reached ($cumulativeSize bytes), skipping remaining")
                                    break
                                }
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
                                val sendImages = if (hasImageContext && chunkImageBytes.isNotEmpty()) chunkImageBytes.toList() else emptyList()
                                Log.d(TAG, "Segment $segIndex: question='${question.take(80)}', sendImages=${sendImages.size}, hasImageCtx=$hasImageContext")

                                if (question.isNotBlank() || sendImages.isNotEmpty()) {
                                    try {
                                        answerText = questionAnswerService.answerQuestion(
                                            question = question,
                                            config = llmConfig,
                                            contexts = segment.contexts,
                                            additionalImages = sendImages
                                        )
                                        Log.d(TAG, "Segment $segIndex Q&A answer: ${answerText.take(80)}")
                                        // If transcription failed but vision Q&A succeeded,
                                        // don't count those chunks as failed
                                        if (chunkFailedCount > 0 && sendImages.isNotEmpty()) {
                                            failedCount -= chunkFailedCount
                                            Log.d(TAG, "Segment $segIndex: recovered $chunkFailedCount failed chunks via vision Q&A")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Segment $segIndex Q&A failed", e)
                                    }
                                }
                            }
                        }
                        } finally {
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
            if (imageBytes.isEmpty()) {
                Log.w(TAG, "RegionImage file is empty: ${ctx.imagePath}")
                return null
            }

            val prompt = "Transcribe all text and content visible in this screenshot. " +
                "Output clean markdown suitable for Obsidian notes. " +
                "If there are diagrams or charts, describe them. " +
                "Do not add any commentary — only output the transcribed content."

            val result = transcriptionService.visionQuery(prompt, listOf(imageBytes))
            Log.d(TAG, "RegionImage transcribed: ${result.take(80)}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to transcribe RegionImage: ${ctx.imagePath}", e)
            null
        }
    }

    /**
     * Sends assembled markdown through the LLM to clean up formatting for Obsidian.
     * Returns a single-element list with the polished content.
     */
    private suspend fun polishMarkdownFormatting(
        rawSegments: List<String>,
        transcriptionService: TranscriptionService
    ): List<String> {
        val rawContent = rawSegments.joinToString("\n---\n\n")

        val systemPrompt = """You are a final-pass editor for Obsidian notes. You receive raw markdown and must:
1. Fix formatting: spacing, headers, lists, code blocks (add language tags if missing), LaTeX, Mermaid.
2. Remove any meta-commentary that is not actual note content — phrases like "Here is the result", \
"The transcribed content is:", "I've summarized the following", or similar preamble/postamble. \
The output should read as if the user wrote it themselves.
3. Remove duplicate content — if the same text appears both as a quote and in the body, keep only the body version.
4. Do NOT change the actual meaning or substance of the content.
5. Return ONLY the cleaned markdown, nothing else."""

        val polished = transcriptionService.textQuery(rawContent, systemPrompt)
        return listOf(polished)
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

        val loadedChunks = coroutineScope {
            validChunks.mapIndexed { index, chunk ->
                async {
                    val imageBytes = chunkStorage.loadChunkBytes(sessionId, chunk.id)
                    if (imageBytes != null) {
                        ChunkData(
                            image = imageBytes,
                            timestampSeconds = chunk.timestampSeconds,
                            index = index
                        )
                    } else {
                        Log.w(TAG, "Failed to load chunk image: ${chunk.id}")
                        null
                    }
                }
            }.mapNotNull { it.await() }
        }
        chunkDataList.addAll(loadedChunks)

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
                        append("---\n\n")
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
