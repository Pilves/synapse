package com.synapse.api

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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

    /** Override in Ollama to handle 404 model-not-found before HttpErrorHandler. */
    protected open fun handleNonSuccessResponse(code: Int, responseBody: String, response: okhttp3.Response) {
        HttpErrorHandler.handleHttpError(
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

    // ── Shared state ────────────────────────────────────────────────────
    /** Lazy is safe because [requestsPerMinute] is effectively immutable in all subclasses. */
    private val rateLimitConfig by lazy { RateLimitConfig(requestsPerMinute) }
    private val rateLimitState = RateLimitState()

    protected val httpClient: OkHttpClient by lazy {
        (sharedHttpClient?.newBuilder() ?: OkHttpClient.Builder())
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

        return RetryHelper.executeWithRetry(
            maxRetries = maxRetries,
            initialDelayMs = initialRetryDelayMs,
            tag = tag,
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

        return RetryHelper.executeWithRetry(
            maxRetries = maxRetries,
            initialDelayMs = initialRetryDelayMs,
            tag = tag,
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

        return RetryHelper.executeWithRetry(
            maxRetries = maxRetries,
            initialDelayMs = initialRetryDelayMs,
            tag = tag,
            retryServerErrors = retryServerErrors,
            onConnectException = connectExceptionHandler
        ) {
            executeVisionQueryRequest(prompt, images, systemPrompt)
        }
    }

    override fun isConfigured(): Boolean = !_apiKey.isNullOrBlank()

    override fun setApiKey(apiKey: String?) {
        this._apiKey = apiKey?.takeIf { it.isNotBlank() }
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
            val responseBody = response.body?.string() ?: ""
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
            val responseBody = response.body?.string() ?: ""
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
            val responseBody = response.body?.string() ?: ""
            Log.d(tag, "Vision query response code: ${response.code}")

            if (!response.isSuccessful) {
                handleNonSuccessResponse(response.code, responseBody, response)
            }

            return parseQueryContent(responseBody)
        }
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

            return TranscriptionJsonParser.parse(content, chunkCount, extractJsonBounds = extractJsonBounds)
        } catch (e: TranscriptionError) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse response", e)
            throw TranscriptionError.InvalidResponse("Failed to parse response: ${e.message}", e)
        }
    }
}
