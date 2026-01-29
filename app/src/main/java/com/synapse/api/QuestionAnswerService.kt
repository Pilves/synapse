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
    private val llmProviderFactory: LlmProviderFactory
) {

    companion object {
        private const val TAG = "QuestionAnswerService"

        private const val SYSTEM_PROMPT = """You are a helpful assistant integrated into a note-taking app. \
Always answer the user's question fully and completely. Never ask for clarification - just answer \
everything that was asked. If the user asks about multiple topics, cover all of them. \
When context is provided from the user's screen or notes, use it to inform your answer. \
When images are provided, describe and analyze them thoroughly. \
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
        contexts: List<CapturedContext> = emptyList()
    ): String {
        val service = llmProviderFactory.getAnsweringService(config)
        Log.d(TAG, "Answering question using ${service.provider.displayName} (${service.modelId})")

        // Collect image bytes from RegionImage contexts
        val imageBytes = contexts.filterIsInstance<CapturedContext.RegionImage>().mapNotNull { ctx ->
            try {
                val file = File(ctx.imagePath)
                if (file.exists()) file.readBytes() else null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read image at ${ctx.imagePath}", e)
                null
            }
        }

        val contextText = contexts.mapNotNull { context ->
            when (context) {
                is CapturedContext.SelectedText -> "Selected text: ${context.text}"
                is CapturedContext.RegionText -> "Region text: ${context.text}"
                is CapturedContext.AutoContext -> buildString {
                    append("Source: ${context.sourceApp}")
                    if (context.sourceUrl != null) append(" (${context.sourceUrl})")
                    if (context.pageTitle != null) append(" - ${context.pageTitle}")
                }
                is CapturedContext.RegionImage -> if (imageBytes.isNotEmpty()) {
                    "Image attached (see below)"
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
            appendLine("Question: $question")
        }

        return try {
            if (imageBytes.isNotEmpty()) {
                Log.d(TAG, "Sending vision query with ${imageBytes.size} image(s)")
                service.visionQuery(prompt, imageBytes, SYSTEM_PROMPT)
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
