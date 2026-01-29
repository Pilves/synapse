package com.synapse.model

interface Destination {
    val id: String
    val displayName: String
    val iconRes: Int
    val requiresAuth: Boolean

    suspend fun configure(): DestinationConfig
    suspend fun send(content: SyncContent): SyncResult
    suspend fun testConnection(): Boolean
}

data class DestinationConfig(
    val destinationId: String,
    val settings: Map<String, String> = emptyMap()
)

data class SyncContent(
    val markdown: String,
    val plainText: String,
    val metadata: NoteMetadata,
    val contexts: List<CapturedContext> = emptyList()
)

data class NoteMetadata(
    val timestamp: Long,
    val intentType: IntentType = IntentType.NOTE,
    val sourceApp: String? = null,
    val sourceUrl: String? = null
)

sealed class SyncResult {
    object Success : SyncResult()
    data class PartialSuccess(val message: String) : SyncResult()
    data class Failure(val error: SyncError) : SyncResult()
}

enum class SyncError {
    NETWORK,
    AUTH_EXPIRED,
    RATE_LIMITED,
    PERMISSION_DENIED,
    NOT_FOUND,
    UNKNOWN
}
