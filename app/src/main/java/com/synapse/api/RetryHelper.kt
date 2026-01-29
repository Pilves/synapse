package com.synapse.api

import android.util.Log
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException

/**
 * Shared retry utility with exponential backoff for LLM API calls.
 */
object RetryHelper {

    /**
     * Executes a block with retry logic for transient errors.
     *
     * Retries on:
     * - [TranscriptionError.RateLimitError] — uses Retry-After header when available
     * - [TranscriptionError.ServerError] with status 500-599 (and 529 for Claude overload)
     * - [TranscriptionError.ServiceUnavailable] — local service not reachable
     * - [ConnectException] — connection refused (e.g. Ollama not running)
     * - [IOException] — generic network failure
     *
     * @param maxRetries Maximum number of attempts (including the first)
     * @param initialDelayMs Starting backoff delay in milliseconds
     * @param tag Log tag for warning messages
     * @param retryServerErrors Predicate for which server error status codes to retry.
     *   Defaults to 500-599. Claude passes `{ it in 500..599 || it == 529 }`.
     * @param onConnectException Optional transform for [ConnectException]. Ollama uses this
     *   to wrap with a user-friendly "Is Ollama running?" message.
     * @param block The suspending operation to execute
     */
    suspend fun <T> executeWithRetry(
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000L,
        tag: String,
        retryServerErrors: (Int) -> Boolean = { it in 500..599 },
        onConnectException: ((ConnectException) -> Exception)? = null,
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        var retryDelay = initialDelayMs

        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: TranscriptionError.RateLimitError) {
                Log.w(tag, "Rate limited on attempt ${attempt + 1}, waiting...")
                val waitTime = e.retryAfterSeconds?.times(1000L) ?: retryDelay
                delay(waitTime)
                retryDelay *= 2
                lastException = e
            } catch (e: TranscriptionError.ServiceUnavailable) {
                Log.w(tag, "Service unavailable on attempt ${attempt + 1}")
                delay(retryDelay)
                retryDelay *= 2
                lastException = e
            } catch (e: TranscriptionError.ServerError) {
                if (retryServerErrors(e.statusCode)) {
                    Log.w(tag, "Server error on attempt ${attempt + 1}: ${e.message}")
                    delay(retryDelay)
                    retryDelay *= 2
                    lastException = e
                } else {
                    throw e
                }
            } catch (e: ConnectException) {
                Log.w(tag, "Connection refused on attempt ${attempt + 1}")
                delay(retryDelay)
                retryDelay *= 2
                lastException = onConnectException?.invoke(e)
                    ?: TranscriptionError.NetworkError(e.message ?: "Connection refused", e)
            } catch (e: IOException) {
                Log.w(tag, "Network error on attempt ${attempt + 1}: ${e.message}")
                delay(retryDelay)
                retryDelay *= 2
                lastException = TranscriptionError.NetworkError(e.message ?: "Network error", e)
            }
        }

        throw lastException ?: TranscriptionError.Unknown("Max retries exceeded")
    }
}
