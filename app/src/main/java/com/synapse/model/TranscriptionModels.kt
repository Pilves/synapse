package com.synapse.model

data class ChunkData(
    val image: ByteArray,
    val timestampSeconds: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChunkData

        if (!image.contentEquals(other.image)) return false
        if (timestampSeconds != other.timestampSeconds) return false

        return true
    }

    override fun hashCode(): Int {
        var result = image.contentHashCode()
        result = 31 * result + timestampSeconds.hashCode()
        return result
    }
}

data class TranscriptionResult(
    val notes: List<Note>,
    val failedChunks: List<Int>
)

data class Note(
    val text: String,
    val chunksUsed: List<Int>
)

data class TranscribedNote(
    val text: String,
    val chunksUsed: List<Int>,
    val intent: DetectedIntent = DetectedIntent(type = IntentType.NOTE, confidence = 1.0f),
    val contextsUsed: List<String> = emptyList()
)

data class QuestionWithAnswer(
    val originalNote: TranscribedNote,
    val question: String,
    val answer: String
)

data class ProcessedResult(
    val notes: List<TranscribedNote>,
    val questions: List<QuestionWithAnswer> = emptyList()
)
