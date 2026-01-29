package com.synapse.api

import com.synapse.model.*

class QuestionAnswerService(
    private val llmProviderFactory: LlmProviderFactory
) {

    suspend fun answerQuestion(
        question: String,
        config: com.synapse.model.LlmConfig,
        contexts: List<CapturedContext> = emptyList()
    ): String {
        val contextText = contexts.mapNotNull { context ->
            when (context) {
                is CapturedContext.SelectedText -> "Selected text: ${context.text}"
                is CapturedContext.RegionText -> "Region text: ${context.text}"
                is CapturedContext.AutoContext -> "Source: ${context.sourceApp} ${context.sourceUrl ?: ""}"
                is CapturedContext.RegionImage -> "Image: ${context.description ?: "no description"}"
            }
        }.joinToString("\n")

        val prompt = buildString {
            appendLine("Answer the following question concisely and accurately.")
            if (contextText.isNotBlank()) {
                appendLine()
                appendLine("Context:")
                appendLine(contextText)
            }
            appendLine()
            appendLine("Question: $question")
            appendLine()
            appendLine("Provide a clear, direct answer. If you're unsure, say so.")
        }

        // For now, return a placeholder - actual implementation would call the LLM
        // The transcription service uses image-based APIs, so we'd need a text-only endpoint
        // Future: use llmProviderFactory.getAnsweringService(config) with a text-only API
        return "Answer pending - text-only LLM query not yet implemented"
    }
}
