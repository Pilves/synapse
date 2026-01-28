package com.synapse.model

data class Chunk(
    val id: String,
    val sessionId: String,
    val index: Int,
    val filePath: String,
    val timestampSeconds: Float,
    val createdAt: Long,
    val isCorrupted: Boolean = false
)
