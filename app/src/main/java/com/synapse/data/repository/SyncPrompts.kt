package com.synapse.data.repository

/**
 * Prompt constants used by [SyncRepositoryImpl] for LLM formatting and transcription.
 */
object SyncPrompts {

    /** System prompt sent to the LLM to format raw captured context text as markdown. */
    const val CONTEXT_FORMAT_SYSTEM_PROMPT =
        """You are a note formatter for Obsidian. You receive raw captured text and must:
1. Format it as clean, readable markdown (headings, lists, bold where appropriate).
2. Fix obvious spelling/grammar issues.
3. Do NOT add commentary, preamble, or extra content.
4. If the text is already well-formatted, return it as-is.
5. Return ONLY the formatted markdown, nothing else."""

    /** System prompt for the final polish pass that cleans up raw markdown for Obsidian. */
    const val POLISH_MARKDOWN_SYSTEM_PROMPT =
        """You are a final-pass editor for Obsidian notes. You receive raw markdown and must:
1. Fix formatting: spacing, headers, lists, code blocks (add language tags if missing), LaTeX, Mermaid.
2. Remove any meta-commentary that is not actual note content — phrases like "Here is the result", \
"The transcribed content is:", "I've summarized the following", or similar preamble/postamble. \
The output should read as if the user wrote it themselves.
3. Remove duplicate content — if the same text appears both as a quote and in the body, keep only the body version.
4. Do NOT change the actual meaning or substance of the content.
5. Return ONLY the cleaned markdown, nothing else."""

    /** Prompt sent alongside a RegionImage screenshot for vision-based transcription. */
    const val REGION_IMAGE_TRANSCRIPTION_PROMPT =
        "Transcribe all text and content visible in this screenshot. " +
        "Output clean markdown suitable for Obsidian notes. " +
        "If there are diagrams or charts, describe them. " +
        "Do not add any commentary — only output the transcribed content."
}
