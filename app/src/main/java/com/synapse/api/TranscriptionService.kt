package com.synapse.api

/**
 * Interface defining the contract for LLM-based handwriting transcription services.
 *
 * Implementations of this interface handle communication with various LLM providers
 * (Gemini, Claude, OpenAI, Ollama) to transcribe handwritten note images into
 * structured markdown text.
 */
interface TranscriptionService {

    /**
     * Transcribes a list of handwritten note chunks into structured text.
     *
     * @param chunks List of image chunks to transcribe, each containing the image data and timestamp
     * @param cleanupEnabled If true, the LLM will fix spelling/grammar and expand abbreviations
     * @param advancedFormatting If true, converts diagrams to Mermaid, equations to LaTeX, etc.
     * @return TranscriptionResult containing the transcribed notes and any failed chunk indices
     * @throws TranscriptionError.NetworkError if network connectivity issues occur
     * @throws TranscriptionError.RateLimitError if rate limits are exceeded
     * @throws TranscriptionError.ApiKeyInvalid if the API key is invalid
     * @throws TranscriptionError.ApiKeyMissing if no API key is configured
     * @throws TranscriptionError.InvalidResponse if the LLM response cannot be parsed
     * @throws TranscriptionError.ServerError if the API returns a server error
     * @throws TranscriptionError.Timeout if the request times out
     */
    suspend fun transcribe(
        chunks: List<ChunkData>,
        cleanupEnabled: Boolean,
        advancedFormatting: Boolean
    ): TranscriptionResult

    /**
     * Checks if the service is properly configured and ready to use.
     *
     * @return true if the service has all required configuration (API key, etc.)
     */
    fun isConfigured(): Boolean

    /**
     * Returns the LLM provider type for this service.
     */
    val provider: LlmProvider

    /**
     * Returns the model identifier being used by this service.
     */
    val modelId: String

    /**
     * Updates the API key for this service.
     *
     * @param apiKey The new API key, or null to clear
     */
    fun setApiKey(apiKey: String?)

    /**
     * Sets a custom prompt template for transcription.
     *
     * @param template The custom template, or null to use default
     */
    fun setCustomPrompt(template: String?)

    /**
     * Gets the current rate limit state for this service.
     *
     * @return RateLimitState or null if rate limiting is not tracked
     */
    fun getRateLimitState(): RateLimitState?

    /**
     * Checks if a request can be made without hitting rate limits.
     *
     * @return true if a request can be made immediately
     */
    fun canMakeRequest(): Boolean

    /**
     * Gets the estimated wait time before next request can be made.
     *
     * @return Wait time in milliseconds, or 0 if no wait needed
     */
    fun getWaitTimeMs(): Long

    /**
     * Sends a text-only query to the LLM and returns the response.
     *
     * Unlike [transcribe], this method does not require image input and is
     * suitable for question-answering, summarization, and other text-only tasks.
     *
     * @param prompt The text prompt to send to the LLM
     * @param systemPrompt Optional system prompt to set the assistant's behavior
     * @return The LLM's text response
     * @throws TranscriptionError.NetworkError if network connectivity issues occur
     * @throws TranscriptionError.RateLimitError if rate limits are exceeded
     * @throws TranscriptionError.ApiKeyInvalid if the API key is invalid
     * @throws TranscriptionError.ApiKeyMissing if no API key is configured
     * @throws TranscriptionError.InvalidResponse if the LLM response cannot be parsed
     * @throws TranscriptionError.ServerError if the API returns a server error
     */
    suspend fun textQuery(
        prompt: String,
        systemPrompt: String? = null
    ): String

    /**
     * Sends a query with both text and images to the LLM and returns the response.
     *
     * Used for vision tasks such as explaining diagrams, charts, or screenshots.
     *
     * @param prompt The text prompt to send to the LLM
     * @param images List of image byte arrays (PNG or WebP) to include
     * @param systemPrompt Optional system prompt to set the assistant's behavior
     * @return The LLM's text response
     */
    suspend fun visionQuery(
        prompt: String,
        images: List<ByteArray>,
        systemPrompt: String? = null
    ): String {
        // Default implementation falls back to text-only query
        return textQuery(prompt, systemPrompt)
    }
}

/**
 * Factory interface for creating TranscriptionService instances.
 */
interface TranscriptionServiceFactory {
    /**
     * Creates a TranscriptionService for the specified provider.
     *
     * @param provider The LLM provider to use
     * @param apiKey Optional API key (required for some providers)
     * @return Configured TranscriptionService instance
     */
    fun create(provider: LlmProvider, apiKey: String? = null): TranscriptionService
}
