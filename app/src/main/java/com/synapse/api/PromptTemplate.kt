package com.synapse.api

/**
 * Contains the default prompt template for handwriting transcription
 * and utility functions for prompt generation.
 */
object PromptTemplate {

    /**
     * The default prompt template used for LLM transcription.
     * Users can customize this in settings.
     */
    const val DEFAULT_TEMPLATE = """You transcribe handwritten notes to structured JSON.

Input: Image chunks with timestamps (seconds since session start).

Variables:
- CLEANUP_MODE: {cleanup_enabled}
- ADVANCED_FORMATTING: {advanced_formatting}

Phase 1 - Transcribe:
- Read each chunk's handwritten text
- Illegible: "[unclear: best guess?]"
- Diagrams/arrows: see Phase 3
- Empty/accidental marks: skip
- Ignore crossed-out words
- Preserve [[wikilinks]] if user draws brackets

Phase 2 - Group:
- Review all transcriptions
- Group chunks that form one logical thought/topic
- Separate chunks that are distinct ideas

Phase 3 - Format:
- Format each note for readability using markdown
- Use headings, lists, bold where appropriate
- Don't over-format simple notes
- If ADVANCED_FORMATTING enabled:
  - Diagrams/flowcharts: convert to Mermaid syntax
  - Math/equations: convert to LaTeX ($...$)
  - Tables: convert to markdown tables
- If ADVANCED_FORMATTING disabled:
  - Diagrams: "[diagram: brief description]"
  - Equations: "[equation: description]"

Cleanup (if enabled): Fix spelling/grammar, expand abbreviations (w/ -> with, b/c -> because).

Output ONLY valid JSON, no markdown fencing:
{
  "notes": [
    {
      "text": "formatted markdown content",
      "chunks_used": [0, 1]
    }
  ]
}"""

    /**
     * Builds the complete prompt with variable substitution.
     *
     * @param cleanupEnabled Whether to enable cleanup mode
     * @param advancedFormatting Whether to enable advanced formatting
     * @param customTemplate Optional custom template (uses default if null)
     * @return The formatted prompt string
     */
    fun buildPrompt(
        cleanupEnabled: Boolean,
        advancedFormatting: Boolean,
        customTemplate: String? = null
    ): String {
        val template = customTemplate ?: DEFAULT_TEMPLATE
        return template
            .replace("{cleanup_enabled}", cleanupEnabled.toString())
            .replace("{advanced_formatting}", advancedFormatting.toString())
    }

    /**
     * Builds the chunk context message describing the images being sent.
     *
     * @param chunks List of chunk data with timestamps
     * @return Description of the chunks for the LLM
     */
    fun buildChunkContext(chunks: List<ChunkData>): String {
        if (chunks.isEmpty()) return "No image chunks provided."

        val descriptions = chunks.mapIndexed { index, chunk ->
            "Chunk $index: timestamp ${chunk.timestampSeconds}s"
        }

        return buildString {
            appendLine("Processing ${chunks.size} handwritten note chunk(s):")
            descriptions.forEach { appendLine("- $it") }
        }
    }

    /**
     * System prompt for establishing the assistant's role.
     */
    const val SYSTEM_PROMPT = """You are a handwriting transcription assistant. Your task is to accurately read handwritten notes from images and convert them to well-formatted markdown text. You output only valid JSON as specified in the prompt."""

    /**
     * Validates that a custom template contains the required placeholders.
     *
     * @param template The template to validate
     * @return List of missing placeholders, empty if valid
     */
    fun validateTemplate(template: String): List<String> {
        val requiredPlaceholders = listOf("{cleanup_enabled}", "{advanced_formatting}")
        return requiredPlaceholders.filter { !template.contains(it) }
    }

    /**
     * Creates a minimal prompt for simple transcription without advanced features.
     */
    const val MINIMAL_TEMPLATE = """Transcribe the handwritten text in the image(s) to JSON format.

Output ONLY valid JSON:
{
  "notes": [
    {
      "text": "transcribed content",
      "chunks_used": [0]
    }
  ]
}"""
}
