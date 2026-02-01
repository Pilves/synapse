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
 * Abstract base class for all LLM transcription services.
 *
 * Owns shared orchestration: validation, rate-limit waits, retry logic,
 * HTTP execution, and response parsing. Subclasses provide only
 * provider-specific request building and response extraction.
 */
abstract class BaseLlmService(
    apiKey: String? = null,
    customPrompt: String? = null,
    protected val rateLimitingSafe: Boolean = false,
    sharedHttpClient: OkHttpClient? = null
) : TranscriptionService {

    @Volatile protected var _apiKey: String? = apiKey
    @Volatile protected var _customPrompt: String? = customPrompt

    companion object {
        /** Maximum response body size (5MB) to prevent OOM on malformed responses. */
        private const val MAX_RESPONSE_BODY_BYTES = 5L * 1024L * 1024L

        /** Maximum backoff delay for retry logic. */
        private const val MAX_BACKOFF_MS = 30_000L
    }

    // ── Abstract properties ─────────────────────────────────────────────
    protected abstract val tag: String
    protected abstract val timeoutSeconds: Long
    protected abstract val maxRetries: Int
    protected abstract val initialRetryDelayMs: Long
    protected abstract val requestsPerMinute: Int

    // ── Abstract methods ────────────────────────────────────────────────
    protected abstract fun getTranscribeUrl(): String
    protected abstract fun getQueryUrl(): String
    protected abstract fun addAuthHeaders(builder: Request.Builder): Request.Builder
    protected abstract fun buildTranscribeRequestBody(chunks: List<ChunkData>, prompt: String, chunkContext: String): JSONObject
    protected abstract fun buildTextQueryRequestBody(prompt: String, systemPrompt: String?): JSONObject
    protected abstract fun buildVisionQueryRequestBody(prompt: String, images: List<ByteArray>, systemPrompt: String?): JSONObject
    protected abstract fun parseTranscriptionContent(responseBody: String): String?
    protected abstract fun parseQueryContent(responseBody: String): String
    protected abstract fun handleErrorResponse(responseBody: String)

    // ── Open hooks (overridable with defaults) ──────────────────────────

    /** Predicate for which server error codes to retry. Claude adds 529. */
    protected open val retryServerErrors: (Int) -> Boolean = { it in 500..599 }

    /** Optional transform for ConnectException. Ollama overrides this. */
    protected open val connectExceptionHandler: ((ConnectException) -> Exception)? = null

    /** Whether to use extractJsonBounds when parsing transcription JSON. Ollama overrides. */
    protected open val extractJsonBounds: Boolean = false

    /** Extra params for HttpErrorHandler. Override in Claude/OpenAI. */
    protected open val httpRetryAfterHeader: String = "Retry-After"
    protected open val httpExtraRetryAfterHeader: String? = null
    protected open val httpHandle529AsOverloaded: Boolean = false

    /** Override in Ollama to handle 404 model-not-found before the default handler. */
    protected open fun handleNonSuccessResponse(code: Int, responseBody: String, response: okhttp3.Response) {
        handleHttpError(
            response, responseBody,
            retryAfterHeader = httpRetryAfterHeader,
            extraRetryAfterHeader = httpExtraRetryAfterHeader,
            handle529AsOverloaded = httpHandle529AsOverloaded
        )
    }

    // ── Shared helpers ─────────────────────────────────────────────────

    /**
     * Encodes image bytes to Base64 string for API transmission.
     */
    protected fun encodeImageToBase64(imageBytes: ByteArray): String {
        return Base64.encodeToString(imageBytes, Base64.NO_WRAP)
    }

    /**
     * Builds combined prompt string with optional chunk context.
     */
    protected fun buildChunkContextString(prompt: String, chunkContext: String?): String {
        return if (chunkContext.isNullOrBlank()) prompt else "$prompt\n\n$chunkContext"
    }

    // ── JSON content-building helpers (Claude / OpenAI message format) ──

    /**
     * Creates a text content part: `{"type": "text", "text": "..."}`.
     * Used by Claude and OpenAI multipart content arrays.
     */
    protected fun buildTextContentPart(text: String): JSONObject =
        JSONObject().apply {
            put("type", "text")
            put("text", text)
        }

    /**
     * Creates a Claude-style image content part with base64 source.
     * `{"type": "image", "source": {"type": "base64", "media_type": ..., "data": ...}}`
     */
    protected fun buildClaudeImagePart(imageBytes: ByteArray, mimeType: String): JSONObject =
        JSONObject().apply {
            put("type", "image")
            put("source", JSONObject().apply {
                put("type", "base64")
                put("media_type", mimeType)
                put("data", encodeImageToBase64(imageBytes))
            })
        }

    /**
     * Creates an OpenAI-style image_url content part with a base64 data URI.
     * `{"type": "image_url", "image_url": {"url": "data:...;base64,...", "detail": "high"}}`
     */
    protected fun buildOpenAiImagePart(imageBytes: ByteArray, mimeType: String): JSONObject =
        JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().apply {
                put("url", "data:$mimeType;base64,${encodeImageToBase64(imageBytes)}")
                put("detail", "high")
            })
        }

    /**
     * Creates a Gemini-style inline_data part.
     * `{"inline_data": {"mime_type": ..., "data": ...}}`
     */
    protected fun buildGeminiImagePart(imageBytes: ByteArray, mimeType: String): JSONObject =
        JSONObject().apply {
            put("inline_data", JSONObject().apply {
                put("mime_type", mimeType)
                put("data", encodeImageToBase64(imageBytes))
            })
        }

    /**
     * Creates a message object: `{"role": role, "content": content}`.
     * Works for both string content and JSONArray content.
     */
    protected fun buildMessage(role: String, content: Any): JSONObject =
        JSONObject().apply {
            put("role", role)
            put("content", content)
        }

    /**
     * Builds a standard chat-completion request body with model, messages, and max_tokens.
     * Caller can add extra fields to the returned JSONObject.
     */
    protected fun buildChatRequestBody(
        model: String,
        messages: JSONArray,
        maxTokens: Int = 8192
    ): JSONObject = JSONObject().apply {
        put("model", model)
        put("messages", messages)
        put("max_tokens", maxTokens)
    }

    // ── Response parsing helpers ────────────────────────────────────────

    /**
     * Extracts the first text block from a Claude-style content array.
     * Iterates the array looking for `{"type": "text", "text": "..."}` entries.
     *
     * @return The first non-blank text value, or null if none found.
     */
    protected fun extractFirstTextBlock(contentArray: JSONArray?): String? {
        if (contentArray == null || contentArray.length() == 0) return null
        for (i in 0 until contentArray.length()) {
            val block = contentArray.getJSONObject(i)
            if (block.optString("type") == "text") {
                val text = block.optString("text", "")
                if (text.isNotBlank()) return text
            }
        }
        return null
    }

    /**
     * Extracts content from an OpenAI-style choices array.
     * Path: `choices[0].message.content`
     *
     * @return The content string, or null if choices is missing/empty.
     */
    protected fun extractChoiceMessageContent(choices: JSONArray?): String? {
        if (choices == null || choices.length() == 0) return null
        val message = choices.getJSONObject(0).optJSONObject("message") ?: return null
        return message.optString("content", "")
    }

    /**
     * Extracts content from a Gemini-style candidates array.
     * Path: `candidates[0].content.parts[0].text`
     *
     * @return The text string, or null if candidates is missing/empty.
     */
    protected fun extractCandidatePartText(candidates: JSONArray?): String? {
        if (candidates == null || candidates.length() == 0) return null
        return candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts")
            ?.getJSONObject(0)
            ?.optString("text", "")
    }

    /**
     * Field mappings for classifying error responses from different LLM providers.
     */
    data class ErrorFieldMappings(
        val errorPath: String,
        val typePath: String,
        val messagePath: String,
        val codeField: String? = null,
        val authErrors: Set<String>,
        val rateLimitErrors: Set<String>,
        val overloadedErrors: Set<String>
    )

    /**
     * Classifies an error response body using the provider's field mappings.
     * Throws the appropriate TranscriptionError.
     */
    protected fun classifyError(responseBody: String, mappings: ErrorFieldMappings) {
        val jsonResponse = JSONObject(responseBody)
        if (!jsonResponse.has(mappings.errorPath)) return

        val error = jsonResponse.get(mappings.errorPath)
        val message: String
        val type: String
        val code: String

        if (error is JSONObject) {
            message = error.optString(mappings.messagePath, "Unknown error")
            type = error.optString(mappings.typePath, "")
            code = if (mappings.codeField != null) error.optString(mappings.codeField, "") else ""
        } else {
            // Ollama has string errors
            message = error.toString()
            type = ""
            code = ""
        }

        when {
            type in mappings.authErrors || code in mappings.authErrors ||
                (type == "invalid_request_error" && message.contains("API key")) ->
                throw TranscriptionError.ApiKeyInvalid(message)
            type in mappings.rateLimitErrors || code in mappings.rateLimitErrors ->
                throw TranscriptionError.RateLimitError(null)
            type in mappings.overloadedErrors || code in mappings.overloadedErrors ->
                throw TranscriptionError.ServiceUnavailable(message)
            code == "insufficient_quota" ->
                throw TranscriptionError.ApiKeyInvalid("Insufficient quota: $message")
            else -> throw TranscriptionError.InvalidResponse("API error: $message")
        }
    }

    /**
     * Checks if an LLM response was truncated and logs a warning.
     *
     * @param field The field value indicating stop reason
     * @param truncationValues Values that indicate truncation (e.g., "max_tokens", "length")
     */
    protected fun checkTruncation(field: String?, vararg truncationValues: String) {
        if (field != null && field in truncationValues) {
            Log.w(tag, "Response was truncated (stop_reason=$field)")
        }
    }

    // ── Shared state ────────────────────────────────────────────────────
    /** Lazy is safe because [requestsPerMinute] is effectively immutable in all subclasses. */
    private val rateLimitConfig by lazy { RateLimitConfig(requestsPerMinute) }
    private val rateLimitState = RateLimitState()

    protected val httpClient: OkHttpClient by lazy {
        sharedHttpClient ?: OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
    }

    // ── TranscriptionService implementation ─────────────────────────────

    override suspend fun transcribe(
        chunks: List<ChunkData>,
        cleanupEnabled: Boolean,
        advancedFormatting: Boolean
    ): TranscriptionResult {
        if (!isConfigured()) throw TranscriptionError.ApiKeyMissing()
        if (chunks.isEmpty()) return TranscriptionResult.empty()

        if (rateLimitingSafe && !canMakeRequest()) {
            val waitTime = getWaitTimeMs()
            Log.d(tag, "Rate limited, waiting ${waitTime}ms")
            delay(waitTime)
        }

        val prompt = PromptTemplate.buildPrompt(cleanupEnabled, advancedFormatting, _customPrompt)
        val chunkContext = PromptTemplate.buildChunkContext(chunks)

        return executeWithRetry(
            maxRetries = maxRetries,
            initialDelayMs = initialRetryDelayMs,
            retryServerErrors = retryServerErrors,
            onConnectException = connectExceptionHandler
        ) {
            executeTranscribeRequest(chunks, prompt, chunkContext)
        }
    }

    override suspend fun textQuery(prompt: String, systemPrompt: String?): String {
        if (!isConfigured()) throw TranscriptionError.ApiKeyMissing()

        if (rateLimitingSafe && !canMakeRequest()) {
            val waitTime = getWaitTimeMs()
            Log.d(tag, "Rate limited, waiting ${waitTime}ms")
            delay(waitTime)
        }

        return executeWithRetry(
            maxRetries = maxRetries,
            initialDelayMs = initialRetryDelayMs,
            retryServerErrors = retryServerErrors,
            onConnectException = connectExceptionHandler
        ) {
            executeTextQueryRequest(prompt, systemPrompt)
        }
    }

    override suspend fun visionQuery(
        prompt: String,
        images: List<ByteArray>,
        systemPrompt: String?
    ): String {
        if (!isConfigured()) throw TranscriptionError.ApiKeyMissing()
        if (images.isEmpty()) return textQuery(prompt, systemPrompt)

        return executeWithRetry(
            maxRetries = maxRetries,
            initialDelayMs = initialRetryDelayMs,
            retryServerErrors = retryServerErrors,
            onConnectException = connectExceptionHandler
        ) {
            executeVisionQueryRequest(prompt, images, systemPrompt)
        }
    }

    override fun isConfigured(): Boolean = !_apiKey.isNullOrBlank()

    override fun setApiKey(apiKey: String?) {
        this._apiKey = apiKey?.takeIf { it.isNotBlank() }
        rateLimitState.reset()
    }

    override fun setCustomPrompt(template: String?) {
        this._customPrompt = template?.takeIf { it.isNotBlank() }
    }

    override fun getRateLimitState(): RateLimitState = rateLimitState

    override fun canMakeRequest(): Boolean = rateLimitState.canMakeRequest(rateLimitConfig)

    override fun getWaitTimeMs(): Long = rateLimitState.getWaitTimeMs(rateLimitConfig)

    // ── Private orchestration ───────────────────────────────────────────

    private fun executeTranscribeRequest(
        chunks: List<ChunkData>,
        prompt: String,
        chunkContext: String
    ): TranscriptionResult {
        val requestBody = buildTranscribeRequestBody(chunks, prompt, chunkContext)
        val request = buildRequest(getTranscribeUrl(), requestBody)

        rateLimitState.recordRequest()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = readResponseBody(response)
            Log.d(tag, "Transcribe response code: ${response.code}")

            if (!response.isSuccessful) {
                handleNonSuccessResponse(response.code, responseBody, response)
            }

            return parseTranscribeResponse(responseBody, chunks.size)
        }
    }

    private fun executeTextQueryRequest(prompt: String, systemPrompt: String?): String {
        val requestBody = buildTextQueryRequestBody(prompt, systemPrompt)
        val request = buildRequest(getQueryUrl(), requestBody)

        rateLimitState.recordRequest()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = readResponseBody(response)
            Log.d(tag, "Text query response code: ${response.code}")

            if (!response.isSuccessful) {
                handleNonSuccessResponse(response.code, responseBody, response)
            }

            return parseQueryContent(responseBody)
        }
    }

    private fun executeVisionQueryRequest(
        prompt: String,
        images: List<ByteArray>,
        systemPrompt: String?
    ): String {
        val requestBody = buildVisionQueryRequestBody(prompt, images, systemPrompt)
        val request = buildRequest(getQueryUrl(), requestBody)

        rateLimitState.recordRequest()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = readResponseBody(response)
            Log.d(tag, "Vision query response code: ${response.code}")

            if (!response.isSuccessful) {
                handleNonSuccessResponse(response.code, responseBody, response)
            }

            return parseQueryContent(responseBody)
        }
    }

    /**
     * Reads the response body with a size limit to prevent OOM on malformed responses.
     * Checks Content-Length header first to reject oversized responses early.
     */
    private fun readResponseBody(response: okhttp3.Response): String {
        val body = response.body ?: return ""
        // Early reject if Content-Length exceeds limit (avoids reading into memory)
        val contentLength = body.contentLength()
        if (contentLength > MAX_RESPONSE_BODY_BYTES) {
            Log.w(tag, "Response Content-Length ($contentLength) exceeds ${MAX_RESPONSE_BODY_BYTES / 1024 / 1024}MB limit, rejecting")
            throw TranscriptionError.InvalidResponse("Response too large: $contentLength bytes")
        }
        val source = body.source()
        source.request(MAX_RESPONSE_BODY_BYTES)
        val buffer = source.buffer
        if (buffer.size > MAX_RESPONSE_BODY_BYTES) {
            Log.w(tag, "Response body exceeds ${MAX_RESPONSE_BODY_BYTES / 1024 / 1024}MB limit, truncating")
        }
        return buffer.clone().readString(
            body.contentType()?.charset() ?: Charsets.UTF_8
        )
    }

    private fun buildRequest(url: String, body: JSONObject): Request {
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        return addAuthHeaders(builder).build()
    }

    private fun parseTranscribeResponse(responseBody: String, chunkCount: Int): TranscriptionResult {
        try {
            handleErrorResponse(responseBody)

            val content = parseTranscriptionContent(responseBody)
            if (content.isNullOrBlank()) {
                Log.w(tag, "Empty content in response")
                return TranscriptionResult.failure((0 until chunkCount).toList())
            }

            return parseTranscriptionJson(content, chunkCount, extractJsonBounds = extractJsonBounds)
        } catch (e: TranscriptionError) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse response", e)
            throw TranscriptionError.InvalidResponse("Failed to parse response: ${e.message}", e)
        }
    }

    // ── Inlined RetryHelper ─────────────────────────────────────────────

    /**
     * Executes a block with retry logic for transient errors.
     *
     * Retries on:
     * - [TranscriptionError.RateLimitError] -- uses Retry-After header when available
     * - [TranscriptionError.ServerError] with retryable status codes
     * - [TranscriptionError.ServiceUnavailable] -- local service not reachable
     * - [ConnectException] -- connection refused (e.g. Ollama not running)
     * - [IOException] -- generic network failure
     */
    private suspend fun <T> executeWithRetry(
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000L,
        retryServerErrors: (Int) -> Boolean = { it in 500..599 },
        onConnectException: ((ConnectException) -> Exception)? = null,
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        var retryDelay = initialDelayMs

        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: TranscriptionError.RateLimitError) {
                Log.w(tag, "Rate limited on attempt ${attempt + 1}, waiting...")
                val waitTime = e.retryAfterSeconds?.times(1000L) ?: retryDelay
                delay(waitTime + retryJitter(waitTime))
                retryDelay = minOf(retryDelay * 2, MAX_BACKOFF_MS)
                lastException = e
            } catch (e: TranscriptionError.ServiceUnavailable) {
                Log.w(tag, "Service unavailable on attempt ${attempt + 1}")
                delay(retryDelay + retryJitter(retryDelay))
                retryDelay = minOf(retryDelay * 2, MAX_BACKOFF_MS)
                lastException = e
            } catch (e: TranscriptionError.ServerError) {
                if (retryServerErrors(e.statusCode)) {
                    Log.w(tag, "Server error on attempt ${attempt + 1}: ${e.message}")
                    delay(retryDelay + retryJitter(retryDelay))
                    retryDelay = minOf(retryDelay * 2, MAX_BACKOFF_MS)
                    lastException = e
                } else {
                    throw e
                }
            } catch (e: ConnectException) {
                Log.w(tag, "Connection refused on attempt ${attempt + 1}")
                delay(retryDelay + retryJitter(retryDelay))
                retryDelay = minOf(retryDelay * 2, MAX_BACKOFF_MS)
                lastException = onConnectException?.invoke(e)
                    ?: TranscriptionError.NetworkError(e.message ?: "Connection refused", e)
            } catch (e: IOException) {
                Log.w(tag, "Network error on attempt ${attempt + 1}: ${e.message}")
                delay(retryDelay + retryJitter(retryDelay))
                retryDelay = minOf(retryDelay * 2, MAX_BACKOFF_MS)
                lastException = TranscriptionError.NetworkError(e.message ?: "Network error", e)
            }
        }

        throw lastException ?: TranscriptionError.Unknown("Max retries exceeded")
    }

    private fun retryJitter(delayMs: Long): Long {
        return (delayMs * (0.5 + 0.5 * Math.random())).toLong()
    }

    // ── Inlined HttpErrorHandler ────────────────────────────────────────

    /**
     * Throws the appropriate [TranscriptionError] for a non-successful HTTP response.
     */
    private fun handleHttpError(
        response: okhttp3.Response,
        responseBody: String,
        retryAfterHeader: String = "Retry-After",
        extraRetryAfterHeader: String? = null,
        handle529AsOverloaded: Boolean = false
    ): Nothing {
        val code = response.code

        when {
            code == 401 || code == 403 -> {
                throw TranscriptionError.ApiKeyInvalid(
                    "Invalid or unauthorized API key (HTTP $code): $responseBody"
                )
            }
            code == 429 -> {
                val retryAfter = response.header(retryAfterHeader)?.toIntOrNull()
                    ?: extraRetryAfterHeader?.let { response.header(it)?.toIntOrNull() }
                throw TranscriptionError.RateLimitError(retryAfter)
            }
            handle529AsOverloaded && code == 529 -> {
                throw TranscriptionError.ServiceUnavailable("API overloaded")
            }
            code in 500..599 -> {
                throw TranscriptionError.ServerError(code, responseBody)
            }
            else -> {
                throw TranscriptionError.Unknown("HTTP $code: $responseBody")
            }
        }
    }

    // ── Inlined TranscriptionJsonParser ─────────────────────────────────

    private val transcriptionJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parses the JSON transcription output from an LLM.
     *
     * @param content Raw text from the LLM (may include markdown fences)
     * @param chunkCount Total number of chunks sent, used to compute failed indices
     * @param extractJsonBounds If true, searches for the outermost `{...}` in the cleaned
     *   content before parsing. Useful for models (e.g. Ollama/llava) that sometimes
     *   embed JSON inside prose.
     */
    protected fun parseTranscriptionJson(
        content: String,
        chunkCount: Int,
        extractJsonBounds: Boolean = false
    ): TranscriptionResult {
        try {
            // Clean up markdown code fencing
            var cleanedContent = content
                .replace(Regex("^```json\\s*", RegexOption.MULTILINE), "")
                .replace(Regex("^```\\s*", RegexOption.MULTILINE), "")
                .replace(Regex("```$", RegexOption.MULTILINE), "")
                .trim()

            if (extractJsonBounds) {
                val jsonStart = cleanedContent.indexOf('{')
                val jsonEnd = cleanedContent.lastIndexOf('}')
                if (jsonStart == -1 || jsonEnd == -1 || jsonEnd < jsonStart) {
                    throw TranscriptionError.InvalidResponse("No valid JSON found in response")
                }
                cleanedContent = cleanedContent.substring(jsonStart, jsonEnd + 1)
            }

            val transcriptionResponse = transcriptionJson.decodeFromString<TranscriptionResponse>(cleanedContent)

            val notes = transcriptionResponse.notes.map { noteResponse ->
                Note(
                    text = noteResponse.text,
                    chunksUsed = noteResponse.chunksUsed
                )
            }

            val usedChunks = notes.flatMap { it.chunksUsed }.toSet()
            val failedChunks = (0 until chunkCount).filter { it !in usedChunks }

            return TranscriptionResult(notes, failedChunks)
        } catch (e: TranscriptionError) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse transcription JSON (${content.length} chars)", e)
            throw TranscriptionError.InvalidResponse("Invalid JSON from LLM: ${e.message}", e)
        }
    }
}
