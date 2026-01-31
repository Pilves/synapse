package com.synapse.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class TestableClaudeService(apiKey: String? = "test-key") :
    ClaudeService(apiKey = apiKey) {

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
class ClaudeServiceTest {

    private lateinit var service: TestableClaudeService

    @Before
    fun setup() {
        service = TestableClaudeService()
    }

    // --- Request body ---

    @Test
    fun `buildTranscribeRequestBody includes image and text content`() {
        val chunks = listOf(
            ChunkData(image = byteArrayOf(1, 2, 3), timestampSeconds = 0f, index = 0)
        )
        val body = service.testBuildTranscribeRequestBody(chunks, "Transcribe this", "context")

        assertEquals("claude-3-5-haiku-20241022", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())

        val content = messages.getJSONObject(0).getJSONArray("content")
        assertTrue(content.length() >= 2)

        val imagePart = content.getJSONObject(0)
        assertEquals("image", imagePart.getString("type"))
        assertNotNull(imagePart.getJSONObject("source"))

        val textPart = content.getJSONObject(content.length() - 1)
        assertEquals("text", textPart.getString("type"))
    }

    @Test
    fun `buildTranscribeRequestBody includes system prompt`() {
        val chunks = listOf(
            ChunkData(image = byteArrayOf(1), timestampSeconds = 0f, index = 0)
        )
        val body = service.testBuildTranscribeRequestBody(chunks, "prompt", "")

        assertTrue(body.has("system"))
    }

    @Test
    fun `buildTranscribeRequestBody includes max_tokens`() {
        val chunks = listOf(
            ChunkData(image = byteArrayOf(1), timestampSeconds = 0f, index = 0)
        )
        val body = service.testBuildTranscribeRequestBody(chunks, "prompt", "")

        assertEquals(8192, body.getInt("max_tokens"))
    }

    @Test
    fun `buildTextQueryRequestBody uses correct model and format`() {
        val body = service.testBuildTextQueryRequestBody("test question", "system prompt")

        assertEquals("claude-3-5-haiku-20241022", body.getString("model"))
        assertEquals("system prompt", body.getString("system"))

        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())

        val content = messages.getJSONObject(0).getJSONArray("content")
        val textPart = content.getJSONObject(0)
        assertEquals("text", textPart.getString("type"))
        assertEquals("test question", textPart.getString("text"))
    }

    @Test
    fun `buildTextQueryRequestBody omits system when null`() {
        val body = service.testBuildTextQueryRequestBody("question", null)

        assertTrue(!body.has("system"))
    }

    @Test
    fun `buildVisionQueryRequestBody includes images`() {
        val images = listOf(byteArrayOf(1, 2, 3))
        val body = service.testBuildVisionQueryRequestBody("Describe", images, null)

        val messages = body.getJSONArray("messages")
        val content = messages.getJSONObject(0).getJSONArray("content")
        assertTrue(content.length() >= 2)
    }

    // --- Response parsing ---

    @Test
    fun `parseTranscriptionContent extracts text from content array`() {
        val response = """
            {
                "content": [
                    {"type": "text", "text": "{\"notes\":[{\"text\":\"test\",\"chunks_used\":[0]}]}"}
                ],
                "stop_reason": "end_turn"
            }
        """.trimIndent()

        val result = service.testParseTranscriptionContent(response)
        assertNotNull(result)
        assertTrue(result!!.contains("test"))
    }

    @Test(expected = TranscriptionError.InvalidResponse::class)
    fun `parseTranscriptionContent throws on empty content`() {
        service.testParseTranscriptionContent("""{"content": []}""")
    }

    @Test
    fun `parseQueryContent extracts text`() {
        val response = """
            {
                "content": [
                    {"type": "text", "text": "Answer here"}
                ]
            }
        """.trimIndent()

        val result = service.testParseQueryContent(response)
        assertEquals("Answer here", result)
    }

    @Test(expected = TranscriptionError.InvalidResponse::class)
    fun `parseQueryContent throws on empty text`() {
        val response = """
            {
                "content": [
                    {"type": "text", "text": ""}
                ]
            }
        """.trimIndent()

        service.testParseQueryContent(response)
    }

    // --- Error classification ---

    @Test(expected = TranscriptionError.ApiKeyInvalid::class)
    fun `handleErrorResponse throws on authentication_error`() {
        service.testHandleErrorResponse("""
            {"error": {"type": "authentication_error", "message": "Invalid API key"}}
        """.trimIndent())
    }

    @Test(expected = TranscriptionError.RateLimitError::class)
    fun `handleErrorResponse throws on rate_limit_error`() {
        service.testHandleErrorResponse("""
            {"error": {"type": "rate_limit_error", "message": "Rate limit exceeded"}}
        """.trimIndent())
    }

    @Test(expected = TranscriptionError.ServiceUnavailable::class)
    fun `handleErrorResponse throws on overloaded_error`() {
        service.testHandleErrorResponse("""
            {"error": {"type": "overloaded_error", "message": "API is overloaded"}}
        """.trimIndent())
    }

    @Test
    fun `handleErrorResponse does nothing when no error`() {
        service.testHandleErrorResponse("""{"content": []}""")
    }

    // --- Configuration ---

    @Test
    fun `provider is CLAUDE`() {
        assertEquals(LlmProvider.CLAUDE, service.provider)
    }

    @Test
    fun `modelId is claude-3-5-haiku`() {
        assertEquals("claude-3-5-haiku-20241022", service.modelId)
    }

    @Test
    fun `isConfigured returns true with key`() {
        assertTrue(service.isConfigured())
    }

    @Test
    fun `isConfigured returns false without key`() {
        val emptyService = TestableClaudeService(apiKey = "")
        assertTrue(!emptyService.isConfigured())
    }
}
