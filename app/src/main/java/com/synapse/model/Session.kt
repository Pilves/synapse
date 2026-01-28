package com.synapse.model

data class Session(
    val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val chunks: List<Chunk> = emptyList()
)
