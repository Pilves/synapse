package com.synapse.api

import android.util.Log
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * TranscriptionService implementation for Anthropic Claude API.
 *
 * Uses the claude-3-5-haiku model for vision-based handwriting transcription.
 * Requires a valid API key from Anthropic.
 */
class ClaudeService(
    apiKey: String? = null,
    customPrompt: String? = null,
    rateLimitingSafe: Boolean = true,
    sharedHttpClient: OkHttpClient? = null
) : BaseLlmService(apiKey, customPrompt, rateLimitingSafe, sharedHttpClient) {

    companion object {
        private const val BASE_URL = "https://api.anthropic.com/v1/messages"
        private const val DEFAULT_MODEL = "claude-3-5-haiku-20241022"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        val ERROR_MAPPINGS = ErrorFieldMappings(
            errorPath = "error",
            typePath = "type",
            messagePath = "message",
            authErrors = setOf("authentication_error"),
            rateLimitErrors = setOf("rate_limit_error"),
            overloadedErrors = setOf("overloaded_error")
        )
    }

    override val provider: LlmProvider = LlmProvider.CLAUDE
    override val modelId: String = DEFAULT_MODEL

    override val tag = "ClaudeService"
    override val timeoutSeconds = 90L
    override val maxRetries = 3
    override val initialRetryDelayMs = 1000L
    override val requestsPerMinute = 50

    override val retryServerErrors: (Int) -> Boolean = { it in 500..599 || it == 529 }
    override val httpRetryAfterHeader = "retry-after"
    override val httpHandle529AsOverloaded = true

    override fun getTranscribeUrl() = BASE_URL
    override fun getQueryUrl() = BASE_URL

    override fun addAuthHeaders(builder: okhttp3.Request.Builder) = builder
        .addHeader("Content-Type", "application/json")
        .addHeader("x-api-key", _apiKey!!)
        .addHeader("anthropic-version", ANTHROPIC_VERSION)

    override fun buildTranscribeRequestBody(
        chunks: List<ChunkData>,
        prompt: String,
        chunkContext: String
    ): JSONObject {
        val contentArray = JSONArray()

        chunks.forEach { chunk ->
            contentArray.put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/webp")
                    put("data", encodeImageToBase64(chunk.image))
                })
            })
        }

        contentArray.put(JSONObject().apply {
            put("type", "text")
            put("text", buildChunkContextString(prompt, chunkContext))
        })

        val messages = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", contentArray)
        })

        return JSONObject().apply {
            put("model", modelId)
            put("max_tokens", 8192)
            put("system", PromptTemplate.SYSTEM_PROMPT)
            put("messages", messages)
        }
    }

    override fun buildTextQueryRequestBody(prompt: String, systemPrompt: String?): JSONObject {
        val contentArray = JSONArray()
        contentArray.put(JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        })

        val messages = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", contentArray)
        })

        return JSONObject().apply {
            put("model", modelId)
            put("max_tokens", 8192)
            if (systemPrompt != null) put("system", systemPrompt)
            put("messages", messages)
        }
    }

    override fun buildVisionQueryRequestBody(
        prompt: String,
        images: List<ByteArray>,
        systemPrompt: String?
    ): JSONObject {
        val contentArray = JSONArray()

        images.forEach { imageBytes ->
            contentArray.put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/png")
                    put("data", encodeImageToBase64(imageBytes))
                })
            })
        }

        contentArray.put(JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        })

        val messages = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", contentArray)
        })

        return JSONObject().apply {
            put("model", modelId)
            put("max_tokens", 8192)
            if (systemPrompt != null) put("system", systemPrompt)
            put("messages", messages)
        }
    }

    override fun parseTranscriptionContent(responseBody: String): String? {
        val jsonResponse = JSONObject(responseBody)

        val stopReason = jsonResponse.optString("stop_reason", "")
        if (stopReason == "max_tokens") {
            Log.w(tag, "Response was truncated due to max tokens")
        }

        val content = jsonResponse.optJSONArray("content")
        if (content == null || content.length() == 0) {
            Log.w(tag, "No content in response")
            return null
        }

        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") {
                val text = block.optString("text", "")
                if (text.isNotBlank()) return text
            }
        }
        return null
    }

    override fun parseQueryContent(responseBody: String): String {
        handleErrorResponse(responseBody)

        val jsonResponse = JSONObject(responseBody)
        val content = jsonResponse.optJSONArray("content")
        if (content == null || content.length() == 0) {
            throw TranscriptionError.InvalidResponse("No content in response")
        }

        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") {
                val text = block.optString("text", "")
                if (text.isNotBlank()) return text.trim()
            }
        }

        throw TranscriptionError.InvalidResponse("Empty text content in response")
    }

    override fun handleErrorResponse(responseBody: String) {
        classifyError(responseBody, ERROR_MAPPINGS)
    }
}
