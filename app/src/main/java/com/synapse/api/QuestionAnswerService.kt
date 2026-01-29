package com.synapse.api

import android.util.Log
import com.synapse.model.*

/**
 * Service that answers user questions using the configured LLM provider.
 *
 * Uses [LlmProviderFactory] to route requests to the appropriate LLM backend
 * (Gemini, Claude, OpenAI, or Ollama) based on the user's configuration.
 * Supports optional context from captured screen content, selected text, etc.
 */
class QuestionAnswerService(
    private val llmProviderFactory: LlmProviderFactory
) {

    companion object {
        private const val TAG = "QuestionAnswerService"

        private const val SYSTEM_PROMPT = """You are a helpful assistant integrated into a note-taking app. \
Answer questions concisely and accurately. When context is provided from the user's screen or notes, \
use it to inform your answer. If you're unsure about something, say so rather than guessing."""
    }

    /**
     * Answers a user question using the configured LLM provider.
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

        val contextText = contexts.mapNotNull { context ->
            when (context) {
                is CapturedContext.SelectedText -> "Selected text: ${context.text}"
                is CapturedContext.RegionText -> "Region text: ${context.text}"
                is CapturedContext.AutoContext -> buildString {
                    append("Source: ${context.sourceApp}")
                    if (context.sourceUrl != null) append(" (${context.sourceUrl})")
                    if (context.pageTitle != null) append(" - ${context.pageTitle}")
                }
                is CapturedContext.RegionImage -> "Image: ${context.description ?: "no description"}"
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
            service.textQuery(prompt, SYSTEM_PROMPT)
        } catch (e: TranscriptionError) {
            Log.e(TAG, "LLM query failed: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during question answering", e)
            throw TranscriptionError.Unknown("Failed to get answer: ${e.message}", e)
        }
    }
}
