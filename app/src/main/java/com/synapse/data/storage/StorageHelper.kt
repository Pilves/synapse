package com.synapse.data.storage

import java.io.File

/**
 * Storage utility functions for Synapse.
 *
 * Provides helper constants and methods for file extensions,
 * formatting, and atomic file writes.
 */
class StorageHelper {

    companion object {
        /** Temp file extension */
        const val TEMP_EXTENSION = ".tmp"

        /** WebP file extension */
        const val WEBP_EXTENSION = ".webp"

        /** JSON file extension */
        const val JSON_EXTENSION = ".json"

        /** Corrupted file marker extension */
        const val CORRUPTED_EXTENSION = ".corrupted"

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
         * Atomically writes text content to a file.
         * Writes to a temporary file first, then renames to the target.
         * Falls back to copy+delete if rename fails.
         *
         * @param file The target file
         * @param content The text content to write
         */
        fun atomicWriteText(file: File, content: String) {
            val tempFile = File(file.parent, "${file.name}${TEMP_EXTENSION}")
            tempFile.writeText(content)
            if (file.exists()) {
                file.delete()
            }
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        }
    }
}
