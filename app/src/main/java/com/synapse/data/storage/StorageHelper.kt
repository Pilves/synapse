package com.synapse.data.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Storage utility functions for Synapse.
 *
 * Provides helper methods for checking storage availability,
 * managing cache directories, and cleaning up temporary files.
 */
class StorageHelper(private val context: Context) {

    companion object {
        /** Minimum required free storage in bytes (50MB) */
        const val MIN_FREE_STORAGE_BYTES = 50L * 1024L * 1024L

        /** Temp file extension */
        const val TEMP_EXTENSION = ".tmp"

        /** WebP file extension */
        const val WEBP_EXTENSION = ".webp"

        /** JSON file extension */
        const val JSON_EXTENSION = ".json"

        /** Corrupted file marker extension */
        const val CORRUPTED_EXTENSION = ".corrupted"

        /** Max age for temp files before cleanup (1 hour in milliseconds) */
        private const val TEMP_FILE_MAX_AGE_MS = 60 * 60 * 1000L
    }

    /**
     * Result of a storage space check.
     */
    sealed class StorageCheckResult {
        /** Sufficient storage available */
        data class Available(val freeBytes: Long) : StorageCheckResult()

        /** Insufficient storage */
        data class Insufficient(val freeBytes: Long, val requiredBytes: Long) : StorageCheckResult()

        /** Unable to check storage */
        data class Error(val message: String) : StorageCheckResult()
    }

    /**
     * Get the cache directory for chunk storage.
     * Creates the directory if it doesn't exist.
     *
     * @return The chunks cache directory, or null if creation failed
     */
    fun getChunkCacheDirectory(): File? {
        val cacheDir = File(context.cacheDir, "chunks")
        return if (cacheDir.exists() || cacheDir.mkdirs()) {
            cacheDir
        } else {
            null
        }
    }

    /**
     * Get the sessions metadata directory.
     * Creates the directory if it doesn't exist.
     *
     * @return The sessions directory, or null if creation failed
     */
    fun getSessionsDirectory(): File? {
        val sessionsDir = File(context.cacheDir, "sessions")
        return if (sessionsDir.exists() || sessionsDir.mkdirs()) {
            sessionsDir
        } else {
            null
        }
    }

    /**
     * Check available storage space.
     *
     * @param requiredBytes The minimum required free space (defaults to MIN_FREE_STORAGE_BYTES)
     * @return StorageCheckResult indicating storage availability
     */
    suspend fun checkAvailableStorage(
        requiredBytes: Long = MIN_FREE_STORAGE_BYTES
    ): StorageCheckResult = withContext(Dispatchers.IO) {
        try {
            val cacheDir = context.cacheDir
            if (!cacheDir.exists()) {
                return@withContext StorageCheckResult.Error("Cache directory does not exist")
            }

            val stat = StatFs(cacheDir.path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong

            if (availableBytes >= requiredBytes) {
                StorageCheckResult.Available(availableBytes)
            } else {
                StorageCheckResult.Insufficient(availableBytes, requiredBytes)
            }
        } catch (e: Exception) {
            StorageCheckResult.Error("Failed to check storage: ${e.message}")
        }
    }

    /**
     * Get the total storage used by the app's cache (chunks and sessions).
     *
     * @return Total bytes used, or -1 if calculation failed
     */
    suspend fun calculateTotalStorageUsed(): Long = withContext(Dispatchers.IO) {
        try {
            var totalSize = 0L

            getChunkCacheDirectory()?.let { chunkDir ->
                totalSize += calculateDirectorySize(chunkDir)
            }

            getSessionsDirectory()?.let { sessionsDir ->
                totalSize += calculateDirectorySize(sessionsDir)
            }

            totalSize
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Calculate the size of a directory recursively.
     *
     * @param directory The directory to calculate size for
     * @return Total size in bytes
     */
    private fun calculateDirectorySize(directory: File): Long {
        if (!directory.exists()) return 0L

        var size = 0L
        directory.listFiles()?.forEach { file ->
            size += if (file.isDirectory) {
                calculateDirectorySize(file)
            } else {
                file.length()
            }
        }
        return size
    }

    /**
     * Clean up old temporary files.
     * Removes .tmp files older than TEMP_FILE_MAX_AGE_MS.
     *
     * @return Number of files cleaned up
     */
    suspend fun cleanupOldTempFiles(): Int = withContext(Dispatchers.IO) {
        var cleanedCount = 0
        val currentTime = System.currentTimeMillis()

        try {
            getChunkCacheDirectory()?.let { chunkDir ->
                cleanedCount += cleanupTempFilesInDirectory(chunkDir, currentTime)
            }

            getSessionsDirectory()?.let { sessionsDir ->
                cleanedCount += cleanupTempFilesInDirectory(sessionsDir, currentTime)
            }
        } catch (e: Exception) {
            // Log error but don't fail
        }

        cleanedCount
    }

    /**
     * Clean up temp files in a specific directory.
     */
    private fun cleanupTempFilesInDirectory(directory: File, currentTime: Long): Int {
        var count = 0
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(TEMP_EXTENSION)) {
                val fileAge = currentTime - file.lastModified()
                if (fileAge > TEMP_FILE_MAX_AGE_MS) {
                    if (file.delete()) {
                        count++
                    }
                }
            }
        }
        return count
    }

    /**
     * Clean up all orphaned temp files (regardless of age).
     * Use this for immediate cleanup.
     *
     * @return Number of files cleaned up
     */
    suspend fun cleanupAllTempFiles(): Int = withContext(Dispatchers.IO) {
        var cleanedCount = 0

        try {
            getChunkCacheDirectory()?.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(TEMP_EXTENSION)) {
                    if (file.delete()) {
                        cleanedCount++
                    }
                }
            }

            getSessionsDirectory()?.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(TEMP_EXTENSION)) {
                    if (file.delete()) {
                        cleanedCount++
                    }
                }
            }
        } catch (e: Exception) {
            // Log error but don't fail
        }

        cleanedCount
    }

    /**
     * Get free storage space in bytes.
     *
     * @return Free space in bytes, or -1 if unavailable
     */
    suspend fun getFreeStorageBytes(): Long = withContext(Dispatchers.IO) {
        try {
            val stat = StatFs(context.cacheDir.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Get total storage space in bytes.
     *
     * @return Total space in bytes, or -1 if unavailable
     */
    suspend fun getTotalStorageBytes(): Long = withContext(Dispatchers.IO) {
        try {
            val stat = StatFs(context.cacheDir.path)
            stat.blockCountLong * stat.blockSizeLong
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Check if a file path is within the allowed cache directories.
     *
     * @param filePath The path to check
     * @return true if the path is within cache directories
     */
    fun isPathWithinCache(filePath: String): Boolean {
        val file = File(filePath)
        val canonicalPath = try {
            file.canonicalPath
        } catch (e: IOException) {
            return false
        }

        val cachePath = context.cacheDir.canonicalPath
        return canonicalPath.startsWith(cachePath)
    }

    /**
     * Format bytes as human-readable string.
     *
     * @param bytes Number of bytes
     * @return Formatted string (e.g., "1.5 MB")
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * Generate a unique temp file path for atomic writes.
     *
     * @param directory The directory for the temp file
     * @param prefix Optional prefix for the temp filename
     * @return A unique temp file path
     */
    fun generateTempFilePath(directory: File, prefix: String = "temp"): File {
        val timestamp = System.currentTimeMillis()
        val random = (Math.random() * 10000).toInt()
        return File(directory, "${prefix}_${timestamp}_${random}$TEMP_EXTENSION")
    }

    /**
     * Safely delete a file, returning whether deletion was successful.
     *
     * @param file The file to delete
     * @return true if the file was deleted or didn't exist
     */
    suspend fun safeDelete(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            !file.exists() || file.delete()
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Safely delete a file by path.
     *
     * @param filePath The path to the file to delete
     * @return true if the file was deleted or didn't exist
     */
    suspend fun safeDelete(filePath: String): Boolean = safeDelete(File(filePath))
}
