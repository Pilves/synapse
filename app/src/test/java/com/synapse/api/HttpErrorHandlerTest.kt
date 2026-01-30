package com.synapse.api

import io.mockk.every
import io.mockk.mockk
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpErrorHandlerTest {

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
            HttpErrorHandler.handleHttpError(response, "Unauthorized")
        } catch (e: TranscriptionError.ApiKeyInvalid) {
            assertTrue(e.message!!.contains("401"))
            return
        }
        throw AssertionError("Expected ApiKeyInvalid")
    }

    @Test
    fun `403 throws ApiKeyInvalid`() {
        val response = mockResponse(403)
        try {
            HttpErrorHandler.handleHttpError(response, "Forbidden")
        } catch (e: TranscriptionError.ApiKeyInvalid) {
            assertTrue(e.message!!.contains("403"))
            return
        }
        throw AssertionError("Expected ApiKeyInvalid")
    }

    @Test
    fun `429 throws RateLimitError`() {
        val response = mockResponse(429)
        try {
            HttpErrorHandler.handleHttpError(response, "Too Many Requests")
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
            HttpErrorHandler.handleHttpError(response, "Too Many Requests")
        } catch (e: TranscriptionError.RateLimitError) {
            assertEquals(30, e.retryAfterSeconds)
            return
        }
        throw AssertionError("Expected RateLimitError")
    }

    @Test
    fun `429 uses extra retry header when primary missing`() {
        val response = mockResponse(429, mapOf("x-ratelimit-reset-tokens" to "15"))
        try {
            HttpErrorHandler.handleHttpError(
                response, "Rate limited",
                extraRetryAfterHeader = "x-ratelimit-reset-tokens"
            )
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
            HttpErrorHandler.handleHttpError(response, "Internal Server Error")
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
            HttpErrorHandler.handleHttpError(response, "Bad Gateway")
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
            HttpErrorHandler.handleHttpError(response, "Service Unavailable")
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
            HttpErrorHandler.handleHttpError(
                response, "Overloaded",
                handle529AsOverloaded = true
            )
        } catch (e: TranscriptionError.ServiceUnavailable) {
            assertTrue(e.message!!.contains("overloaded"))
            return
        }
        throw AssertionError("Expected ServiceUnavailable")
    }

    @Test
    fun `529 throws ServerError when handle529AsOverloaded is false`() {
        val response = mockResponse(529)
        try {
            HttpErrorHandler.handleHttpError(
                response, "Overloaded",
                handle529AsOverloaded = false
            )
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
            HttpErrorHandler.handleHttpError(response, "Bad Request")
        } catch (e: TranscriptionError.Unknown) {
            assertTrue(e.message!!.contains("400"))
            return
        }
        throw AssertionError("Expected Unknown")
    }

    @Test
    fun `404 throws Unknown`() {
        val response = mockResponse(404)
        try {
            HttpErrorHandler.handleHttpError(response, "Not Found")
        } catch (e: TranscriptionError.Unknown) {
            assertTrue(e.message!!.contains("404"))
            return
        }
        throw AssertionError("Expected Unknown")
    }

    @Test
    fun `response body is included in error messages`() {
        val response = mockResponse(400)
        try {
            HttpErrorHandler.handleHttpError(response, "custom error body")
        } catch (e: TranscriptionError.Unknown) {
            assertTrue(e.message!!.contains("custom error body"))
            return
        }
        throw AssertionError("Expected Unknown with body")
    }
}
