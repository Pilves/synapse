package com.synapse.api

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TranscriptionService implementation for Google Gemini API.
 *
 * Uses the gemini-1.5-flash model for vision-based handwriting transcription.
 * Supports the free tier rate limits: 15 RPM, 1500 requests/day.
 */
class GeminiService(
    @Volatile private var apiKey: String? = null,
    @Volatile private var customPrompt: String? = null,
    sharedHttpClient: OkHttpClient? = null
) : TranscriptionService {

    companion object {
        private const val TAG = "GeminiService"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val DEFAULT_MODEL = "gemini-2.0-flash"
        private const val TIMEOUT_SECONDS = 60L
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L

        // Free tier rate limits
        private const val REQUESTS_PER_MINUTE = 15
        private const val REQUESTS_PER_DAY = 1500

        /** Safety threshold applied to all categories. Change to e.g. "BLOCK_MEDIUM_AND_ABOVE" for stricter filtering. */
        const val DEFAULT_SAFETY_THRESHOLD = "BLOCK_NONE"
    }

    override val provider: LlmProvider = LlmProvider.GEMINI
    override val modelId: String = DEFAULT_MODEL

    private val rateLimitConfig = RateLimitConfig(REQUESTS_PER_MINUTE, REQUESTS_PER_DAY)
    private val rateLimitState = RateLimitState()

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

        // Note: Removed internal rate limit pre-check - let API return 429 if needed
        // Our internal tracker was blocking requests after previous failures

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
            tag = TAG
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
        val url = "$BASE_URL/$modelId:generateContent"

        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey!!)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        rateLimitState.recordRequest()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "Gemini response code: ${response.code}")
            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini error response (${responseBody.length} chars)")
            }

            when {
                response.isSuccessful -> {
                    return parseResponse(responseBody, chunks.size)
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
        val parts = JSONArray()

        // Add text prompt
        parts.put(JSONObject().put("text", "$prompt\n\n$chunkContext"))

        // Add images as inline data
        chunks.forEach { chunk ->
            val base64Image = Base64.encodeToString(chunk.image, Base64.NO_WRAP)
            val inlineData = JSONObject().apply {
                put("mime_type", "image/webp")
                put("data", base64Image)
            }
            parts.put(JSONObject().put("inline_data", inlineData))
        }

        val contents = JSONArray().put(
            JSONObject().apply {
                put("role", "user")
                put("parts", parts)
            }
        )

        val generationConfig = JSONObject().apply {
            put("temperature", 0.2)
            put("topP", 0.8)
            put("maxOutputTokens", 8192)
        }

        val safetySettings = JSONArray().apply {
            listOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT"
            ).forEach { category ->
                put(JSONObject().apply {
                    put("category", category)
                    put("threshold", DEFAULT_SAFETY_THRESHOLD)
                })
            }
        }

        return JSONObject().apply {
            put("contents", contents)
            put("generationConfig", generationConfig)
            put("safetySettings", safetySettings)
        }
    }

    private fun parseResponse(responseBody: String, chunkCount: Int): TranscriptionResult {
        try {
            val jsonResponse = JSONObject(responseBody)

            // Check for errors in response
            if (jsonResponse.has("error")) {
                val error = jsonResponse.getJSONObject("error")
                val message = error.optString("message", "Unknown error")
                throw TranscriptionError.InvalidResponse("API error: $message")
            }

            // Extract the generated text
            val candidates = jsonResponse.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                Log.w(TAG, "No candidates in response")
                return TranscriptionResult.failure((0 until chunkCount).toList())
            }

            val content = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts")
                ?.getJSONObject(0)
                ?.optString("text", "")
                ?: ""

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
        return TranscriptionJsonParser.parse(content, chunkCount)
    }

    override suspend fun textQuery(prompt: String, systemPrompt: String?): String {
        if (!isConfigured()) {
            throw TranscriptionError.ApiKeyMissing()
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

        return RetryHelper.executeWithRetry(
            maxRetries = MAX_RETRIES,
            initialDelayMs = INITIAL_RETRY_DELAY_MS,
            tag = TAG
        ) {
            executeVisionQueryRequest(prompt, images, systemPrompt)
        }
    }

    private fun executeVisionQueryRequest(
        prompt: String,
        images: List<ByteArray>,
        systemPrompt: String?
    ): String {
        val parts = JSONArray()

        val fullPrompt = if (systemPrompt != null) "$systemPrompt\n\n$prompt" else prompt
        parts.put(JSONObject().put("text", fullPrompt))

        images.forEach { imageBytes ->
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val inlineData = JSONObject().apply {
                put("mime_type", "image/png")
                put("data", base64Image)
            }
            parts.put(JSONObject().put("inline_data", inlineData))
        }

        val contents = JSONArray().put(
            JSONObject().apply {
                put("role", "user")
                put("parts", parts)
            }
        )

        val generationConfig = JSONObject().apply {
            put("temperature", 0.3)
            put("topP", 0.9)
            put("maxOutputTokens", 8192)
        }

        val requestBody = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", generationConfig)
        }

        val url = "$BASE_URL/$modelId:generateContent"

        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey!!)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        rateLimitState.recordRequest()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "Gemini vision query response code: ${response.code}")

            when {
                response.isSuccessful -> return parseTextQueryResponse(responseBody)
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
            tag = TAG
        ) {
            executeTextQueryRequest(prompt, systemPrompt)
        }
    }

    private fun executeTextQueryRequest(prompt: String, systemPrompt: String?): String {
        val parts = JSONArray()

        // Add system prompt as initial text if provided
        val fullPrompt = if (systemPrompt != null) {
            "$systemPrompt\n\n$prompt"
        } else {
            prompt
        }
        parts.put(JSONObject().put("text", fullPrompt))

        val contents = JSONArray().put(
            JSONObject().apply {
                put("role", "user")
                put("parts", parts)
            }
        )

        val generationConfig = JSONObject().apply {
            put("temperature", 0.3)
            put("topP", 0.9)
            put("maxOutputTokens", 8192)
        }

        val requestBody = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", generationConfig)
        }

        val url = "$BASE_URL/$modelId:generateContent"

        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey!!)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        rateLimitState.recordRequest()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "Gemini text query response code: ${response.code}")

            when {
                response.isSuccessful -> {
                    return parseTextQueryResponse(responseBody)
                }
                else -> HttpErrorHandler.handleHttpError(response, responseBody)
            }
        }
    }

    private fun parseTextQueryResponse(responseBody: String): String {
        val jsonResponse = JSONObject(responseBody)

        if (jsonResponse.has("error")) {
            val error = jsonResponse.getJSONObject("error")
            val message = error.optString("message", "Unknown error")
            throw TranscriptionError.InvalidResponse("API error: $message")
        }

        val candidates = jsonResponse.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw TranscriptionError.InvalidResponse("No candidates in response")
        }

        val content = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts")
            ?.getJSONObject(0)
            ?.optString("text", "")
            ?: ""

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
