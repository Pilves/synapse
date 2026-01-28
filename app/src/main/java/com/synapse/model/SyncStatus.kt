package com.synapse.model

sealed class SyncStatus {
    object Idle : SyncStatus()
    object Queued : SyncStatus()
    data class InProgress(val progress: Float) : SyncStatus()
    data class PartialSuccess(val syncedCount: Int, val failedCount: Int) : SyncStatus()
    object Success : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}
