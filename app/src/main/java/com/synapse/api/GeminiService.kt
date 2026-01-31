package com.synapse.api

import android.util.Log
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * TranscriptionService implementation for Google Gemini API.
 *
 * Uses the gemini-2.0-flash model for vision-based handwriting transcription.
 * Supports the free tier rate limits: 15 RPM.
 */
class GeminiService(
    apiKey: String? = null,
    customPrompt: String? = null,
    sharedHttpClient: OkHttpClient? = null
) : BaseLlmService(apiKey, customPrompt, rateLimitingSafe = false, sharedHttpClient) {

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val DEFAULT_MODEL = "gemini-2.0-flash"

        /** Safety threshold applied to all categories. */
        const val DEFAULT_SAFETY_THRESHOLD = "BLOCK_MEDIUM_AND_ABOVE"
        val ERROR_MAPPINGS = ErrorFieldMappings(
            errorPath = "error",
            typePath = "status",
            messagePath = "message",
            authErrors = emptySet(),
            rateLimitErrors = emptySet(),
            overloadedErrors = emptySet()
        )
    }

    override val provider: LlmProvider = LlmProvider.GEMINI
    override val modelId: String = DEFAULT_MODEL

    override val tag = "GeminiService"
    override val timeoutSeconds = 60L
    override val maxRetries = 3
    override val initialRetryDelayMs = 1000L
    override val requestsPerMinute = 15

    override fun getTranscribeUrl() = "$BASE_URL/$modelId:generateContent"
    override fun getQueryUrl() = "$BASE_URL/$modelId:generateContent"

    override fun addAuthHeaders(builder: okhttp3.Request.Builder) = builder
        .addHeader("x-goog-api-key", requireNotNull(_apiKey) { "API key not configured for Gemini" })

    override fun buildTranscribeRequestBody(
        chunks: List<ChunkData>,
        prompt: String,
        chunkContext: String
    ): JSONObject {
        val parts = JSONArray()
        parts.put(JSONObject().put("text", buildChunkContextString(prompt, chunkContext)))

        chunks.forEach { chunk ->
            parts.put(buildGeminiImagePart(chunk.image, "image/webp"))
        }

        val contents = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("parts", parts)
        })

        return JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("topP", 0.8)
                put("maxOutputTokens", 8192)
            })
            put("safetySettings", buildSafetySettings())
        }
    }

    override fun buildTextQueryRequestBody(prompt: String, systemPrompt: String?): JSONObject {
        val fullPrompt = if (systemPrompt != null) "$systemPrompt\n\n$prompt" else prompt
        val parts = JSONArray().put(JSONObject().put("text", fullPrompt))

        val contents = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("parts", parts)
        })

        return JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("topP", 0.9)
                put("maxOutputTokens", 8192)
            })
        }
    }

    override fun buildVisionQueryRequestBody(
        prompt: String,
        images: List<ByteArray>,
        systemPrompt: String?
    ): JSONObject {
        val fullPrompt = if (systemPrompt != null) "$systemPrompt\n\n$prompt" else prompt
        val parts = JSONArray()
        parts.put(JSONObject().put("text", fullPrompt))

        images.forEach { imageBytes ->
            parts.put(buildGeminiImagePart(imageBytes, "image/png"))
        }

        val contents = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("parts", parts)
        })

        return JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("topP", 0.9)
                put("maxOutputTokens", 8192)
            })
        }
    }

    override fun parseTranscriptionContent(responseBody: String): String? {
        val jsonResponse = JSONObject(responseBody)
        val candidates = jsonResponse.optJSONArray("candidates")
        return extractCandidatePartText(candidates)
            ?: throw TranscriptionError.InvalidResponse("No candidates in Gemini response")
    }

    override fun parseQueryContent(responseBody: String): String {
        handleErrorResponse(responseBody)

        val jsonResponse = JSONObject(responseBody)
        val candidates = jsonResponse.optJSONArray("candidates")
        val content = extractCandidatePartText(candidates) ?: ""

        if (content.isBlank()) {
            throw TranscriptionError.InvalidResponse(
                if (candidates == null || candidates.length() == 0)
                    "No candidates in response"
                else "Empty content in response"
            )
        }
        return content.trim()
    }

    override fun handleErrorResponse(responseBody: String) {
        classifyError(responseBody, ERROR_MAPPINGS)
    }

    private fun buildSafetySettings(): JSONArray {
        return JSONArray().apply {
            listOf(
                "HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT"
            ).forEach { category ->
                put(JSONObject().apply {
                    put("category", category)
                    put("threshold", DEFAULT_SAFETY_THRESHOLD)
                })
            }
        }
    }
}
