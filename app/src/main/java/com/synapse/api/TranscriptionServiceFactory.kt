package com.synapse.api

import com.synapse.model.LlmConfig

/**
 * Factory for creating TranscriptionService instances based on the selected provider.
 *
 * This class centralizes the creation of LLM service implementations and ensures
 * consistent configuration across the app. Also supports routing requests to the
 * appropriate provider based on [LlmConfig], with separate providers for
 * transcription and answering (e.g., Gemini for transcription, Claude for answering).
 */
class DefaultTranscriptionServiceFactory(
    private val httpClient: okhttp3.OkHttpClient? = null
) {

    private val serviceCache = java.util.concurrent.ConcurrentHashMap<LlmProvider, TranscriptionService>()
    private var lastRateLimitingSafe: Boolean = true

    /**
     * Creates or retrieves a cached TranscriptionService for the specified provider.
     *
     * @param provider The LLM provider to use
     * @param apiKey Optional API key (required for Gemini, Claude, OpenAI)
     * @return Configured TranscriptionService instance
     */
    fun create(provider: LlmProvider, apiKey: String?, rateLimitingSafe: Boolean): TranscriptionService {
        synchronized(serviceCache) {
            // Invalidate cache when rateLimitingSafe changes since it's a constructor param
            if (rateLimitingSafe != lastRateLimitingSafe) {
                serviceCache.clear()
                lastRateLimitingSafe = rateLimitingSafe
            }

            return serviceCache.getOrPut(provider) {
                when (provider) {
                    LlmProvider.GEMINI -> GeminiService(apiKey, sharedHttpClient = httpClient)
                    LlmProvider.CLAUDE -> ClaudeService(apiKey, rateLimitingSafe = rateLimitingSafe, sharedHttpClient = httpClient)
                    LlmProvider.OPENAI -> OpenAiService(apiKey, rateLimitingSafe = rateLimitingSafe, sharedHttpClient = httpClient)
                    LlmProvider.OLLAMA -> OllamaService(sharedHttpClient = httpClient)
                }
            }.also { service ->
                service.setApiKey(apiKey)
            }
        }
    }

    /**
     * Creates a new TranscriptionService instance without caching.
     * Useful for testing or when you need isolated instances.
     *
     * @param provider The LLM provider to use
     * @param apiKey Optional API key
     * @return New TranscriptionService instance
     */
    fun createNew(provider: LlmProvider, apiKey: String? = null): TranscriptionService {
        return when (provider) {
            LlmProvider.GEMINI -> GeminiService(apiKey, sharedHttpClient = httpClient)
            LlmProvider.CLAUDE -> ClaudeService(apiKey, sharedHttpClient = httpClient)
            LlmProvider.OPENAI -> OpenAiService(apiKey, sharedHttpClient = httpClient)
            LlmProvider.OLLAMA -> OllamaService(sharedHttpClient = httpClient)
        }
    }

    /**
     * Clears the service cache.
     * Call this when the user changes providers or API keys.
     */
    fun clearCache() {
        serviceCache.clear()
    }

    /**
     * Removes a specific provider from the cache.
     *
     * @param provider The provider to remove from cache
     */
    fun invalidate(provider: LlmProvider) {
        serviceCache.remove(provider)
    }

    /**
     * Updates the API key for a cached service.
     *
     * @param provider The provider to update
     * @param apiKey The new API key
     */
    fun updateApiKey(provider: LlmProvider, apiKey: String?) {
        serviceCache[provider]?.setApiKey(apiKey)
    }

    /**
     * Updates the custom prompt for a cached service.
     *
     * @param provider The provider to update
     * @param template The custom prompt template
     */
    fun updateCustomPrompt(provider: LlmProvider, template: String?) {
        serviceCache[provider]?.setCustomPrompt(template)
    }

    /**
     * Checks if any configured provider is ready to use.
     *
     * @return List of configured providers
     */
    fun getConfiguredProviders(): List<LlmProvider> {
        return serviceCache.filter { it.value.isConfigured() }.keys.toList()
    }

    /**
     * Creates a TranscriptionService configured for transcription based on the LlmConfig.
     *
     * @param config The LLM configuration specifying provider and API key
     * @param rateLimitingSafe Whether to enable rate limiting safety
     * @return Configured TranscriptionService for transcription
     */
    fun getTranscriptionService(config: LlmConfig, rateLimitingSafe: Boolean = true): TranscriptionService {
        return create(
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
     * @param rateLimitingSafe Whether to enable rate limiting safety
     * @return Configured TranscriptionService for answering
     */
    fun getAnsweringService(config: LlmConfig, rateLimitingSafe: Boolean = true): TranscriptionService {
        val provider = config.answeringProvider ?: config.transcriptionProvider
        val apiKey = config.answeringApiKey ?: config.transcriptionApiKey
        return create(
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
