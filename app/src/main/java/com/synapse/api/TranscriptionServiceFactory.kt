package com.synapse.api

/**
 * Factory for creating TranscriptionService instances based on the selected provider.
 *
 * This class centralizes the creation of LLM service implementations and ensures
 * consistent configuration across the app.
 */
class DefaultTranscriptionServiceFactory : TranscriptionServiceFactory {

    private val serviceCache = mutableMapOf<LlmProvider, TranscriptionService>()

    /**
     * Creates or retrieves a cached TranscriptionService for the specified provider.
     *
     * @param provider The LLM provider to use
     * @param apiKey Optional API key (required for Gemini, Claude, OpenAI)
     * @return Configured TranscriptionService instance
     */
    override fun create(provider: LlmProvider, apiKey: String?): TranscriptionService {
        return serviceCache.getOrPut(provider) {
            when (provider) {
                LlmProvider.GEMINI -> GeminiService(apiKey)
                LlmProvider.CLAUDE -> ClaudeService(apiKey)
                LlmProvider.OPENAI -> OpenAiService(apiKey)
                LlmProvider.OLLAMA -> OllamaService()
            }
        }.also { service ->
            // Update API key if provided
            apiKey?.let { service.setApiKey(it) }
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
            LlmProvider.GEMINI -> GeminiService(apiKey)
            LlmProvider.CLAUDE -> ClaudeService(apiKey)
            LlmProvider.OPENAI -> OpenAiService(apiKey)
            LlmProvider.OLLAMA -> OllamaService()
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

    companion object {
        @Volatile
        private var instance: DefaultTranscriptionServiceFactory? = null

        /**
         * Gets the singleton instance of the factory.
         */
        fun getInstance(): DefaultTranscriptionServiceFactory {
            return instance ?: synchronized(this) {
                instance ?: DefaultTranscriptionServiceFactory().also { instance = it }
            }
        }
    }
}
