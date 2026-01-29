package com.synapse.api

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * TranscriptionService implementation for OpenAI API.
 *
 * Uses the gpt-4o-mini model with Vision API for handwriting transcription.
 * Requires a valid API key from OpenAI.
 */
class OpenAiService(
    apiKey: String? = null,
    customPrompt: String? = null,
    rateLimitingSafe: Boolean = true,
    sharedHttpClient: OkHttpClient? = null
) : BaseLlmService(apiKey, customPrompt, rateLimitingSafe, sharedHttpClient) {

    companion object {
        private const val BASE_URL = "https://api.openai.com/v1/chat/completions"
        private const val DEFAULT_MODEL = "gpt-4o-mini"
    }

    override val provider: LlmProvider = LlmProvider.OPENAI
    override val modelId: String = DEFAULT_MODEL

    override val tag = "OpenAiService"
    override val timeoutSeconds = 90L
    override val maxRetries = 3
    override val initialRetryDelayMs = 1000L
    override val requestsPerMinute = 500

    override val httpExtraRetryAfterHeader = "x-ratelimit-reset-tokens"

    override fun getTranscribeUrl() = BASE_URL
    override fun getQueryUrl() = BASE_URL

    override fun addAuthHeaders(builder: okhttp3.Request.Builder) = builder
        .addHeader("Content-Type", "application/json")
        .addHeader("Authorization", "Bearer ${_apiKey!!}")

    override fun buildTranscribeRequestBody(
        chunks: List<ChunkData>,
        prompt: String,
        chunkContext: String
    ): JSONObject {
        val contentArray = JSONArray()

        contentArray.put(JSONObject().apply {
            put("type", "text")
            put("text", "$prompt\n\n$chunkContext")
        })

        chunks.forEach { chunk ->
            val base64Image = Base64.encodeToString(chunk.image, Base64.NO_WRAP)
            contentArray.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/webp;base64,$base64Image")
                    put("detail", "high")
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
            put("response_format", JSONObject().put("type", "json_object"))
        }
    }

    override fun buildTextQueryRequestBody(prompt: String, systemPrompt: String?): JSONObject {
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

        return JSONObject().apply {
            put("model", modelId)
            put("messages", messages)
            put("max_tokens", 8192)
            put("temperature", 0.3)
            put("top_p", 0.9)
        }
    }

    override fun buildVisionQueryRequestBody(
        prompt: String,
        images: List<ByteArray>,
        systemPrompt: String?
    ): JSONObject {
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

        return JSONObject().apply {
            put("model", modelId)
            put("messages", messages)
            put("max_tokens", 8192)
            put("temperature", 0.3)
        }
    }

    override fun parseTranscriptionContent(responseBody: String): String? {
        val jsonResponse = JSONObject(responseBody)
        val choices = jsonResponse.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            Log.w(tag, "No choices in response")
            return null
        }

        val finishReason = choices.getJSONObject(0).optString("finish_reason", "")
        if (finishReason == "length") {
            Log.w(tag, "Response was truncated due to max tokens")
        }

        val message = choices.getJSONObject(0).optJSONObject("message")
        return message?.optString("content", "")
    }

    override fun parseQueryContent(responseBody: String): String {
        handleErrorResponse(responseBody)

        val jsonResponse = JSONObject(responseBody)
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

    override fun handleErrorResponse(responseBody: String) {
        val jsonResponse = JSONObject(responseBody)
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
    }
}
