package com.synapse.model

data class LlmConfig(
    val transcriptionProvider: String,
    val transcriptionApiKey: String,
    val answeringProvider: String? = null,
    val answeringApiKey: String? = null
)
