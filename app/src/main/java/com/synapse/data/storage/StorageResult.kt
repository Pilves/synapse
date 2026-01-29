package com.synapse.data.storage

/**
 * Unified result type for storage operations.
 *
 * Replaces ChunkStorage.ChunkResult and SessionStorage.SessionResult
 * with a single consistent sealed class.
 */
sealed class StorageResult<out T> {
    data class Success<T>(val data: T) : StorageResult<T>()
    data class Error(
        val type: ErrorType,
        val message: String,
        val exception: Exception? = null
    ) : StorageResult<Nothing>()
}

/**
 * Types of errors that can occur during storage operations.
 */
enum class ErrorType {
    NOT_FOUND,
    WRITE_FAILED,
    READ_FAILED,
    PERMISSION_DENIED,
    INSUFFICIENT_STORAGE,
    CORRUPTED_FILE,
    VERIFICATION_FAILED,
    UNKNOWN
}
