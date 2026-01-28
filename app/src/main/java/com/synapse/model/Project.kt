package com.synapse.model

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String,
    val name: String,
    val pathUri: String,
    val defaultFile: String,
    val lastUsedFile: String? = null
)
