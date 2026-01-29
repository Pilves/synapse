package com.synapse.api

import okhttp3.Response

/**
 * Shared HTTP error handler for LLM service responses.
 *
 * Translates HTTP status codes into typed [TranscriptionError] exceptions.
 * Each service can customize behaviour via the optional parameters.
 */
object HttpErrorHandler {

    /**
     * Throws the appropriate [TranscriptionError] for a non-successful HTTP response.
     *
     * @param response The OkHttp response (used for status code and headers)
     * @param responseBody The already-read response body string
     * @param retryAfterHeader Header name for Retry-After (default "Retry-After")
     * @param extraRetryAfterHeader Optional secondary header for retry timing
     *   (e.g. OpenAI's "x-ratelimit-reset-tokens")
     * @param handle529AsOverloaded If true, treats HTTP 529 as [TranscriptionError.ServiceUnavailable]
     *   (used by Claude). Otherwise falls through to the default branch.
     */
    fun handleHttpError(
        response: Response,
        responseBody: String,
        retryAfterHeader: String = "Retry-After",
        extraRetryAfterHeader: String? = null,
        handle529AsOverloaded: Boolean = false
    ): Nothing {
        val code = response.code

        when {
            code == 401 || code == 403 -> {
                throw TranscriptionError.ApiKeyInvalid(
                    "Invalid or unauthorized API key (HTTP $code): $responseBody"
                )
            }
            code == 429 -> {
                val retryAfter = response.header(retryAfterHeader)?.toIntOrNull()
                    ?: extraRetryAfterHeader?.let { response.header(it)?.toIntOrNull() }
                throw TranscriptionError.RateLimitError(retryAfter)
            }
            handle529AsOverloaded && code == 529 -> {
                throw TranscriptionError.ServiceUnavailable("API overloaded")
            }
            code in 500..599 -> {
                throw TranscriptionError.ServerError(code, responseBody)
            }
            else -> {
                throw TranscriptionError.Unknown("HTTP $code: $responseBody")
            }
        }
    }
}
