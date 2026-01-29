package com.synapse.model

/**
 * Types of intents that can be detected from handwritten notes.
 */
enum class IntentType {
    NOTE,
    TASK,
    QUESTION,
    REMINDER,
    REACTION
}

/**
 * Represents a detected intent from the LLM's analysis of a handwritten note.
 *
 * @property type The classified intent type
 * @property confidence Confidence score from 0.0 to 1.0
 * @property needsConfirmation Whether the user should confirm this classification
 * @property data Optional structured data extracted based on intent type
 */
data class DetectedIntent(
    val type: IntentType,
    val confidence: Float = 1.0f,
    val needsConfirmation: Boolean = false,
    val data: IntentData? = null
)

/**
 * Structured data extracted based on the detected intent type.
 */
sealed class IntentData {
    /**
     * Data for TASK intents - contains an optional deadline.
     */
    data class TaskData(
        val deadline: String? = null
    ) : IntentData()

    /**
     * Data for QUESTION intents - contains the question text.
     */
    data class QuestionData(
        val question: String
    ) : IntentData()

    /**
     * Data for REMINDER intents - contains time reference and optional parsed timestamp.
     */
    data class ReminderData(
        val time: String? = null,
        val parsedTime: Long? = null
    ) : IntentData()
}

/**
 * A transcribed note with intent detection information.
 *
 * @property text The formatted markdown content
 * @property chunksUsed Indices of chunks that were combined to form this note
 * @property intent The detected intent, if any
 * @property contextsUsed IDs of contexts that were referenced in this note
 */
data class TranscribedNote(
    val text: String,
    val chunksUsed: List<Int> = emptyList(),
    val intent: DetectedIntent? = null,
    val contextsUsed: List<String> = emptyList()
)

/**
 * Represents captured context that can be provided alongside handwritten notes
 * for richer transcription and intent detection.
 */
sealed class CapturedContext {
    abstract val id: String

    data class SelectedText(
        override val id: String,
        val text: String,
        val sourceApp: String? = null,
        val sourceUrl: String? = null
    ) : CapturedContext()

    data class RegionText(
        override val id: String,
        val text: String
    ) : CapturedContext()

    data class AutoContext(
        override val id: String,
        val sourceApp: String,
        val sourceUrl: String? = null
    ) : CapturedContext()

    data class RegionImage(
        override val id: String,
        val imageData: ByteArray,
        val description: String? = null
    ) : CapturedContext() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as RegionImage
            if (id != other.id) return false
            if (!imageData.contentEquals(other.imageData)) return false
            if (description != other.description) return false
            return true
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + imageData.contentHashCode()
            result = 31 * result + (description?.hashCode() ?: 0)
            return result
        }
    }
}
