package com.synapse.api

import com.synapse.model.LlmConfig

/**
 * Factory that routes LLM requests to the appropriate provider based on LlmConfig.
 *
 * Supports separate providers for transcription and answering, allowing users
 * to mix providers (e.g., Gemini for transcription, Claude for answering).
 */
class LlmProviderFactory(
    private val transcriptionServiceFactory: DefaultTranscriptionServiceFactory
) {
    /**
     * Creates a TranscriptionService configured for transcription based on the LlmConfig.
     *
     * @param config The LLM configuration specifying provider and API key
     * @return Configured TranscriptionService for transcription
     */
    fun getTranscriptionService(config: LlmConfig, rateLimitingSafe: Boolean = true): TranscriptionService {
        return transcriptionServiceFactory.create(
            provider = resolveProvider(config.transcriptionProvider),
            apiKey = config.transcriptionApiKey,
            rateLimitingSafe = rateLimitingSafe
        )
    }

    /**
     * Creates a TranscriptionService configured for answering based on the LlmConfig.
     *
     * Falls back to the transcription provider/key if no separate answering
     * provider is configured.
     *
     * @param config The LLM configuration specifying provider and API key
     * @return Configured TranscriptionService for answering
     */
    fun getAnsweringService(config: LlmConfig, rateLimitingSafe: Boolean = true): TranscriptionService {
        val provider = config.answeringProvider ?: config.transcriptionProvider
        val apiKey = config.answeringApiKey ?: config.transcriptionApiKey
        return transcriptionServiceFactory.create(
            provider = resolveProvider(provider),
            apiKey = apiKey,
            rateLimitingSafe = rateLimitingSafe
        )
    }

    /**
     * Resolves a provider name string to an [LlmProvider] enum value.
     *
     * First attempts exact match via [LlmProvider.fromName], then falls back
     * to substring matching for flexible user input.
     *
     * @param providerName The provider name string from config
     * @return The resolved LlmProvider, defaulting to GEMINI if unrecognized
     */
    private fun resolveProvider(providerName: String): LlmProvider {
        // Try exact match first
        LlmProvider.fromName(providerName)?.let { return it }

        // Fall back to substring matching for flexibility
        return when {
            providerName.contains("gemini", ignoreCase = true) -> LlmProvider.GEMINI
            providerName.contains("claude", ignoreCase = true) -> LlmProvider.CLAUDE
            providerName.contains("gpt", ignoreCase = true) ||
                providerName.contains("openai", ignoreCase = true) -> LlmProvider.OPENAI
            providerName.contains("ollama", ignoreCase = true) ||
                providerName.contains("llava", ignoreCase = true) -> LlmProvider.OLLAMA
            else -> LlmProvider.GEMINI // Default
        }
    }
}
