package com.synapse.model

data class SessionSyncConfig(
    val sessionId: String,
    val destinations: List<String> = listOf("clipboard"),
    val contexts: List<CapturedContext> = emptyList()
)
