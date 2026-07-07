package com.synapse.data.repository

import android.util.Log
import com.synapse.api.ChunkData
import com.synapse.api.QuestionAnswerService
import com.synapse.api.TranscriptionError
import com.synapse.api.TranscriptionService
import java.io.IOException
import com.synapse.model.CapturedContext
import com.synapse.model.LlmConfig
import com.synapse.data.storage.ChunkStorage
import com.synapse.model.Chunk
import com.synapse.util.OutputSanitizer
import java.io.File

/**
 * Turns a single [Segment] into a markdown string plus an explicit accounting result.
 *
 * Extracted from [SyncRepositoryImpl.syncSession] so that method reads as an orchestrator.
 * This class owns everything specific to processing one segment: the three-way
 * context/chunks/Q&A branching, chunk transcription, region-image and context-text
 * formatting via the vision/text LLM, and the failed-chunk accounting (including the
 * vision-recovery subtraction).
 *
 * The failed-chunk accounting is returned in [SegmentOutput.failedDelta] rather than
 * mutated onto shared outer state, so the orchestrator simply sums the deltas.
 */
class SegmentTranscriber(
    private val chunkStorage: ChunkStorage,
    private val questionAnswerService: QuestionAnswerService?,
    private val llmConfigProvider: (suspend () -> LlmConfig?)?,
    private val maxBatchBytes: Long
) {

    companion object {
        private const val TAG = "SyncRepository"
        private const val MAX_CHUNKS_PER_REQUEST = 10
    }

    /**
     * Result of processing one segment.
     *
     * @param markdown the segment's rendered markdown, or null if the segment produced
     *   no content to write (chunk-only segment whose transcription yielded no notes).
     * @param failedDelta the net change to the running failed-chunk count for this segment
     *   (already includes any vision-recovery subtraction).
     * @param lastError the last transcription error message for this segment, or null.
     */
    data class SegmentOutput(
        val markdown: String?,
        val failedDelta: Int,
        val lastError: String?
    )

    /**
     * Processes one segment and returns its markdown plus explicit accounting.
     *
     * @param segIndex the segment's index within the session (for logging parity).
     * @param segment the segment to process.
     * @param sessionId the owning session id (for loading chunk bytes).
     * @param transcriptionService the configured transcription service, or null when no
     *   LLM is available (context-only segments then fall back to blockquotes).
     */
    suspend fun processSegment(
        segIndex: Int,
        segment: Segment,
        sessionId: String,
        transcriptionService: TranscriptionService?
    ): SegmentOutput {
        var markdown: String? = null
        var failedDelta = 0
        var lastError: String? = null

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
                markdown = sb.toString()
                Log.d(TAG, "Segment $segIndex: context-only (${segment.contexts.size} contexts)")
            }

            // Chunk-only segment: transcribe and write
            segment.contexts.isEmpty() && segment.chunks.isNotEmpty() -> {
                val result = transcribeChunks(
                    sessionId, segment.chunks, requireNotNull(transcriptionService) { "Transcription service not available" }
                )
                failedDelta += result.failedCount
                if (result.notes.isNotEmpty()) {
                    markdown = result.notes.joinToString("\n\n")
                }
                if (result.lastError != null) {
                    lastError = result.lastError
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
                        if (cumulativeSize >= maxBatchBytes) {
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
                    lastError = transcribeResult.lastError
                }
                // Track failed transcriptions, but if we send images directly
                // and get an answer, we'll subtract them back
                failedDelta += failed
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
                                    failedDelta -= chunkFailedCount
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
                markdown = content
                Log.d(TAG, "Segment $segIndex: Q&A (${segment.contexts.size} contexts, ${segment.chunks.size} chunks, ${chunkImageBytes.size} chunk images sent)")
            }
        }

        return SegmentOutput(markdown, failedDelta, lastError)
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
}
