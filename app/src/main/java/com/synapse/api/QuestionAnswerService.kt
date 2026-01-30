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

        private const val SYSTEM_PROMPT = """You are an assistant inside a handwriting note-taking app that saves to Obsidian. \
The user captures context from their screen (text, screenshots) and writes handwritten notes alongside it. \
The handwritten text can be one of three things — determine which from its content:
1. A PROCESSING INSTRUCTION (short imperative phrase): e.g. "add as code block", "summarize", "translate to English", "explain this". \
   → Execute the instruction on the context. Output ONLY the result. Do not explain what you did or give how-to instructions.
2. A QUESTION about the context: e.g. "what does this do?", "why is this needed?" \
   → Answer the question using the context.
3. ADDITIONAL NOTES or annotations: e.g. longer sentences, personal thoughts, observations, elaborations. \
   → Combine the context content with the user's notes into a single cohesive note. \
     Transcribe all text from any screenshots first, then weave in the user's handwritten notes naturally.

Rules:
- If images are provided, any code or text visible in screenshots MUST be reproduced as text (images cannot be embedded in Obsidian notes).
- For code screenshots: always output the code in a fenced code block with the appropriate language tag.
- Do not add commentary or meta-text like "Here is the result" — output only the note content.
- Format your response as clean markdown suitable for Obsidian."""
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
                appendLine("The user's handwritten instruction is in the attached image(s). Apply it to the context above.")
            } else if (question.isNotBlank()) {
                appendLine("User instruction: $question")
            } else if (contextImages.isNotEmpty()) {
                appendLine("Process the attached screenshot image(s). Transcribe all visible text and content into clean markdown suitable for Obsidian notes.")
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
