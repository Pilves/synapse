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
import java.util.concurrent.TimeUnit

/**
 * TranscriptionService implementation for OpenAI API.
 *
 * Uses the gpt-4o-mini model with Vision API for handwriting transcription.
 * Requires a valid API key from OpenAI.
 */
class OpenAiService(
    private var apiKey: String? = null,
    private var customPrompt: String? = null,
    private val rateLimitingSafe: Boolean = true
) : TranscriptionService {

    companion object {
        private const val TAG = "OpenAiService"
        private const val BASE_URL = "https://api.openai.com/v1/chat/completions"
        private const val DEFAULT_MODEL = "gpt-4o-mini"
        private const val TIMEOUT_SECONDS = 90L
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L

        // OpenAI rate limits (varies by tier, using conservative defaults for Tier 1)
        private const val REQUESTS_PER_MINUTE = 500
        private const val REQUESTS_PER_DAY = 10000
    }

    override val provider: LlmProvider = LlmProvider.OPENAI
    override val modelId: String = DEFAULT_MODEL

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
        var lastException: Exception? = null
        var retryDelay = INITIAL_RETRY_DELAY_MS

        repeat(MAX_RETRIES) { attempt ->
            try {
                return executeRequest(chunks, prompt, chunkContext)
            } catch (e: TranscriptionError.RateLimitError) {
                Log.w(TAG, "Rate limited on attempt ${attempt + 1}, waiting...")
                val waitTime = e.retryAfterSeconds?.times(1000L) ?: retryDelay
                delay(waitTime)
                retryDelay *= 2
                lastException = e
            } catch (e: TranscriptionError.ServerError) {
                if (e.statusCode in 500..599) {
                    Log.w(TAG, "Server error on attempt ${attempt + 1}: ${e.message}")
                    delay(retryDelay)
                    retryDelay *= 2
                    lastException = e
                } else {
                    throw e
                }
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
        val key = apiKey ?: throw TranscriptionError.ApiKeyMissing()
        val requestBody = buildRequestBody(chunks, prompt, chunkContext)

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $key")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        rateLimitState.recordRequest()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            when {
                response.isSuccessful -> {
                    return parseResponse(responseBody, chunks.size)
                }
                response.code == 401 -> {
                    throw TranscriptionError.ApiKeyInvalid("Invalid API key")
                }
                response.code == 403 -> {
                    // Check if it's a permission or billing issue
                    val errorJson = try {
                        JSONObject(responseBody)
                    } catch (e: Exception) {
                        null
                    }
                    val errorMessage = errorJson?.optJSONObject("error")?.optString("message", "")
                        ?: "Access denied"
                    throw TranscriptionError.ApiKeyInvalid(errorMessage)
                }
                response.code == 429 -> {
                    val retryAfter = response.header("retry-after")?.toIntOrNull()
                        ?: response.header("x-ratelimit-reset-tokens")?.toIntOrNull()
                    throw TranscriptionError.RateLimitError(retryAfter)
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
        val contentArray = JSONArray()

        // Add text prompt first
        contentArray.put(JSONObject().apply {
            put("type", "text")
            put("text", "$prompt\n\n$chunkContext")
        })

        // Add images
        chunks.forEach { chunk ->
            val base64Image = Base64.encodeToString(chunk.image, Base64.NO_WRAP)
            val imageUrl = "data:image/webp;base64,$base64Image"

            contentArray.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", imageUrl)
                    put("detail", "high") // Use high detail for handwriting recognition
                })
            })
        }

        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", contentArray)
        }

        val systemMessage = JSONObject().apply {
            put("role", "system")
            put("content", PromptTemplate.SYSTEM_PROMPT)
        }

        val messages = JSONArray().apply {
            put(systemMessage)
            put(userMessage)
        }

        return JSONObject().apply {
            put("model", modelId)
            put("messages", messages)
            put("max_tokens", 8192)
            put("temperature", 0.2)
            put("top_p", 0.8)
            // Request JSON output mode
            put("response_format", JSONObject().put("type", "json_object"))
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
                val code = error.optString("code", "")

                when {
                    code == "invalid_api_key" || type == "invalid_request_error" && message.contains("API key") ->
                        throw TranscriptionError.ApiKeyInvalid(message)
                    code == "rate_limit_exceeded" || type == "tokens" ->
                        throw TranscriptionError.RateLimitError(null)
                    code == "insufficient_quota" ->
                        throw TranscriptionError.ApiKeyInvalid("Insufficient quota: $message")
                    else -> throw TranscriptionError.InvalidResponse("API error: $message")
                }
            }

            // Extract the generated text
            val choices = jsonResponse.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                Log.w(TAG, "No choices in response")
                return TranscriptionResult.failure((0 until chunkCount).toList())
            }

            val message = choices.getJSONObject(0).optJSONObject("message")
            val content = message?.optString("content", "") ?: ""

            // Check finish reason
            val finishReason = choices.getJSONObject(0).optString("finish_reason", "")
            if (finishReason == "length") {
                Log.w(TAG, "Response was truncated due to max tokens")
            }

            if (content.isBlank()) {
                Log.w(TAG, "Empty content in response")
                return TranscriptionResult.failure((0 until chunkCount).toList())
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

            val transcriptionResponse = json.decodeFromString<TranscriptionResponse>(cleanedContent)

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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse transcription JSON: $content", e)
            throw TranscriptionError.InvalidResponse("Invalid JSON from LLM: ${e.message}", e)
        }
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

        val messages = JSONArray()

        if (systemPrompt != null) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        val contentArray = JSONArray()
        contentArray.put(JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        })

        images.forEach { imageBytes ->
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            contentArray.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/png;base64,$base64Image")
                    put("detail", "high")
                })
            })
        }

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", contentArray)
        })

        val requestBody = JSONObject().apply {
            put("model", modelId)
            put("messages", messages)
            put("max_tokens", 8192)
            put("temperature", 0.3)
        }

        val key = apiKey ?: throw TranscriptionError.ApiKeyMissing()

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $key")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        rateLimitState.recordRequest()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            when {
                response.isSuccessful -> return parseTextQueryResponse(responseBody)
                response.code == 401 ->
                    throw TranscriptionError.ApiKeyInvalid("Invalid API key")
                response.code == 429 -> {
                    val retryAfter = response.header("retry-after")?.toIntOrNull()
                    throw TranscriptionError.RateLimitError(retryAfter)
                }
                response.code in 500..599 ->
                    throw TranscriptionError.ServerError(response.code, responseBody)
                else ->
                    throw TranscriptionError.Unknown("HTTP ${response.code}: $responseBody")
            }
        }
    }

    private suspend fun executeTextQueryWithRetry(
        prompt: String,
        systemPrompt: String?
    ): String {
        var lastException: Exception? = null
        var retryDelay = INITIAL_RETRY_DELAY_MS

        repeat(MAX_RETRIES) { attempt ->
            try {
                return executeTextQueryRequest(prompt, systemPrompt)
            } catch (e: TranscriptionError.RateLimitError) {
                Log.w(TAG, "Rate limited on attempt ${attempt + 1}, waiting...")
                val waitTime = e.retryAfterSeconds?.times(1000L) ?: retryDelay
                delay(waitTime)
                retryDelay *= 2
                lastException = e
            } catch (e: TranscriptionError.ServerError) {
                if (e.statusCode in 500..599) {
                    Log.w(TAG, "Server error on attempt ${attempt + 1}: ${e.message}")
                    delay(retryDelay)
                    retryDelay *= 2
                    lastException = e
                } else {
                    throw e
                }
            } catch (e: IOException) {
                Log.w(TAG, "Network error on attempt ${attempt + 1}: ${e.message}")
                delay(retryDelay)
                retryDelay *= 2
                lastException = TranscriptionError.NetworkError(e.message ?: "Network error", e)
            }
        }

        throw lastException ?: TranscriptionError.Unknown("Max retries exceeded")
    }

    private fun executeTextQueryRequest(prompt: String, systemPrompt: String?): String {
        val key = apiKey ?: throw TranscriptionError.ApiKeyMissing()

        val messages = JSONArray()

        if (systemPrompt != null) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", prompt)
        })

        val requestBody = JSONObject().apply {
            put("model", modelId)
            put("messages", messages)
            put("max_tokens", 8192)
            put("temperature", 0.3)
            put("top_p", 0.9)
        }

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $key")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        rateLimitState.recordRequest()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            when {
                response.isSuccessful -> {
                    return parseTextQueryResponse(responseBody)
                }
                response.code == 401 -> {
                    throw TranscriptionError.ApiKeyInvalid("Invalid API key")
                }
                response.code == 403 -> {
                    val errorJson = try { JSONObject(responseBody) } catch (e: Exception) { null }
                    val errorMessage = errorJson?.optJSONObject("error")?.optString("message", "")
                        ?: "Access denied"
                    throw TranscriptionError.ApiKeyInvalid(errorMessage)
                }
                response.code == 429 -> {
                    val retryAfter = response.header("retry-after")?.toIntOrNull()
                        ?: response.header("x-ratelimit-reset-tokens")?.toIntOrNull()
                    throw TranscriptionError.RateLimitError(retryAfter)
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

    private fun parseTextQueryResponse(responseBody: String): String {
        val jsonResponse = JSONObject(responseBody)

        if (jsonResponse.has("error")) {
            val error = jsonResponse.getJSONObject("error")
            val message = error.optString("message", "Unknown error")
            val code = error.optString("code", "")

            when {
                code == "invalid_api_key" -> throw TranscriptionError.ApiKeyInvalid(message)
                code == "rate_limit_exceeded" -> throw TranscriptionError.RateLimitError(null)
                code == "insufficient_quota" -> throw TranscriptionError.ApiKeyInvalid("Insufficient quota: $message")
                else -> throw TranscriptionError.InvalidResponse("API error: $message")
            }
        }

        val choices = jsonResponse.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw TranscriptionError.InvalidResponse("No choices in response")
        }

        val message = choices.getJSONObject(0).optJSONObject("message")
        val content = message?.optString("content", "") ?: ""

        if (content.isBlank()) {
            throw TranscriptionError.InvalidResponse("Empty content in response")
        }

        return content.trim()
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
