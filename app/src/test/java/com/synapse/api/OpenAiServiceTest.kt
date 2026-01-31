package com.synapse.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class TestableOpenAiService(apiKey: String? = "test-key") :
    OpenAiService(apiKey = apiKey) {

    fun testBuildTranscribeRequestBody(chunks: List<ChunkData>, prompt: String, chunkContext: String) =
        buildTranscribeRequestBody(chunks, prompt, chunkContext)

    fun testBuildTextQueryRequestBody(prompt: String, systemPrompt: String?) =
        buildTextQueryRequestBody(prompt, systemPrompt)

    fun testBuildVisionQueryRequestBody(prompt: String, images: List<ByteArray>, systemPrompt: String?) =
        buildVisionQueryRequestBody(prompt, images, systemPrompt)

    fun testParseTranscriptionContent(responseBody: String) =
        parseTranscriptionContent(responseBody)

    fun testParseQueryContent(responseBody: String) =
        parseQueryContent(responseBody)

    fun testHandleErrorResponse(responseBody: String) =
        handleErrorResponse(responseBody)
}

@RunWith(RobolectricTestRunner::class)
class OpenAiServiceTest {

    private lateinit var service: TestableOpenAiService

    @Before
    fun setup() {
        service = TestableOpenAiService()
    }

    // --- Request body ---

    @Test
    fun `buildTranscribeRequestBody uses correct model`() {
        val chunks = listOf(
            ChunkData(image = byteArrayOf(1, 2, 3), timestampSeconds = 0f, index = 0)
        )
        val body = service.testBuildTranscribeRequestBody(chunks, "Transcribe", "context")

        assertEquals("gpt-4o-mini", body.getString("model"))
    }

    @Test
    fun `buildTranscribeRequestBody includes system and user messages`() {
        val chunks = listOf(
            ChunkData(image = byteArrayOf(1), timestampSeconds = 0f, index = 0)
        )
        val body = service.testBuildTranscribeRequestBody(chunks, "prompt", "")

        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())

        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun `buildTranscribeRequestBody includes OpenAI image format`() {
        val chunks = listOf(
            ChunkData(image = byteArrayOf(1, 2, 3), timestampSeconds = 0f, index = 0)
        )
        val body = service.testBuildTranscribeRequestBody(chunks, "prompt", "")

        val userContent = body.getJSONArray("messages")
            .getJSONObject(1)
            .getJSONArray("content")

        assertTrue(userContent.length() >= 2)

        var foundImage = false
        for (i in 0 until userContent.length()) {
            if (userContent.getJSONObject(i).optString("type") == "image_url") {
                foundImage = true
                val imageUrl = userContent.getJSONObject(i).getJSONObject("image_url")
                assertTrue(imageUrl.getString("url").startsWith("data:image/webp;base64,"))
                assertEquals("high", imageUrl.getString("detail"))
            }
        }
        assertTrue(foundImage)
    }

    @Test
    fun `buildTranscribeRequestBody requests json response format`() {
        val chunks = listOf(
            ChunkData(image = byteArrayOf(1), timestampSeconds = 0f, index = 0)
        )
        val body = service.testBuildTranscribeRequestBody(chunks, "prompt", "")

        val format = body.getJSONObject("response_format")
        assertEquals("json_object", format.getString("type"))
    }

    @Test
    fun `buildTextQueryRequestBody includes system prompt when provided`() {
        val body = service.testBuildTextQueryRequestBody("question", "system")

        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
    }

    @Test
    fun `buildTextQueryRequestBody excludes system when null`() {
        val body = service.testBuildTextQueryRequestBody("question", null)

        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
    }

    // --- Response parsing ---

    @Test
    fun `parseTranscriptionContent extracts from choices`() {
        val response = """
            {
                "choices": [{
                    "message": {
                        "content": "{\"notes\":[{\"text\":\"hello\",\"chunks_used\":[0]}]}"
                    },
                    "finish_reason": "stop"
                }]
            }
        """.trimIndent()

        val result = service.testParseTranscriptionContent(response)
        assertNotNull(result)
        assertTrue(result!!.contains("hello"))
    }

    @Test(expected = TranscriptionError.InvalidResponse::class)
    fun `parseTranscriptionContent throws on empty choices`() {
        service.testParseTranscriptionContent("""{"choices": []}""")
    }

    @Test(expected = TranscriptionError.InvalidResponse::class)
    fun `parseTranscriptionContent throws on missing choices`() {
        service.testParseTranscriptionContent("""{}""")
    }

    @Test
    fun `parseQueryContent extracts text`() {
        val response = """
            {
                "choices": [{
                    "message": {"content": "The answer is 42"}
                }]
            }
        """.trimIndent()

        assertEquals("The answer is 42", service.testParseQueryContent(response))
    }

    @Test(expected = TranscriptionError.InvalidResponse::class)
    fun `parseQueryContent throws on empty content`() {
        val response = """
            {
                "choices": [{
                    "message": {"content": ""}
                }]
            }
        """.trimIndent()

        service.testParseQueryContent(response)
    }

    // --- Error classification ---

    @Test(expected = TranscriptionError.ApiKeyInvalid::class)
    fun `handleErrorResponse throws on invalid_api_key`() {
        service.testHandleErrorResponse("""
            {"error": {"type": "invalid_api_key", "message": "Bad key", "code": "invalid_api_key"}}
        """.trimIndent())
    }

    @Test(expected = TranscriptionError.RateLimitError::class)
    fun `handleErrorResponse throws on rate_limit_exceeded`() {
        service.testHandleErrorResponse("""
            {"error": {"type": "rate_limit_exceeded", "message": "Too many requests", "code": "rate_limit_exceeded"}}
        """.trimIndent())
    }

    @Test(expected = TranscriptionError.ApiKeyInvalid::class)
    fun `handleErrorResponse throws on insufficient_quota`() {
        service.testHandleErrorResponse("""
            {"error": {"type": "insufficient_quota", "message": "You exceeded your current quota", "code": "insufficient_quota"}}
        """.trimIndent())
    }

    // --- Configuration ---

    @Test
    fun `provider is OPENAI`() {
        assertEquals(LlmProvider.OPENAI, service.provider)
    }

    @Test
    fun `modelId is gpt-4o-mini`() {
        assertEquals("gpt-4o-mini", service.modelId)
    }
}
