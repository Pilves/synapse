package com.synapse.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class TestableOllamaService : OllamaService() {

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
class OllamaServiceTest {

    private lateinit var service: TestableOllamaService

    @Before
    fun setup() {
        service = TestableOllamaService()
    }

    // --- Request body ---

    @Test
    fun `buildTranscribeRequestBody uses correct model and format`() {
        val chunks = listOf(
            ChunkData(image = byteArrayOf(1, 2, 3), timestampSeconds = 0f, index = 0)
        )
        val body = service.testBuildTranscribeRequestBody(chunks, "Transcribe", "context")

        assertEquals("llava", body.getString("model"))
        assertEquals(false, body.getBoolean("stream"))
        assertNotNull(body.getJSONArray("images"))
        assertEquals(1, body.getJSONArray("images").length())
    }

    @Test
    fun `buildTranscribeRequestBody includes prompt with system prompt`() {
        val chunks = listOf(
            ChunkData(image = byteArrayOf(1), timestampSeconds = 0f, index = 0)
        )
        val body = service.testBuildTranscribeRequestBody(chunks, "prompt text", "chunk context")

        val prompt = body.getString("prompt")
        assertTrue(prompt.contains("prompt text"))
        assertTrue(prompt.contains("chunk context"))
    }

    @Test
    fun `buildTranscribeRequestBody includes options`() {
        val chunks = listOf(
            ChunkData(image = byteArrayOf(1), timestampSeconds = 0f, index = 0)
        )
        val body = service.testBuildTranscribeRequestBody(chunks, "prompt", "")

        val options = body.getJSONObject("options")
        assertNotNull(options)
        assertEquals(8192, options.getInt("num_predict"))
    }

    @Test
    fun `buildTextQueryRequestBody includes system prompt when provided`() {
        val body = service.testBuildTextQueryRequestBody("question", "system")

        val prompt = body.getString("prompt")
        assertTrue(prompt.contains("system"))
        assertTrue(prompt.contains("question"))
        assertEquals(false, body.getBoolean("stream"))
    }

    @Test
    fun `buildTextQueryRequestBody works without system prompt`() {
        val body = service.testBuildTextQueryRequestBody("question", null)

        assertEquals("question", body.getString("prompt"))
    }

    @Test
    fun `buildVisionQueryRequestBody includes images`() {
        val images = listOf(byteArrayOf(1, 2, 3))
        val body = service.testBuildVisionQueryRequestBody("Describe", images, null)

        assertEquals(1, body.getJSONArray("images").length())
    }

    // --- Response parsing ---

    @Test
    fun `parseTranscriptionContent extracts response field`() {
        val response = """
            {
                "response": "{\"notes\":[{\"text\":\"hello\",\"chunks_used\":[0]}]}",
                "done": true
            }
        """.trimIndent()

        val result = service.testParseTranscriptionContent(response)
        assertNotNull(result)
        assertTrue(result!!.contains("hello"))
    }

    @Test
    fun `parseTranscriptionContent returns null on empty response`() {
        val response = """{"response": "", "done": true}"""

        val result = service.testParseTranscriptionContent(response)
        assertNull(result)
    }

    @Test
    fun `parseQueryContent extracts response text`() {
        val response = """{"response": "Answer text", "done": true}"""

        assertEquals("Answer text", service.testParseQueryContent(response))
    }

    @Test(expected = TranscriptionError.InvalidResponse::class)
    fun `parseQueryContent throws on empty response`() {
        service.testParseQueryContent("""{"response": "", "done": true}""")
    }

    // --- Error handling ---

    @Test(expected = TranscriptionError.InvalidResponse::class)
    fun `handleErrorResponse throws on error field`() {
        service.testHandleErrorResponse("""{"error": "model not found"}""")
    }

    @Test
    fun `handleErrorResponse does nothing when no error`() {
        service.testHandleErrorResponse("""{"response": "ok", "done": true}""")
    }

    // --- Configuration ---

    @Test
    fun `provider is OLLAMA`() {
        assertEquals(LlmProvider.OLLAMA, service.provider)
    }

    @Test
    fun `modelId defaults to llava`() {
        assertEquals("llava", service.modelId)
    }

    @Test
    fun `setModel changes modelId`() {
        service.setModel("mistral")
        assertEquals("mistral", service.modelId)
    }

    @Test
    fun `setBaseUrl accepts localhost`() {
        service.setBaseUrl("http://localhost:11434")
    }

    @Test
    fun `setBaseUrl accepts 127_0_0_1`() {
        service.setBaseUrl("http://127.0.0.1:11434")
    }

    @Test
    fun `setBaseUrl accepts 192_168 addresses`() {
        service.setBaseUrl("http://192.168.1.100:11434")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setBaseUrl rejects external hosts`() {
        service.setBaseUrl("http://evil.com:11434")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setBaseUrl rejects ftp scheme`() {
        service.setBaseUrl("ftp://localhost:11434")
    }

    @Test
    fun `canMakeRequest always returns true`() {
        assertTrue(service.canMakeRequest())
    }

    @Test
    fun `getWaitTimeMs always returns 0`() {
        assertEquals(0L, service.getWaitTimeMs())
    }

    @Test
    fun `setApiKey is noop`() {
        service.setApiKey("key")
        // No crash is sufficient
    }
}
