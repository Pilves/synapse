package com.synapse.api

import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpErrorHandlerTest {

    /**
     * Minimal concrete subclass of BaseLlmService that exposes
     * handleNonSuccessResponse for testing HTTP error classification.
     */
    private class TestLlmService : BaseLlmService(apiKey = "test-key") {
        override val tag = "TestLlm"
        override val timeoutSeconds = 10L
        override val maxRetries = 1
        override val initialRetryDelayMs = 100L
        override val requestsPerMinute = 100
        override val provider = LlmProvider.GEMINI
        override val modelId = "test-model"

        override fun getTranscribeUrl() = "http://localhost/transcribe"
        override fun getQueryUrl() = "http://localhost/query"
        override fun addAuthHeaders(builder: Request.Builder) = builder
        override fun buildTranscribeRequestBody(chunks: List<ChunkData>, prompt: String, chunkContext: String) = JSONObject()
        override fun buildTextQueryRequestBody(prompt: String, systemPrompt: String?) = JSONObject()
        override fun buildVisionQueryRequestBody(prompt: String, images: List<ByteArray>, systemPrompt: String?) = JSONObject()
        override fun parseTranscriptionContent(responseBody: String): String? = null
        override fun parseQueryContent(responseBody: String) = ""
        override fun handleErrorResponse(responseBody: String) {}

        /** Expose handleNonSuccessResponse for testing. */
        fun testHandleHttpError(
            code: Int,
            responseBody: String,
            response: Response,
            retryAfterHeader: String = "Retry-After",
            extraRetryAfterHeader: String? = null,
            handle529AsOverloaded: Boolean = false
        ) {
            // Temporarily override the http error handler params
            handleNonSuccessResponse(code, responseBody, response)
        }

        fun testHandleHttpErrorWith529(
            code: Int,
            responseBody: String,
            response: Response
        ) {
            // We need to test with handle529AsOverloaded = true
            // Override the property for this test
            _handle529 = true
            handleNonSuccessResponse(code, responseBody, response)
        }

        private var _handle529 = false
        override val httpHandle529AsOverloaded: Boolean get() = _handle529
        override val httpExtraRetryAfterHeader: String? get() = _extraHeader
        private var _extraHeader: String? = null

        fun setExtraRetryAfterHeader(header: String?) { _extraHeader = header }
    }

    private val service = TestLlmService()

    private fun mockResponse(code: Int, headers: Map<String, String> = emptyMap()): Response {
        val response = mockk<Response>(relaxed = true)
        every { response.code } returns code
        headers.forEach { (key, value) ->
            every { response.header(key) } returns value
        }
        // Return null for any header not in the map
        every { response.header(match { it !in headers.keys }) } returns null
        return response
    }

    @Test
    fun `401 throws ApiKeyInvalid`() {
        val response = mockResponse(401)
        try {
            service.testHandleHttpError(401, "Unauthorized", response)
        } catch (e: TranscriptionError.ApiKeyInvalid) {
            assertTrue(e.message.contains("401"))
            return
        }
        throw AssertionError("Expected ApiKeyInvalid")
    }

    @Test
    fun `403 throws ApiKeyInvalid`() {
        val response = mockResponse(403)
        try {
            service.testHandleHttpError(403, "Forbidden", response)
        } catch (e: TranscriptionError.ApiKeyInvalid) {
            assertTrue(e.message.contains("403"))
            return
        }
        throw AssertionError("Expected ApiKeyInvalid")
    }

    @Test
    fun `429 throws RateLimitError`() {
        val response = mockResponse(429)
        try {
            service.testHandleHttpError(429, "Too Many Requests", response)
        } catch (e: TranscriptionError.RateLimitError) {
            assertEquals(null, e.retryAfterSeconds)
            return
        }
        throw AssertionError("Expected RateLimitError")
    }

    @Test
    fun `429 parses Retry-After header`() {
        val response = mockResponse(429, mapOf("Retry-After" to "30"))
        try {
            service.testHandleHttpError(429, "Too Many Requests", response)
        } catch (e: TranscriptionError.RateLimitError) {
            assertEquals(30, e.retryAfterSeconds)
            return
        }
        throw AssertionError("Expected RateLimitError")
    }

    @Test
    fun `429 uses extra retry header when primary missing`() {
        val response = mockResponse(429, mapOf("x-ratelimit-reset-tokens" to "15"))
        service.setExtraRetryAfterHeader("x-ratelimit-reset-tokens")
        try {
            service.testHandleHttpError(429, "Rate limited", response)
        } catch (e: TranscriptionError.RateLimitError) {
            assertEquals(15, e.retryAfterSeconds)
            return
        }
        throw AssertionError("Expected RateLimitError")
    }

    @Test
    fun `500 throws ServerError`() {
        val response = mockResponse(500)
        try {
            service.testHandleHttpError(500, "Internal Server Error", response)
        } catch (e: TranscriptionError.ServerError) {
            assertEquals(500, e.statusCode)
            assertEquals("Internal Server Error", e.message)
            return
        }
        throw AssertionError("Expected ServerError")
    }

    @Test
    fun `502 throws ServerError`() {
        val response = mockResponse(502)
        try {
            service.testHandleHttpError(502, "Bad Gateway", response)
        } catch (e: TranscriptionError.ServerError) {
            assertEquals(502, e.statusCode)
            return
        }
        throw AssertionError("Expected ServerError")
    }

    @Test
    fun `503 throws ServerError`() {
        val response = mockResponse(503)
        try {
            service.testHandleHttpError(503, "Service Unavailable", response)
        } catch (e: TranscriptionError.ServerError) {
            assertEquals(503, e.statusCode)
            return
        }
        throw AssertionError("Expected ServerError")
    }

    @Test
    fun `529 throws ServiceUnavailable when handle529AsOverloaded is true`() {
        val response = mockResponse(529)
        try {
            service.testHandleHttpErrorWith529(529, "Overloaded", response)
        } catch (e: TranscriptionError.ServiceUnavailable) {
            assertTrue(e.message.contains("overloaded"))
            return
        }
        throw AssertionError("Expected ServiceUnavailable")
    }

    @Test
    fun `529 throws ServerError when handle529AsOverloaded is false`() {
        val response = mockResponse(529)
        try {
            service.testHandleHttpError(529, "Overloaded", response)
        } catch (e: TranscriptionError.ServerError) {
            assertEquals(529, e.statusCode)
            return
        }
        throw AssertionError("Expected ServerError")
    }

    @Test
    fun `400 throws Unknown`() {
        val response = mockResponse(400)
        try {
            service.testHandleHttpError(400, "Bad Request", response)
        } catch (e: TranscriptionError.Unknown) {
            assertTrue(e.message.contains("400"))
            return
        }
        throw AssertionError("Expected Unknown")
    }

    @Test
    fun `404 throws Unknown`() {
        val response = mockResponse(404)
        try {
            service.testHandleHttpError(404, "Not Found", response)
        } catch (e: TranscriptionError.Unknown) {
            assertTrue(e.message.contains("404"))
            return
        }
        throw AssertionError("Expected Unknown")
    }

    @Test
    fun `response body is included in error messages`() {
        val response = mockResponse(400)
        try {
            service.testHandleHttpError(400, "custom error body", response)
        } catch (e: TranscriptionError.Unknown) {
            assertTrue(e.message.contains("custom error body"))
            return
        }
        throw AssertionError("Expected Unknown with body")
    }
}
