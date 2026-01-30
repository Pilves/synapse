package com.synapse.api

import android.util.Log

/**
 * Cost-optimized transcription that routes to text-only or vision based on context availability.
 *
 * Decision logic:
 * - If accessibility text context is available and sufficient → text-only LLM call (cheap)
 * - If no context or context is image-only → vision OCR call (expensive, but necessary)
 * - Tracks cost savings for the user to see in settings
 *
 * This wraps an existing [TranscriptionService] and adds the decision layer.
 */
class TwoPassTranscriptionService(
    private val delegate: TranscriptionService,
    private val preferTextOnly: Boolean = true,
    private val onCostSaved: ((savedChunks: Int) -> Unit)? = null
) : TranscriptionService by delegate {

    companion object {
        private const val TAG = "TwoPassTranscription"

        /**
         * Minimum accessibility text length to consider "sufficient" for text-only transcription.
         * Below this, we fall back to vision OCR.
         */
        private const val MIN_CONTEXT_TEXT_LENGTH = 20
    }

    /**
     * Determines whether a set of contexts provides enough text to skip vision OCR.
     *
     * @param contexts Text extracted from screen via accessibility
     * @return true if text-only transcription is sufficient
     */
    fun canUseTextOnly(contextTexts: List<String>): Boolean {
        if (!preferTextOnly) return false

        val combinedText = contextTexts.joinToString(" ").trim()
        if (combinedText.length < MIN_CONTEXT_TEXT_LENGTH) return false

        // If the text appears to be meaningful (not just UI labels), prefer text-only
        val wordCount = combinedText.split("\\s+".toRegex()).size
        return wordCount >= 3
    }

    /**
     * Transcribes using text-only mode when context is sufficient.
     * Falls back to vision OCR via the delegate when text is insufficient.
     *
     * @param chunks The image chunks to transcribe
     * @param contextTexts Accessibility text from the screen (if available)
     * @param cleanupEnabled Whether to clean up formatting
     * @param advancedFormatting Whether to use advanced formatting
     * @return Transcription result
     */
    suspend fun transcribeWithContext(
        chunks: List<ChunkData>,
        contextTexts: List<String>,
        cleanupEnabled: Boolean,
        advancedFormatting: Boolean
    ): TranscriptionResult {
        if (canUseTextOnly(contextTexts)) {
            Log.d(TAG, "Using text-only path (${contextTexts.size} context texts available)")

            // Use text-only transcription — the context provides enough information
            val combinedContext = contextTexts.joinToString("\n")
            val prompt = buildString {
                appendLine("The user has handwritten notes alongside the following screen content:")
                appendLine()
                appendLine(combinedContext)
                appendLine()
                appendLine("Please provide a clean transcription of what the user likely wrote, using the screen content as reference context.")
            }

            return try {
                val response = delegate.textQuery(prompt)
                onCostSaved?.invoke(chunks.size)
                Log.d(TAG, "Text-only transcription succeeded, saved ${chunks.size} vision API calls")

                TranscriptionResult(
                    notes = listOf(Note(text = response, chunksUsed = chunks.map { it.index })),
                    failedChunks = emptyList()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Text-only transcription failed, falling back to vision", e)
                // Fall back to vision OCR
                delegate.transcribe(chunks, cleanupEnabled, advancedFormatting)
            }
        }

        // No sufficient text context — use standard vision OCR
        Log.d(TAG, "Using vision OCR path (insufficient text context)")
        return delegate.transcribe(chunks, cleanupEnabled, advancedFormatting)
    }
}
