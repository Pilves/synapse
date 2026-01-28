package com.synapse.api

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.TimeUnit

/**
 * TranscriptionService implementation for local Ollama server.
 *
 * Uses the llava model (or other vision-capable models) for handwriting transcription.
 * Runs locally, no API key required. Requires Ollama to be running on localhost.
 */
class OllamaService(
    private var baseUrl: String = DEFAULT_BASE_URL,
    private var model: String = DEFAULT_MODEL,
    private var customPrompt: String? = null
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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Sets the base URL for the Ollama server.
     * Default is http://localhost:11434
     */
    fun setBaseUrl(url: String) {
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
        var lastException: Exception? = null
        var retryDelay = INITIAL_RETRY_DELAY_MS

        repeat(MAX_RETRIES) { attempt ->
            try {
                return executeRequest(chunks, prompt, chunkContext)
            } catch (e: TranscriptionError.ServiceUnavailable) {
                Log.w(TAG, "Service unavailable on attempt ${attempt + 1}")
                delay(retryDelay)
                retryDelay *= 2
                lastException = e
            } catch (e: TranscriptionError.ServerError) {
                Log.w(TAG, "Server error on attempt ${attempt + 1}: ${e.message}")
                delay(retryDelay)
                retryDelay *= 2
                lastException = e
            } catch (e: ConnectException) {
                Log.w(TAG, "Connection refused on attempt ${attempt + 1}")
                delay(retryDelay)
                retryDelay *= 2
                lastException = TranscriptionError.ServiceUnavailable(
                    "Cannot connect to Ollama at $baseUrl. Is Ollama running?"
                )
            } catch (e: IOException) {
                Log.w(TAG, "Network error on attempt ${attempt + 1}: ${e.message}")
                delay(retryDelay)
                retryDelay *= 2
                lastException = TranscriptionError.NetworkError(e.message ?: "Network error", e)
            }
        }

        throw lastException ?: TranscriptionError.Unknown("Max retries exceeded")
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
                    // Model not found
                    throw TranscriptionError.InvalidResponse(
                        "Model '$model' not found. Install it with: ollama pull $model"
                    )
                }
                response.code in 500..599 -> {
                    throw TranscriptionError.ServerError(response.code, responseBody)
                }
                else -> {
                    throw TranscriptionError.Unknown("HTTP ${response.code}: $responseBody")
                }
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
                put("num_predict", 4096)
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
        try {
            // Clean up the content - remove markdown code fencing if present
            val cleanedContent = content
                .replace(Regex("^```json\\s*", RegexOption.MULTILINE), "")
                .replace(Regex("^```\\s*", RegexOption.MULTILINE), "")
                .replace(Regex("```$", RegexOption.MULTILINE), "")
                .trim()

            // Try to find JSON in the response (LLM might include extra text)
            val jsonStart = cleanedContent.indexOf('{')
            val jsonEnd = cleanedContent.lastIndexOf('}')

            if (jsonStart == -1 || jsonEnd == -1 || jsonEnd < jsonStart) {
                throw TranscriptionError.InvalidResponse("No valid JSON found in response")
            }

            val jsonContent = cleanedContent.substring(jsonStart, jsonEnd + 1)
            val transcriptionResponse = json.decodeFromString<TranscriptionResponse>(jsonContent)

            val notes = transcriptionResponse.notes.map { noteResponse ->
                Note(
                    text = noteResponse.text,
                    chunksUsed = noteResponse.chunksUsed
                )
            }

            // Determine which chunks weren't used (failed/skipped)
            val usedChunks = notes.flatMap { it.chunksUsed }.toSet()
            val failedChunks = (0 until chunkCount).filter { it !in usedChunks }

            return TranscriptionResult(notes, failedChunks)
        } catch (e: TranscriptionError) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse transcription JSON: $content", e)
            throw TranscriptionError.InvalidResponse("Invalid JSON from LLM: ${e.message}", e)
        }
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
            val healthCheckClient = OkHttpClient.Builder()
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

            val healthCheckClient = OkHttpClient.Builder()
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
