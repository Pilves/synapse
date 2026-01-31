package com.synapse.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * TranscriptionService implementation for local Ollama server.
 *
 * Uses the llava model (or other vision-capable models) for handwriting transcription.
 * Runs locally, no API key required. Requires Ollama to be running on localhost.
 */
open class OllamaService(
    @Volatile private var baseUrl: String = DEFAULT_BASE_URL,
    private var model: String = DEFAULT_MODEL,
    customPrompt: String? = null,
    sharedHttpClient: OkHttpClient? = null
) : BaseLlmService(apiKey = null, customPrompt = customPrompt, rateLimitingSafe = false, sharedHttpClient = sharedHttpClient) {

    override val provider: LlmProvider = LlmProvider.OLLAMA
    override val modelId: String get() = model

    override val tag = "OllamaService"
    override val timeoutSeconds = 120L
    override val maxRetries = 2
    override val initialRetryDelayMs = 500L
    override val requestsPerMinute = Int.MAX_VALUE

    override val extractJsonBounds = true

    override val connectExceptionHandler: ((ConnectException) -> Exception) = { _ ->
        TranscriptionError.ServiceUnavailable(
            "Cannot connect to Ollama at $baseUrl. Is Ollama running?"
        )
    }

    override fun getTranscribeUrl() = "$baseUrl/api/generate"
    override fun getQueryUrl() = "$baseUrl/api/generate"

    override fun addAuthHeaders(builder: Request.Builder) = builder // No auth needed

    override fun handleNonSuccessResponse(code: Int, responseBody: String, response: okhttp3.Response) {
        if (code == 404) {
            throw TranscriptionError.InvalidResponse(
                "Model '$model' not found. Install it with: ollama pull $model"
            )
        }
        super.handleNonSuccessResponse(code, responseBody, response)
    }

    override fun buildTranscribeRequestBody(
        chunks: List<ChunkData>,
        prompt: String,
        chunkContext: String
    ): JSONObject {
        val images = JSONArray()
        chunks.forEach { chunk ->
            images.put(encodeImageToBase64(chunk.image))
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
            put("stream", false)
            put("options", JSONObject().apply {
                put("temperature", 0.2)
                put("top_p", 0.8)
                put("num_predict", 8192)
            })
        }
    }

    override fun buildTextQueryRequestBody(prompt: String, systemPrompt: String?): JSONObject {
        val fullPrompt = if (systemPrompt != null) "$systemPrompt\n\n$prompt" else prompt

        return JSONObject().apply {
            put("model", model)
            put("prompt", fullPrompt)
            put("stream", false)
            put("options", JSONObject().apply {
                put("temperature", 0.3)
                put("top_p", 0.9)
                put("num_predict", 8192)
            })
        }
    }

    override fun buildVisionQueryRequestBody(
        prompt: String,
        images: List<ByteArray>,
        systemPrompt: String?
    ): JSONObject {
        val fullPrompt = if (systemPrompt != null) "$systemPrompt\n\n$prompt" else prompt

        val imagesArray = JSONArray()
        images.forEach { imageBytes ->
            imagesArray.put(encodeImageToBase64(imageBytes))
        }

        return JSONObject().apply {
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
    }

    override fun parseTranscriptionContent(responseBody: String): String? {
        val jsonResponse = JSONObject(responseBody)

        val done = jsonResponse.optBoolean("done", true)
        if (!done) {
            Log.w(tag, "Response generation was incomplete")
        }

        val content = jsonResponse.optString("response", "")
        return content.ifBlank { null }
    }

    override fun parseQueryContent(responseBody: String): String {
        handleErrorResponse(responseBody)

        val jsonResponse = JSONObject(responseBody)
        val content = jsonResponse.optString("response", "")

        if (content.isBlank()) {
            throw TranscriptionError.InvalidResponse("Empty response from Ollama")
        }
        return content.trim()
    }

    override fun handleErrorResponse(responseBody: String) {
        val jsonResponse = JSONObject(responseBody)
        if (jsonResponse.has("error")) {
            val error = jsonResponse.optString("error", "Unknown error")
            throw TranscriptionError.InvalidResponse("Ollama error: $error")
        }
    }

    // ── Ollama-specific: no API key, health-check isConfigured ──────────

    override fun isConfigured(): Boolean {
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
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.d(tag, "Ollama health check failed: ${e.message}")
            false
        }
    }

    override fun setApiKey(apiKey: String?) {
        // No-op: Ollama doesn't use API keys
    }

    override fun canMakeRequest(): Boolean = true
    override fun getWaitTimeMs(): Long = 0

    // ── Ollama-specific configuration ───────────────────────────────────

    companion object {
        private const val DEFAULT_BASE_URL = "http://localhost:11434"
        private const val DEFAULT_MODEL = "llava"

        private val ALLOWED_HOSTS = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

        private fun isAllowedHost(host: String): Boolean {
            if (host in ALLOWED_HOSTS) return true
            // Allow 192.168.* for local network Ollama instances
            if (host.startsWith("192.168.")) return true
            return false
        }
    }

    fun setBaseUrl(url: String) {
        val parsed = URL(url.trimEnd('/'))
        require(parsed.protocol in listOf("http", "https")) { "Invalid URL scheme: ${parsed.protocol}" }
        require(!parsed.host.isNullOrEmpty()) { "Invalid URL host" }
        require(isAllowedHost(parsed.host)) {
            "URL host '${parsed.host}' is not in the allowlist. " +
            "Allowed: localhost, 127.0.0.1, ::1, 10.0.2.2, 192.168.*"
        }
        this.baseUrl = url.trimEnd('/')
    }

    fun setModel(modelName: String) {
        this.model = modelName
    }

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
            Log.d(tag, "Model availability check failed: ${e.message}")
            false
        }
    }
}
