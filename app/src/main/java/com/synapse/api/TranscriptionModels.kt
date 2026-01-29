package com.synapse.api

import com.synapse.model.DetectedIntent
import com.synapse.model.IntentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a chunk of handwritten data to be transcribed.
 *
 * @property image The image data as a byte array (WebP format)
 * @property timestampSeconds Time offset from session start in seconds
 * @property index The chunk index within the session
 */
data class ChunkData(
    val image: ByteArray,
    val timestampSeconds: Float,
    val index: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChunkData

        if (!image.contentEquals(other.image)) return false
        if (timestampSeconds != other.timestampSeconds) return false
        if (index != other.index) return false

        return true
    }

    override fun hashCode(): Int {
        var result = image.contentHashCode()
        result = 31 * result + timestampSeconds.hashCode()
        result = 31 * result + index
        return result
    }
}

/**
 * Represents the result of a transcription operation.
 *
 * @property notes List of successfully transcribed notes
 * @property failedChunks Indices of chunks that failed to transcribe
 */
data class TranscriptionResult(
    val notes: List<Note>,
    val failedChunks: List<Int>
) {
    val isFullySuccessful: Boolean
        get() = failedChunks.isEmpty()

    val isPartialSuccess: Boolean
        get() = notes.isNotEmpty() && failedChunks.isNotEmpty()

    val isEmpty: Boolean
        get() = notes.isEmpty()

    companion object {
        fun empty() = TranscriptionResult(emptyList(), emptyList())

        fun failure(failedIndices: List<Int>) = TranscriptionResult(emptyList(), failedIndices)
    }
}

/**
 * Represents a single transcribed note.
 *
 * @property text The formatted markdown content
 * @property chunksUsed Indices of chunks that were combined to form this note
 */
data class Note(
    val text: String,
    val chunksUsed: List<Int>
)

/**
 * JSON response structure from LLM transcription.
 * Used for parsing the LLM's JSON output.
 */
@Serializable
data class TranscriptionResponse(
    val notes: List<NoteResponse>
)

@Serializable
data class NoteResponse(
    val text: String,
    @SerialName("chunks_used")
    val chunksUsed: List<Int>
)

/**
 * A transcribed note with intent detection and context references.
 */
data class TranscribedNote(
    val text: String,
    val chunksUsed: List<Int>,
    val intent: DetectedIntent = DetectedIntent(type = IntentType.NOTE, confidence = 1.0f),
    val contextsUsed: List<String> = emptyList()
)

/**
 * A question extracted from a note paired with its generated answer.
 */
data class QuestionWithAnswer(
    val originalNote: TranscribedNote,
    val question: String,
    val answer: String
)

/**
 * Aggregated result of transcription processing including intent-detected questions.
 */
data class ProcessedResult(
    val notes: List<TranscribedNote>,
    val questions: List<QuestionWithAnswer> = emptyList()
)

/**
 * Error types that can occur during transcription.
 */
sealed class TranscriptionError : Exception() {
    data class NetworkError(override val message: String, override val cause: Throwable? = null) : TranscriptionError()
    data class RateLimitError(val retryAfterSeconds: Int?) : TranscriptionError()
    data class ApiKeyInvalid(override val message: String = "Invalid API key") : TranscriptionError()
    data class ApiKeyMissing(override val message: String = "API key not configured") : TranscriptionError()
    data class InvalidResponse(override val message: String, override val cause: Throwable? = null) : TranscriptionError()
    data class ServerError(val statusCode: Int, override val message: String) : TranscriptionError()
    data class ServiceUnavailable(override val message: String = "Service temporarily unavailable") : TranscriptionError()
    data class Timeout(override val message: String = "Request timed out") : TranscriptionError()
    data class Unknown(override val message: String, override val cause: Throwable? = null) : TranscriptionError()
}

/**
 * Rate limiting configuration and state.
 */
data class RateLimitConfig(
    val requestsPerMinute: Int,
    val requestsPerDay: Int
)

data class RateLimitState(
    val requestTimestamps: MutableList<Long> = mutableListOf(),
    val dailyRequestCount: Int = 0,
    val lastDayReset: Long = System.currentTimeMillis()
) {
    fun canMakeRequest(config: RateLimitConfig): Boolean {
        cleanOldTimestamps()
        val now = System.currentTimeMillis()

        // Check daily limit
        if (shouldResetDaily(now)) {
            return true
        }
        if (dailyRequestCount >= config.requestsPerDay) {
            return false
        }

        // Check per-minute limit
        val oneMinuteAgo = now - 60_000
        val recentRequests = requestTimestamps.count { it > oneMinuteAgo }
        return recentRequests < config.requestsPerMinute
    }

    fun recordRequest() {
        val now = System.currentTimeMillis()
        requestTimestamps.add(now)
        cleanOldTimestamps()
    }

    private fun cleanOldTimestamps() {
        val oneMinuteAgo = System.currentTimeMillis() - 60_000
        requestTimestamps.removeAll { it < oneMinuteAgo }
    }

    private fun shouldResetDaily(now: Long): Boolean {
        val oneDayMs = 24 * 60 * 60 * 1000
        return now - lastDayReset > oneDayMs
    }

    fun getWaitTimeMs(config: RateLimitConfig): Long {
        val now = System.currentTimeMillis()
        val oneMinuteAgo = now - 60_000
        val oldestRecentRequest = requestTimestamps.filter { it > oneMinuteAgo }.minOrNull()
        return if (oldestRecentRequest != null) {
            (oldestRecentRequest + 60_000) - now
        } else {
            0
        }
    }
}
