package com.synapse.api

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TranscriptionService implementation for Anthropic Claude API.
 *
 * Uses the claude-3-haiku model for vision-based handwriting transcription.
 * Requires a valid API key from Anthropic.
 */
class ClaudeService(
    @Volatile private var apiKey: String? = null,
    @Volatile private var customPrompt: String? = null,
    private val rateLimitingSafe: Boolean = true,
    sharedHttpClient: OkHttpClient? = null
) : TranscriptionService {

    companion object {
        private const val TAG = "ClaudeService"
        private const val BASE_URL = "https://api.anthropic.com/v1/messages"
        private const val DEFAULT_MODEL = "claude-3-5-haiku-20241022"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val TIMEOUT_SECONDS = 90L
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L

        // TODO: make rate limits configurable per account tier (see QA audit #18)
        // Claude API rate limits (varies by tier, using conservative defaults)
        private const val REQUESTS_PER_MINUTE = 50
        private const val REQUESTS_PER_DAY = 10000
    }

    override val provider: LlmProvider = LlmProvider.CLAUDE
    override val modelId: String = DEFAULT_MODEL

    private val rateLimitConfig = RateLimitConfig(REQUESTS_PER_MINUTE, REQUESTS_PER_DAY)
    private val rateLimitState = RateLimitState()

    // TODO: Consolidate OkHttpClient instances into a shared singleton (#42)
    private val httpClient = (sharedHttpClient?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun transcribe(
        chunks: List<ChunkData>,
        cleanupEnabled: Boolean,
        advancedFormatting: Boolean
    ): TranscriptionResult {
        if (!isConfigured()) {
            throw TranscriptionError.ApiKeyMissing()
        }

        if (chunks.isEmpty()) {
            return TranscriptionResult.empty()
        }

        // Check rate limits (only in safe mode)
        if (rateLimitingSafe && !canMakeRequest()) {
            val waitTime = getWaitTimeMs()
            Log.d(TAG, "Rate limited, waiting ${waitTime}ms")
            delay(waitTime)
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
            retryServerErrors = { it in 500..599 || it == 529 }
        ) {
            executeRequest(chunks, prompt, chunkContext)
        }
    }

    private fun executeRequest(
        chunks: List<ChunkData>,
        prompt: String,
        chunkContext: String
    ): TranscriptionResult {
        val key = apiKey ?: throw TranscriptionError.ApiKeyMissing()
        val requestBody = buildRequestBody(chunks, prompt, chunkContext)

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        rateLimitState.recordRequest()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            when {
                response.isSuccessful -> {
                    return parseResponse(responseBody, chunks.size)
                }
                else -> HttpErrorHandler.handleHttpError(
                    response, responseBody,
                    retryAfterHeader = "retry-after",
                    handle529AsOverloaded = true
                )
            }
        }
    }

    private fun buildRequestBody(
        chunks: List<ChunkData>,
        prompt: String,
        chunkContext: String
    ): JSONObject {
        val contentArray = JSONArray()

        // Add images first
        chunks.forEach { chunk ->
            val base64Image = Base64.encodeToString(chunk.image, Base64.NO_WRAP)
            val imageContent = JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/webp")
                    put("data", base64Image)
                })
            }
            contentArray.put(imageContent)
        }

        // Add text prompt after images
        contentArray.put(JSONObject().apply {
            put("type", "text")
            put("text", "$prompt\n\n$chunkContext")
        })

        val messages = JSONArray().put(
            JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            }
        )

        return JSONObject().apply {
            put("model", modelId)
            put("max_tokens", 8192)
            put("system", PromptTemplate.SYSTEM_PROMPT)
            put("messages", messages)
        }
    }

    private fun parseResponse(responseBody: String, chunkCount: Int): TranscriptionResult {
        try {
            val jsonResponse = JSONObject(responseBody)

            // Check for errors
            if (jsonResponse.has("error")) {
                val error = jsonResponse.getJSONObject("error")
                val message = error.optString("message", "Unknown error")
                val type = error.optString("type", "")

                when (type) {
                    "authentication_error" -> throw TranscriptionError.ApiKeyInvalid(message)
                    "rate_limit_error" -> throw TranscriptionError.RateLimitError(null)
                    "overloaded_error" -> throw TranscriptionError.ServiceUnavailable(message)
                    else -> throw TranscriptionError.InvalidResponse("API error: $message")
                }
            }

            // Check stop reason
            val stopReason = jsonResponse.optString("stop_reason", "")
            if (stopReason == "max_tokens") {
                Log.w(TAG, "Response was truncated due to max tokens")
            }

            // Extract the generated text
            val content = jsonResponse.optJSONArray("content")
            if (content == null || content.length() == 0) {
                Log.w(TAG, "No content in response")
                return TranscriptionResult.failure((0 until chunkCount).toList())
            }

            // Find the text content block
            var textContent = ""
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") {
                    textContent = block.optString("text", "")
                    break
                }
            }

            if (textContent.isBlank()) {
                Log.w(TAG, "Empty text content in response")
                return TranscriptionResult.failure((0 until chunkCount).toList())
            }

            // Parse the JSON output from the LLM
            return parseTranscriptionJson(textContent, chunkCount)
        } catch (e: TranscriptionError) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response", e)
            throw TranscriptionError.InvalidResponse("Failed to parse response: ${e.message}", e)
        }
    }

    private fun parseTranscriptionJson(content: String, chunkCount: Int): TranscriptionResult {
        return TranscriptionJsonParser.parse(content, chunkCount)
    }

    override suspend fun textQuery(prompt: String, systemPrompt: String?): String {
        if (!isConfigured()) {
            throw TranscriptionError.ApiKeyMissing()
        }

        if (rateLimitingSafe && !canMakeRequest()) {
            val waitTime = getWaitTimeMs()
            Log.d(TAG, "Rate limited, waiting ${waitTime}ms")
            delay(waitTime)
        }

        return executeTextQueryWithRetry(prompt, systemPrompt)
    }

    override suspend fun visionQuery(
        prompt: String,
        images: List<ByteArray>,
        systemPrompt: String?
    ): String {
        if (!isConfigured()) {
            throw TranscriptionError.ApiKeyMissing()
        }
        if (images.isEmpty()) {
            return textQuery(prompt, systemPrompt)
        }

        val contentArray = JSONArray()

        images.forEach { imageBytes ->
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            contentArray.put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/png")
                    put("data", base64Image)
                })
            })
        }

        contentArray.put(JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        })

        val messages = JSONArray().put(
            JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            }
        )

        val requestBody = JSONObject().apply {
            put("model", modelId)
            put("max_tokens", 8192)
            if (systemPrompt != null) {
                put("system", systemPrompt)
            }
            put("messages", messages)
        }

        val key = apiKey ?: throw TranscriptionError.ApiKeyMissing()

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        rateLimitState.recordRequest()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            when {
                response.isSuccessful -> return parseTextQueryResponse(responseBody)
                else -> HttpErrorHandler.handleHttpError(
                    response, responseBody,
                    retryAfterHeader = "retry-after",
                    handle529AsOverloaded = true
                )
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
            retryServerErrors = { it in 500..599 || it == 529 }
        ) {
            executeTextQueryRequest(prompt, systemPrompt)
        }
    }

    private fun executeTextQueryRequest(prompt: String, systemPrompt: String?): String {
        val key = apiKey ?: throw TranscriptionError.ApiKeyMissing()

        val contentArray = JSONArray()
        contentArray.put(JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        })

        val messages = JSONArray().put(
            JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            }
        )

        val requestBody = JSONObject().apply {
            put("model", modelId)
            put("max_tokens", 8192)
            if (systemPrompt != null) {
                put("system", systemPrompt)
            }
            put("messages", messages)
        }

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        rateLimitState.recordRequest()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            when {
                response.isSuccessful -> {
                    return parseTextQueryResponse(responseBody)
                }
                else -> HttpErrorHandler.handleHttpError(
                    response, responseBody,
                    retryAfterHeader = "retry-after",
                    handle529AsOverloaded = true
                )
            }
        }
    }

    private fun parseTextQueryResponse(responseBody: String): String {
        val jsonResponse = JSONObject(responseBody)

        if (jsonResponse.has("error")) {
            val error = jsonResponse.getJSONObject("error")
            val message = error.optString("message", "Unknown error")
            val type = error.optString("type", "")

            when (type) {
                "authentication_error" -> throw TranscriptionError.ApiKeyInvalid(message)
                "rate_limit_error" -> throw TranscriptionError.RateLimitError(null)
                "overloaded_error" -> throw TranscriptionError.ServiceUnavailable(message)
                else -> throw TranscriptionError.InvalidResponse("API error: $message")
            }
        }

        val content = jsonResponse.optJSONArray("content")
        if (content == null || content.length() == 0) {
            throw TranscriptionError.InvalidResponse("No content in response")
        }

        // Find the text content block
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") {
                val text = block.optString("text", "")
                if (text.isNotBlank()) {
                    return text.trim()
                }
            }
        }

        throw TranscriptionError.InvalidResponse("Empty text content in response")
    }

    override fun isConfigured(): Boolean {
        return !apiKey.isNullOrBlank()
    }

    override fun setApiKey(apiKey: String?) {
        this.apiKey = apiKey?.takeIf { it.isNotBlank() }
    }

    override fun setCustomPrompt(template: String?) {
        this.customPrompt = template?.takeIf { it.isNotBlank() }
    }

    override fun getRateLimitState(): RateLimitState = rateLimitState

    override fun canMakeRequest(): Boolean {
        return rateLimitState.canMakeRequest(rateLimitConfig)
    }

    override fun getWaitTimeMs(): Long {
        return rateLimitState.getWaitTimeMs(rateLimitConfig)
    }
}
