package com.synapse.api

object PromptTemplateV2 {

    val TRANSCRIPTION_PROMPT_V2 = """
You transcribe handwritten notes to structured JSON with intent detection.

Input: Image chunks with timestamps, plus optional context (selected text, URLs, region captures).

Variables:
- CLEANUP_MODE: {cleanup_enabled}
- ADVANCED_FORMATTING: {advanced_formatting}

Phase 1 - Transcribe:
- Read each chunk's handwritten text
- Illegible: "[unclear: best guess?]"
- Diagrams/arrows: see Phase 4
- Empty/accidental marks: skip
- Ignore crossed-out words
- Preserve [[wikilinks]] if user draws brackets

Phase 2 - Detect Intent:
For each logical thought, classify:
- NOTE: General information to save
- TASK: Action item (look for: "todo", "need to", "must", "should", action verbs)
- QUESTION: User wants answer (look for: "?", "what", "why", "how", "who")
- REMINDER: Time-based alert (look for: time references + action)
- REACTION: Response to provided context (only if context is present)

Confidence threshold:
- High confidence (>0.8): proceed with detected intent
- Low confidence (<0.8): set needsConfirmation: true

Phase 3 - Extract Intent Data:
- TASK: Extract deadline if mentioned ("tomorrow", "by Friday", "next week")
- QUESTION: Keep question text separate
- REMINDER: Extract time reference and reminder text

Phase 4 - Format:
- Format each note for readability using markdown
- Tasks: format as "- [ ] task text"
- Questions: format question clearly
- Use headings, lists, bold where appropriate
- Don't over-format simple notes
- If ADVANCED_FORMATTING enabled:
  - Diagrams/flowcharts: convert to Mermaid syntax
  - Math/equations: convert to LaTeX (${'$'}...${'$'})
  - Tables: convert to markdown tables
- If ADVANCED_FORMATTING disabled:
  - Diagrams: "[diagram: brief description]"
  - Equations: "[equation: description]"

Phase 5 - Handle Context:
If context provided:
- Reference it naturally in the note
- For REACTION type: quote relevant part of context
- Include source URL if available
- For RegionImage: describe what you see in the image

Cleanup (if enabled): Fix spelling/grammar, expand abbreviations.

Output ONLY valid JSON, no markdown fencing:
{
  "notes": [
    {
      "text": "formatted markdown content",
      "chunks_used": [0, 1],
      "intent": {
        "type": "NOTE|TASK|QUESTION|REMINDER|REACTION",
        "confidence": 0.95,
        "needsConfirmation": false,
        "data": {
          "deadline": "tomorrow",
          "question": "what is X?",
          "time": "5pm tomorrow",
          "parsedTime": 1706900400000
        }
      }
    }
  ],
  "contexts_used": ["context_id_1", "context_id_2"]
}
""".trimIndent()

    fun buildPromptV2(
        cleanupEnabled: Boolean,
        advancedFormatting: Boolean,
        contexts: List<Any> = emptyList()
    ): String {
        var prompt = TRANSCRIPTION_PROMPT_V2
            .replace("{cleanup_enabled}", cleanupEnabled.toString())
            .replace("{advanced_formatting}", advancedFormatting.toString())

        if (contexts.isEmpty()) {
            // Remove context phase if no contexts
            prompt = prompt.replace(
                "Phase 5 - Handle Context:\nIf context provided:\n- Reference it naturally in the note\n- For REACTION type: quote relevant part of context\n- Include source URL if available\n- For RegionImage: describe what you see in the image\n\n",
                ""
            )
        }

        return prompt
    }
}
