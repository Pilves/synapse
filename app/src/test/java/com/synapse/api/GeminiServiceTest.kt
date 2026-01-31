package com.synapse.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Testable subclass that exposes protected methods for unit testing.
 */
private class TestableGeminiService(apiKey: String? = "test-key") :
    GeminiService(apiKey = apiKey) {

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
class GeminiServiceTest {

    private lateinit var service: TestableGeminiService

    @Before
    fun setup() {
        service = TestableGeminiService()
    }

    // --- Request body ---

    @Test
    fun `buildTranscribeRequestBody includes image parts and prompt`() {
        val chunks = listOf(
            ChunkData(image = byteArrayOf(1, 2, 3), timestampSeconds = 0f, index = 0)
        )
        val body = service.testBuildTranscribeRequestBody(chunks, "Transcribe this", "context")

        val contents = body.getJSONArray("contents")
        assertEquals(1, contents.length())

        val parts = contents.getJSONObject(0).getJSONArray("parts")
        assertTrue(parts.length() >= 2)

        assertNotNull(parts.getJSONObject(0).optString("text"))

        val inlineData = parts.getJSONObject(1).optJSONObject("inline_data")
        assertNotNull(inlineData)
        assertEquals("image/webp", inlineData?.getString("mime_type"))
    }

    @Test
    fun `buildTranscribeRequestBody includes generation config`() {
        val chunks = listOf(
            ChunkData(image = byteArrayOf(1), timestampSeconds = 0f, index = 0)
        )
        val body = service.testBuildTranscribeRequestBody(chunks, "prompt", "")

        val config = body.getJSONObject("generationConfig")
        assertNotNull(config)
        assertEquals(8192, config.getInt("maxOutputTokens"))
    }

    @Test
    fun `buildTranscribeRequestBody includes safety settings`() {
        val chunks = listOf(
            ChunkData(image = byteArrayOf(1), timestampSeconds = 0f, index = 0)
        )
        val body = service.testBuildTranscribeRequestBody(chunks, "prompt", "")

        val safetySettings = body.getJSONArray("safetySettings")
        assertEquals(4, safetySettings.length())
    }

    @Test
    fun `buildTextQueryRequestBody includes prompt`() {
        val body = service.testBuildTextQueryRequestBody("What is this?", null)

        val contents = body.getJSONArray("contents")
        val parts = contents.getJSONObject(0).getJSONArray("parts")
        val text = parts.getJSONObject(0).getString("text")
        assertTrue(text.contains("What is this?"))
    }

    @Test
    fun `buildTextQueryRequestBody includes system prompt when provided`() {
        val body = service.testBuildTextQueryRequestBody("question", "You are a helper")

        val contents = body.getJSONArray("contents")
        val parts = contents.getJSONObject(0).getJSONArray("parts")
        val text = parts.getJSONObject(0).getString("text")
        assertTrue(text.contains("You are a helper"))
        assertTrue(text.contains("question"))
    }

    @Test
    fun `buildVisionQueryRequestBody includes images and prompt`() {
        val images = listOf(byteArrayOf(1, 2, 3))
        val body = service.testBuildVisionQueryRequestBody("Describe this", images, null)

        val contents = body.getJSONArray("contents")
        val parts = contents.getJSONObject(0).getJSONArray("parts")
        assertTrue(parts.length() >= 2)
    }

    // --- Response parsing ---

    @Test
    fun `parseTranscriptionContent extracts text from candidates`() {
        val response = """
            {
                "candidates": [{
                    "content": {
                        "parts": [{"text": "{\"notes\":[{\"text\":\"hello\",\"chunks_used\":[0]}]}"}]
                    }
                }]
            }
        """.trimIndent()

        val result = service.testParseTranscriptionContent(response)
        assertNotNull(result)
        assertTrue(result!!.contains("hello"))
    }

    @Test(expected = TranscriptionError.InvalidResponse::class)
    fun `parseTranscriptionContent throws on empty candidates`() {
        service.testParseTranscriptionContent("""{"candidates": []}""")
    }

    @Test(expected = TranscriptionError.InvalidResponse::class)
    fun `parseTranscriptionContent throws on missing candidates`() {
        service.testParseTranscriptionContent("""{}""")
    }

    @Test
    fun `parseQueryContent extracts text from candidates`() {
        val response = """
            {
                "candidates": [{
                    "content": {
                        "parts": [{"text": "Answer text"}]
                    }
                }]
            }
        """.trimIndent()

        val result = service.testParseQueryContent(response)
        assertEquals("Answer text", result)
    }

    @Test(expected = TranscriptionError.InvalidResponse::class)
    fun `parseQueryContent throws on empty content`() {
        val response = """
            {
                "candidates": [{
                    "content": {
                        "parts": [{"text": ""}]
                    }
                }]
            }
        """.trimIndent()

        service.testParseQueryContent(response)
    }

    // --- Error classification ---

    @Test(expected = TranscriptionError.ApiKeyInvalid::class)
    fun `handleErrorResponse throws ApiKeyInvalid on UNAUTHENTICATED`() {
        service.testHandleErrorResponse("""
            {"error": {"status": "UNAUTHENTICATED", "message": "API key invalid"}}
        """.trimIndent())
    }

    @Test(expected = TranscriptionError.ApiKeyInvalid::class)
    fun `handleErrorResponse throws ApiKeyInvalid on PERMISSION_DENIED`() {
        service.testHandleErrorResponse("""
            {"error": {"status": "PERMISSION_DENIED", "message": "Permission denied"}}
        """.trimIndent())
    }

    @Test(expected = TranscriptionError.RateLimitError::class)
    fun `handleErrorResponse throws RateLimitError on RESOURCE_EXHAUSTED`() {
        service.testHandleErrorResponse("""
            {"error": {"status": "RESOURCE_EXHAUSTED", "message": "Rate limit exceeded"}}
        """.trimIndent())
    }

    @Test(expected = TranscriptionError.ServiceUnavailable::class)
    fun `handleErrorResponse throws ServiceUnavailable on UNAVAILABLE`() {
        service.testHandleErrorResponse("""
            {"error": {"status": "UNAVAILABLE", "message": "Service unavailable"}}
        """.trimIndent())
    }

    @Test
    fun `handleErrorResponse does nothing when no error field`() {
        service.testHandleErrorResponse("""{"candidates": []}""")
    }

    // --- Configuration ---

    @Test
    fun `provider is GEMINI`() {
        assertEquals(LlmProvider.GEMINI, service.provider)
    }

    @Test
    fun `modelId is gemini-2-0-flash`() {
        assertEquals("gemini-2.0-flash", service.modelId)
    }

    @Test
    fun `isConfigured returns true when API key is set`() {
        assertTrue(service.isConfigured())
    }

    @Test
    fun `isConfigured returns false when API key is blank`() {
        val emptyService = TestableGeminiService(apiKey = "")
        assertTrue(!emptyService.isConfigured())
    }
}
