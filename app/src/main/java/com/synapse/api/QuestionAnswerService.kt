package com.synapse.api

import android.util.Log
import com.synapse.model.*
import java.io.File

/**
 * Service that answers user questions using the configured LLM provider.
 *
 * Uses [LlmProviderFactory] to route requests to the appropriate LLM backend
 * (Gemini, Claude, OpenAI, or Ollama) based on the user's configuration.
 * Supports optional context from captured screen content, selected text,
 * and region images (diagrams, screenshots, etc.).
 */
class QuestionAnswerService(
    private val llmProviderFactory: LlmProviderFactory,
    private val appFilesDir: File? = null
) {

    companion object {
        private const val TAG = "QuestionAnswerService"
        private const val MAX_IMAGE_FILE_SIZE = 10L * 1024 * 1024 // 10MB

        private const val SYSTEM_PROMPT = """You are a helpful assistant integrated into a note-taking app. \
Always answer the user's question fully and completely. Never ask for clarification - just answer \
everything that was asked. If the user asks about multiple topics, cover all of them. \
When context is provided from the user's screen or notes, use it to inform your answer. \
When images are provided, describe and analyze them thoroughly. \
If an image contains code, always transcribe the code into a properly formatted code block in your answer. \
Since images cannot be embedded in the notes, any code visible in screenshots must be reproduced as text. \
Format your response in markdown. Use code blocks with language tags for code examples."""
    }

    /**
     * Answers a user question using the configured LLM provider.
     *
     * When region images are present in the contexts, the images are loaded from
     * disk and sent to the LLM via [TranscriptionService.visionQuery] so the model
     * can see and analyze diagrams, charts, screenshots, etc.
     *
     * @param question The user's question text
     * @param config LLM configuration specifying which provider and API key to use
     * @param contexts Optional list of captured contexts (selected text, screen regions, etc.)
     * @return The LLM's answer as a string
     * @throws TranscriptionError if the LLM request fails
     */
    suspend fun answerQuestion(
        question: String,
        config: LlmConfig,
        contexts: List<CapturedContext> = emptyList(),
        additionalImages: List<ByteArray> = emptyList()
    ): String {
        val service = llmProviderFactory.getAnsweringService(config)
        Log.d(TAG, "Answering question using ${service.provider.displayName} (${service.modelId})")

        // Collect image bytes from RegionImage contexts
        val contextImages = contexts.filterIsInstance<CapturedContext.RegionImage>().mapNotNull { ctx ->
            try {
                val file = File(ctx.imagePath)
                val canonical = file.canonicalPath
                // Validate that the file is within the app's files directory
                val allowedDir = appFilesDir?.canonicalPath
                if (allowedDir != null && !canonical.startsWith(allowedDir)) {
                    Log.w(TAG, "Path outside app files dir, skipping image")
                    return@mapNotNull null
                }
                if (!file.exists()) return@mapNotNull null
                // Skip files larger than 10MB to avoid OOM
                if (file.length() > MAX_IMAGE_FILE_SIZE) {
                    Log.w(TAG, "Image file too large (${file.length()} bytes), skipping")
                    return@mapNotNull null
                }
                file.readBytes()
            } catch (e: SecurityException) {
                Log.w(TAG, "Security exception reading image path", e)
                null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read image at ${ctx.imagePath}", e)
                null
            }
        }

        // Combine context images + additional images (e.g. handwritten scribble chunks)
        val allImages = contextImages + additionalImages

        val contextText = contexts.mapNotNull { context ->
            when (context) {
                is CapturedContext.SelectedText -> "Selected text: ${context.text}"
                is CapturedContext.RegionText -> "Region text: ${context.text}"
                is CapturedContext.AutoContext -> buildString {
                    append("Source: ${context.sourceApp}")
                    if (context.sourceUrl != null) append(" (${context.sourceUrl})")
                    if (context.pageTitle != null) append(" - ${context.pageTitle}")
                }
                is CapturedContext.RegionImage -> if (contextImages.isNotEmpty()) {
                    "Screenshot image attached (see below)"
                } else {
                    "Image: ${context.description ?: "could not load image"}"
                }
            }
        }.joinToString("\n")

        val prompt = buildString {
            if (contextText.isNotBlank()) {
                appendLine("Context:")
                appendLine(contextText)
                appendLine()
            }
            if (additionalImages.isNotEmpty()) {
                appendLine("The user's handwritten question is in the attached image(s). Read it and answer based on the context above.")
            } else if (question.isNotBlank()) {
                appendLine("Question: $question")
            }
        }

        return try {
            if (allImages.isNotEmpty()) {
                Log.d(TAG, "Sending vision query with ${allImages.size} image(s) (${contextImages.size} context + ${additionalImages.size} scribble)")
                service.visionQuery(prompt, allImages, SYSTEM_PROMPT)
            } else {
                service.textQuery(prompt, SYSTEM_PROMPT)
            }
        } catch (e: TranscriptionError) {
            Log.e(TAG, "LLM query failed: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during question answering", e)
            throw TranscriptionError.Unknown("Failed to get answer: ${e.message}", e)
        }
    }
}
