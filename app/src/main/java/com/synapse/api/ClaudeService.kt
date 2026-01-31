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
open class ClaudeService(
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
        .addHeader("x-api-key", requireNotNull(_apiKey) { "API key not configured for Claude" })
        .addHeader("anthropic-version", ANTHROPIC_VERSION)

    override fun buildTranscribeRequestBody(
        chunks: List<ChunkData>,
        prompt: String,
        chunkContext: String
    ): JSONObject {
        val contentArray = JSONArray()

        chunks.forEach { chunk ->
            contentArray.put(buildClaudeImagePart(chunk.image, "image/webp"))
        }

        contentArray.put(buildTextContentPart(buildChunkContextString(prompt, chunkContext)))

        val messages = JSONArray().put(buildMessage("user", contentArray))

        return buildChatRequestBody(modelId, messages).apply {
            put("system", PromptTemplate.SYSTEM_PROMPT)
        }
    }

    override fun buildTextQueryRequestBody(prompt: String, systemPrompt: String?): JSONObject {
        val contentArray = JSONArray()
        contentArray.put(buildTextContentPart(prompt))

        val messages = JSONArray().put(buildMessage("user", contentArray))

        return buildChatRequestBody(modelId, messages).apply {
            if (systemPrompt != null) put("system", systemPrompt)
        }
    }

    override fun buildVisionQueryRequestBody(
        prompt: String,
        images: List<ByteArray>,
        systemPrompt: String?
    ): JSONObject {
        val contentArray = JSONArray()

        images.forEach { imageBytes ->
            contentArray.put(buildClaudeImagePart(imageBytes, "image/png"))
        }

        contentArray.put(buildTextContentPart(prompt))

        val messages = JSONArray().put(buildMessage("user", contentArray))

        return buildChatRequestBody(modelId, messages).apply {
            if (systemPrompt != null) put("system", systemPrompt)
        }
    }

    override fun parseTranscriptionContent(responseBody: String): String? {
        val jsonResponse = JSONObject(responseBody)

        checkTruncation(jsonResponse.optString("stop_reason", ""), "max_tokens")

        val content = jsonResponse.optJSONArray("content")
        return extractFirstTextBlock(content)
            ?: throw TranscriptionError.InvalidResponse("No content in Claude response")
    }

    override fun parseQueryContent(responseBody: String): String {
        handleErrorResponse(responseBody)

        val jsonResponse = JSONObject(responseBody)
        val content = jsonResponse.optJSONArray("content")
        val text = extractFirstTextBlock(content)

        if (text.isNullOrBlank()) {
            throw TranscriptionError.InvalidResponse(
                if (content == null || content.length() == 0)
                    "No content in response"
                else "Empty text content in response"
            )
        }
        return text.trim()
    }

    override fun handleErrorResponse(responseBody: String) {
        classifyError(responseBody, ERROR_MAPPINGS)
    }
}
