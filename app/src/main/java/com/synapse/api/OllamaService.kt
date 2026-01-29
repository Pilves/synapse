package com.synapse.api

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * TranscriptionService implementation for local Ollama server.
 *
 * Uses the llava model (or other vision-capable models) for handwriting transcription.
 * Runs locally, no API key required. Requires Ollama to be running on localhost.
 */
class OllamaService(
    @Volatile private var baseUrl: String = DEFAULT_BASE_URL,
    private var model: String = DEFAULT_MODEL,
    @Volatile private var customPrompt: String? = null,
    sharedHttpClient: OkHttpClient? = null
) : TranscriptionService {

    companion object {
        private const val TAG = "OllamaService"
        private const val DEFAULT_BASE_URL = "http://localhost:11434"
        private const val DEFAULT_MODEL = "llava"
        private const val TIMEOUT_SECONDS = 120L // Local models may be slower
        private const val MAX_RETRIES = 2
        private const val INITIAL_RETRY_DELAY_MS = 500L

        // No rate limits for local Ollama
        private const val REQUESTS_PER_MINUTE = Int.MAX_VALUE
        private const val REQUESTS_PER_DAY = Int.MAX_VALUE
    }

    override val provider: LlmProvider = LlmProvider.OLLAMA
    override val modelId: String get() = model

    private val rateLimitConfig = RateLimitConfig(REQUESTS_PER_MINUTE, REQUESTS_PER_DAY)
    private val rateLimitState = RateLimitState()

    private val httpClient = (sharedHttpClient?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Sets the base URL for the Ollama server.
     * Default is http://localhost:11434
     */
    fun setBaseUrl(url: String) {
        val parsed = URL(url.trimEnd('/'))
        require(parsed.protocol in listOf("http", "https")) { "Invalid URL scheme: ${parsed.protocol}" }
        require(!parsed.host.isNullOrEmpty()) { "Invalid URL host" }
        this.baseUrl = url.trimEnd('/')
    }

    /**
     * Sets the model to use for transcription.
     * Must be a vision-capable model like llava.
     */
    fun setModel(modelName: String) {
        this.model = modelName
    }

    override suspend fun transcribe(
        chunks: List<ChunkData>,
        cleanupEnabled: Boolean,
        advancedFormatting: Boolean
    ): TranscriptionResult {
        if (!isConfigured()) {
            throw TranscriptionError.ServiceUnavailable("Ollama server not available at $baseUrl")
        }

        if (chunks.isEmpty()) {
            return TranscriptionResult.empty()
        }

        val prompt = PromptTemplate.buildPrompt(cleanupEnabled, advancedFormatting, customPrompt)
        val chunkContext = PromptTemplate.buildChunkContext(chunks)

        return executeWithRetry(chunks, prompt, chunkContext)
    }

    private suspend fun executeWithRetry(
        chunks: List<ChunkData>,
        prompt: String,
        chunkContext: String
    ): TranscriptionResult {
        return RetryHelper.executeWithRetry(
            maxRetries = MAX_RETRIES,
            initialDelayMs = INITIAL_RETRY_DELAY_MS,
            tag = TAG,
            onConnectException = { e ->
                TranscriptionError.ServiceUnavailable(
                    "Cannot connect to Ollama at $baseUrl. Is Ollama running?"
                )
            }
        ) {
            executeRequest(chunks, prompt, chunkContext)
        }
    }

    private fun executeRequest(
        chunks: List<ChunkData>,
        prompt: String,
        chunkContext: String
    ): TranscriptionResult {
        val requestBody = buildRequestBody(chunks, prompt, chunkContext)
        val url = "$baseUrl/api/generate"

        val request = Request.Builder()
            .url(url)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        rateLimitState.recordRequest()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            when {
                response.isSuccessful -> {
                    return parseResponse(responseBody, chunks.size)
                }
                response.code == 404 -> {
                    throw TranscriptionError.InvalidResponse(
                        "Model '$model' not found. Install it with: ollama pull $model"
                    )
                }
                else -> HttpErrorHandler.handleHttpError(response, responseBody)
            }
        }
    }

    private fun buildRequestBody(
        chunks: List<ChunkData>,
        prompt: String,
        chunkContext: String
    ): JSONObject {
        // Encode all images as base64
        val images = JSONArray()
        chunks.forEach { chunk ->
            val base64Image = Base64.encodeToString(chunk.image, Base64.NO_WRAP)
            images.put(base64Image)
        }

        val fullPrompt = buildString {
            append(PromptTemplate.SYSTEM_PROMPT)
            append("\n\n")
            append(prompt)
            append("\n\n")
            append(chunkContext)
        }

        return JSONObject().apply {
            put("model", model)
            put("prompt", fullPrompt)
            put("images", images)
            put("stream", false) // Get complete response at once
            put("options", JSONObject().apply {
                put("temperature", 0.2)
                put("top_p", 0.8)
                put("num_predict", 8192)
            })
        }
    }

    private fun parseResponse(responseBody: String, chunkCount: Int): TranscriptionResult {
        try {
            // Ollama may return multiple JSON objects for streaming, but we disabled streaming
            // So we should get a single JSON response
            val jsonResponse = JSONObject(responseBody)

            // Check for errors
            if (jsonResponse.has("error")) {
                val error = jsonResponse.optString("error", "Unknown error")
                throw TranscriptionError.InvalidResponse("Ollama error: $error")
            }

            // Extract the generated response
            val content = jsonResponse.optString("response", "")

            if (content.isBlank()) {
                Log.w(TAG, "Empty response from Ollama")
                return TranscriptionResult.failure((0 until chunkCount).toList())
            }

            // Check if generation was complete
            val done = jsonResponse.optBoolean("done", true)
            if (!done) {
                Log.w(TAG, "Response generation was incomplete")
            }

            // Parse the JSON output from the LLM
            return parseTranscriptionJson(content, chunkCount)
        } catch (e: TranscriptionError) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response", e)
            throw TranscriptionError.InvalidResponse("Failed to parse response: ${e.message}", e)
        }
    }

    private fun parseTranscriptionJson(content: String, chunkCount: Int): TranscriptionResult {
        return TranscriptionJsonParser.parse(content, chunkCount, extractJsonBounds = true)
    }

    override suspend fun textQuery(prompt: String, systemPrompt: String?): String {
        if (!isConfigured()) {
            throw TranscriptionError.ServiceUnavailable("Ollama server not available at $baseUrl")
        }

        return executeTextQueryWithRetry(prompt, systemPrompt)
    }

    override suspend fun visionQuery(
        prompt: String,
        images: List<ByteArray>,
        systemPrompt: String?
    ): String {
        if (!isConfigured()) {
            throw TranscriptionError.ServiceUnavailable("Ollama server not available at $baseUrl")
        }
        if (images.isEmpty()) {
            return textQuery(prompt, systemPrompt)
        }

        val fullPrompt = if (systemPrompt != null) "$systemPrompt\n\n$prompt" else prompt

        val imagesArray = JSONArray()
        images.forEach { imageBytes ->
            imagesArray.put(Base64.encodeToString(imageBytes, Base64.NO_WRAP))
        }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("prompt", fullPrompt)
            put("images", imagesArray)
            put("stream", false)
            put("options", JSONObject().apply {
                put("temperature", 0.3)
                put("top_p", 0.9)
                put("num_predict", 8192)
            })
        }

        val url = "$baseUrl/api/generate"

        val request = Request.Builder()
            .url(url)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        rateLimitState.recordRequest()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            when {
                response.isSuccessful -> return parseTextQueryResponse(responseBody)
                response.code == 404 ->
                    throw TranscriptionError.InvalidResponse(
                        "Model '$model' not found. Install it with: ollama pull $model"
                    )
                else -> HttpErrorHandler.handleHttpError(response, responseBody)
            }
        }
    }

    private suspend fun executeTextQueryWithRetry(
        prompt: String,
        systemPrompt: String?
    ): String {
        return RetryHelper.executeWithRetry(
            maxRetries = MAX_RETRIES,
            initialDelayMs = INITIAL_RETRY_DELAY_MS,
            tag = TAG,
            onConnectException = { e ->
                TranscriptionError.ServiceUnavailable(
                    "Cannot connect to Ollama at $baseUrl. Is Ollama running?"
                )
            }
        ) {
            executeTextQueryRequest(prompt, systemPrompt)
        }
    }

    private fun executeTextQueryRequest(prompt: String, systemPrompt: String?): String {
        val fullPrompt = if (systemPrompt != null) {
            "$systemPrompt\n\n$prompt"
        } else {
            prompt
        }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("prompt", fullPrompt)
            put("stream", false)
            put("options", JSONObject().apply {
                put("temperature", 0.3)
                put("top_p", 0.9)
                put("num_predict", 8192)
            })
        }

        val url = "$baseUrl/api/generate"

        val request = Request.Builder()
            .url(url)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        rateLimitState.recordRequest()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            when {
                response.isSuccessful -> {
                    return parseTextQueryResponse(responseBody)
                }
                response.code == 404 -> {
                    throw TranscriptionError.InvalidResponse(
                        "Model '$model' not found. Install it with: ollama pull $model"
                    )
                }
                else -> HttpErrorHandler.handleHttpError(response, responseBody)
            }
        }
    }

    private fun parseTextQueryResponse(responseBody: String): String {
        val jsonResponse = JSONObject(responseBody)

        if (jsonResponse.has("error")) {
            val error = jsonResponse.optString("error", "Unknown error")
            throw TranscriptionError.InvalidResponse("Ollama error: $error")
        }

        val content = jsonResponse.optString("response", "")

        if (content.isBlank()) {
            throw TranscriptionError.InvalidResponse("Empty response from Ollama")
        }

        return content.trim()
    }

    /**
     * Checks if Ollama is running and accessible.
     * Unlike other services, this doesn't require an API key but needs
     * the Ollama server to be running.
     */
    override fun isConfigured(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/tags")
                .get()
                .build()

            // Use a short timeout for the health check
            val healthCheckClient = httpClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            healthCheckClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.d(TAG, "Ollama health check failed: ${e.message}")
            false
        }
    }

    /**
     * Checks if a specific model is available in Ollama.
     */
    fun isModelAvailable(modelName: String = model): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/tags")
                .get()
                .build()

            val healthCheckClient = httpClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            healthCheckClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false

                val body = response.body?.string() ?: return@use false
                val jsonResponse = JSONObject(body)
                val models = jsonResponse.optJSONArray("models") ?: return@use false

                for (i in 0 until models.length()) {
                    val modelObj = models.getJSONObject(i)
                    val name = modelObj.optString("name", "")
                    if (name == modelName || name.startsWith("$modelName:")) {
                        return@use true
                    }
                }
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "Model availability check failed: ${e.message}")
            false
        }
    }

    /**
     * Not applicable for Ollama - no API key required.
     */
    override fun setApiKey(apiKey: String?) {
        // No-op: Ollama doesn't use API keys
    }

    override fun setCustomPrompt(template: String?) {
        this.customPrompt = template?.takeIf { it.isNotBlank() }
    }

    override fun getRateLimitState(): RateLimitState = rateLimitState

    override fun canMakeRequest(): Boolean {
        // Local Ollama has no rate limits
        return true
    }

    override fun getWaitTimeMs(): Long {
        // No rate limiting for local Ollama
        return 0
    }
}
