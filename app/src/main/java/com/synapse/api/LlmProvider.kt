package com.synapse.api

/**
 * Enum representing the supported LLM providers for handwriting transcription.
 */
enum class LlmProvider(
    val displayName: String,
    val requiresApiKey: Boolean,
    val defaultModel: String
) {
    GEMINI(
        displayName = "Google Gemini",
        requiresApiKey = true,
        defaultModel = "gemini-1.5-flash"
    ),
    CLAUDE(
        displayName = "Anthropic Claude",
        requiresApiKey = true,
        defaultModel = "claude-3-haiku-20240307"
    ),
    OPENAI(
        displayName = "OpenAI",
        requiresApiKey = true,
        defaultModel = "gpt-4o-mini"
    ),
    OLLAMA(
        displayName = "Ollama (Local)",
        requiresApiKey = false,
        defaultModel = "llava"
    );

    companion object {
        fun fromName(name: String): LlmProvider? {
            return entries.find { it.name.equals(name, ignoreCase = true) }
        }
    }
}
