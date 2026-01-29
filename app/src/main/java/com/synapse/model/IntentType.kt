package com.synapse.model

enum class IntentType {
    NOTE,
    TASK,
    QUESTION,
    REMINDER,
    REACTION
}

data class DetectedIntent(
    val type: IntentType,
    val confidence: Float,
    val needsConfirmation: Boolean = false,
    val extractedData: IntentData? = null
)

sealed class IntentData {
    data class TaskData(
        val taskText: String,
        val deadline: String? = null
    ) : IntentData()

    data class QuestionData(
        val question: String,
        val answer: String? = null
    ) : IntentData()

    data class ReminderData(
        val reminderText: String,
        val time: String? = null,
        val parsedTime: Long? = null
    ) : IntentData()
}
