package com.synapse.api

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
open class OpenAiService(
    apiKey: String? = null,
    customPrompt: String? = null,
    rateLimitingSafe: Boolean = true,
    sharedHttpClient: OkHttpClient? = null
) : BaseLlmService(apiKey, customPrompt, rateLimitingSafe, sharedHttpClient) {

    companion object {
        private const val BASE_URL = "https://api.openai.com/v1/chat/completions"
        private const val DEFAULT_MODEL = "gpt-4o-mini"
        val ERROR_MAPPINGS = ErrorFieldMappings(
            errorPath = "error",
            typePath = "type",
            messagePath = "message",
            codeField = "code",
            authErrors = setOf("invalid_api_key"),
            rateLimitErrors = setOf("rate_limit_exceeded", "tokens"),
            overloadedErrors = emptySet()
        )
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
        .addHeader("Authorization", "Bearer ${requireNotNull(_apiKey) { "API key not configured for OpenAI" }}")

    override fun buildTranscribeRequestBody(
        chunks: List<ChunkData>,
        prompt: String,
        chunkContext: String
    ): JSONObject {
        val contentArray = JSONArray()

        contentArray.put(buildTextContentPart(buildChunkContextString(prompt, chunkContext)))

        chunks.forEach { chunk ->
            contentArray.put(buildOpenAiImagePart(chunk.image, "image/webp"))
        }

        val messages = JSONArray().apply {
            put(buildMessage("system", PromptTemplate.SYSTEM_PROMPT))
            put(buildMessage("user", contentArray))
        }

        return buildChatRequestBody(modelId, messages).apply {
            put("temperature", 0.2)
            put("top_p", 0.8)
            put("response_format", JSONObject().put("type", "json_object"))
        }
    }

    override fun buildTextQueryRequestBody(prompt: String, systemPrompt: String?): JSONObject {
        val messages = JSONArray()

        if (systemPrompt != null) {
            messages.put(buildMessage("system", systemPrompt))
        }

        messages.put(buildMessage("user", prompt))

        return buildChatRequestBody(modelId, messages).apply {
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
            messages.put(buildMessage("system", systemPrompt))
        }

        val contentArray = JSONArray()
        contentArray.put(buildTextContentPart(prompt))

        images.forEach { imageBytes ->
            contentArray.put(buildOpenAiImagePart(imageBytes, "image/png"))
        }

        messages.put(buildMessage("user", contentArray))

        return buildChatRequestBody(modelId, messages).apply {
            put("temperature", 0.3)
        }
    }

    override fun parseTranscriptionContent(responseBody: String): String? {
        val jsonResponse = JSONObject(responseBody)
        val choices = jsonResponse.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw TranscriptionError.InvalidResponse("No choices in OpenAI response")
        }

        checkTruncation(choices.getJSONObject(0).optString("finish_reason", ""), "length")

        return extractChoiceMessageContent(choices)
            ?: throw TranscriptionError.InvalidResponse("Empty content in OpenAI response")
    }

    override fun parseQueryContent(responseBody: String): String {
        handleErrorResponse(responseBody)

        val jsonResponse = JSONObject(responseBody)
        val choices = jsonResponse.optJSONArray("choices")
        val content = extractChoiceMessageContent(choices) ?: ""

        if (content.isBlank()) {
            throw TranscriptionError.InvalidResponse(
                if (choices == null || choices.length() == 0)
                    "No choices in response"
                else "Empty content in response"
            )
        }
        return content.trim()
    }

    override fun handleErrorResponse(responseBody: String) {
        classifyError(responseBody, ERROR_MAPPINGS)
    }
}
