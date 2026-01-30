package com.synapse.model

enum class QueueStatus {
    PENDING,
    QUEUED,
    SYNCING,
    SUCCESS,
    FAILED
}

data class QueuedSync(
    val sessionId: String,
    val destinations: List<String>,
    val status: QueueStatus,
    val attempts: Int = 0,
    val queuedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)
