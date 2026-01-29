package com.synapse.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.synapse.model.Chunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles chunk file operations for Synapse.
 *
 * Chunks are stored in the app's cache directory with atomic writes:
 * - Write to .tmp file first
 * - Verify the written file
 * - Rename to .webp
 *
 * Filename format: session_{timestamp}_chunk_{index}.webp
 *
 * Features:
 * - Pre-flight storage check (>50MB free)
 * - Atomic writes with verification
 * - Corruption detection and marking
 * - Session-based chunk organization
 */
class ChunkStorage(private val context: Context) {

    companion object {
        private const val TAG = "ChunkStorage"

        /** Chunks cache directory name */
        private const val CHUNKS_DIR = "chunks"

        /** Chunk filename prefix */
        private const val CHUNK_PREFIX = "session_"

        /** Chunk filename middle segment */
        private const val CHUNK_MIDDLE = "_chunk_"

        /** Thumbnail suffix */
        private const val THUMB_SUFFIX = "_thumb"

        /** Thumbnail size in pixels */
        private const val THUMBNAIL_SIZE = 150

        /** WebP quality for full images (85%) */
        private const val IMAGE_QUALITY = 85

        /** WebP quality for thumbnails */
        private const val THUMBNAIL_QUALITY = 70

        /** Minimum required free storage in bytes (50MB) */
        private const val MIN_FREE_STORAGE_BYTES = 50L * 1024L * 1024L

        /** Minimum valid image size in bytes */
        private const val MIN_VALID_IMAGE_SIZE = 100L
    }

    /** Mutex for ensuring atomic operations per session */
    private val sessionMutexes = ConcurrentHashMap<String, Mutex>()

    private val chunksDir: File
        get() = File(context.cacheDir, CHUNKS_DIR).also { it.mkdirs() }

    /**
     * Get or create a mutex for a specific session.
     */
    private fun getSessionMutex(sessionId: String): Mutex {
        return sessionMutexes.getOrPut(sessionId) { Mutex() }
    }

    /**
     * Removes the session mutex from the map if it is not currently locked.
     * Call this after a session is fully processed to prevent unbounded map growth.
     *
     * @param sessionId The session ID whose mutex to clean up
     */
    fun cleanupSessionMutex(sessionId: String) {
        sessionMutexes.compute(sessionId) { _, mutex ->
            if (mutex != null && !mutex.isLocked) null else mutex
        }
    }

    /**
     * Generate the filename for a chunk.
     *
     * @param sessionTimestamp The session timestamp
     * @param index The chunk index
     * @return Filename in format: session_{timestamp}_chunk_{index}.webp
     */
    fun generateChunkFilename(sessionTimestamp: String, index: Int): String {
        return "$CHUNK_PREFIX${sessionTimestamp}$CHUNK_MIDDLE${index}$StorageHelper.WEBP_EXTENSION"
    }

    /**
     * Parse a chunk filename to extract session timestamp and index.
     *
     * @param filename The chunk filename
     * @return Pair of (sessionTimestamp, index), or null if parsing fails
     */
    fun parseChunkFilename(filename: String): Pair<String, Int>? {
        if (!filename.startsWith(CHUNK_PREFIX) || !filename.endsWith(StorageHelper.WEBP_EXTENSION)) {
            return null
        }

        // Handle thumb files
        if (filename.contains(THUMB_SUFFIX)) {
            return null
        }

        val withoutExtension = filename.removeSuffix(StorageHelper.WEBP_EXTENSION)
        val chunkIndex = withoutExtension.lastIndexOf(CHUNK_MIDDLE)

        if (chunkIndex == -1) return null

        val sessionTimestamp = withoutExtension.substring(CHUNK_PREFIX.length, chunkIndex)
        val indexStr = withoutExtension.substring(chunkIndex + CHUNK_MIDDLE.length)

        return try {
            sessionTimestamp to indexStr.toInt()
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Check if there is sufficient storage for saving a new chunk.
     *
     * @return StorageResult.Success(true) if storage is available, Error otherwise
     */
    suspend fun checkStorageAvailable(): StorageResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val stat = android.os.StatFs(context.cacheDir.path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong

            if (availableBytes >= MIN_FREE_STORAGE_BYTES) {
                StorageResult.Success(true)
            } else {
                StorageResult.Error(
                    ErrorType.INSUFFICIENT_STORAGE,
                    "Insufficient storage: ${StorageHelper.formatBytes(availableBytes)} free, " +
                            "${StorageHelper.formatBytes(MIN_FREE_STORAGE_BYTES)} required"
                )
            }
        } catch (e: Exception) {
            StorageResult.Error(
                ErrorType.UNKNOWN,
                "Failed to check storage: ${e.message}",
                e
            )
        }
    }

    /**
     * Saves a bitmap as a chunk image with atomic write.
     *
     * Performs the following:
     * 1. Pre-flight storage check (>50MB free)
     * 2. Write to .tmp file
     * 3. Verify the written file
     * 4. Rename to .webp
     *
     * @param sessionId The session this chunk belongs to
     * @param index The chunk index within the session
     * @param bitmap The image to save
     * @return StorageResult with the chunk ID and file path on success
     */
    suspend fun saveChunk(
        sessionId: String,
        index: Int,
        bitmap: Bitmap
    ): StorageResult<Pair<String, String>> = withContext(Dispatchers.IO) {
        val mutex = getSessionMutex(sessionId)

        mutex.withLock {
            // 1. Pre-flight storage check
            val storageCheck = checkStorageAvailable()
            if (storageCheck is StorageResult.Error) {
                return@withContext storageCheck
            }

            // 2. Setup files
            val sessionDir = File(chunksDir, sessionId).also { it.mkdirs() }
            val chunkFilename = generateChunkFilename(sessionId, index)
            val finalFile = File(sessionDir, chunkFilename)
            val tempFile = File(sessionDir, "${chunkFilename}$StorageHelper.TEMP_EXTENSION")

            try {
                // 3. Write to temp file
                FileOutputStream(tempFile).use { out ->
                    val success = bitmap.compress(
                        Bitmap.CompressFormat.WEBP_LOSSY,
                        IMAGE_QUALITY,
                        out
                    )
                    if (!success) {
                        tempFile.delete()
                        return@withContext StorageResult.Error(
                            ErrorType.WRITE_FAILED,
                            "Failed to compress bitmap to WebP"
                        )
                    }
                    out.flush()
                }

                // 4. Verify the written file
                if (!verifyImageFile(tempFile)) {
                    tempFile.delete()
                    return@withContext StorageResult.Error(
                        ErrorType.VERIFICATION_FAILED,
                        "Written chunk failed verification"
                    )
                }

                // 5. Atomic rename (delete existing first if needed)
                if (finalFile.exists()) {
                    finalFile.delete()
                }

                val renameSuccess = tempFile.renameTo(finalFile)
                if (!renameSuccess) {
                    // Fallback: copy and delete
                    try {
                        tempFile.copyTo(finalFile, overwrite = true)
                        tempFile.delete()
                    } catch (e: Exception) {
                        tempFile.delete()
                        return@withContext StorageResult.Error(
                            ErrorType.WRITE_FAILED,
                            "Failed to finalize chunk file: ${e.message}",
                            e
                        )
                    }
                }

                // 6. Save thumbnail
                saveThumbnail(sessionDir, sessionId, index, bitmap)

                val chunkId = "${sessionId}_$index"
                Log.d(TAG, "Saved chunk $chunkId at ${finalFile.absolutePath}")

                StorageResult.Success(Pair(chunkId, finalFile.absolutePath))

            } catch (e: SecurityException) {
                tempFile.delete()
                StorageResult.Error(
                    ErrorType.PERMISSION_DENIED,
                    "Permission denied: ${e.message}",
                    e
                )
            } catch (e: IOException) {
                tempFile.delete()
                StorageResult.Error(
                    ErrorType.WRITE_FAILED,
                    "IO error: ${e.message}",
                    e
                )
            } catch (e: Exception) {
                tempFile.delete()
                StorageResult.Error(
                    ErrorType.UNKNOWN,
                    "Unexpected error: ${e.message}",
                    e
                )
            }
        }
    }

    /**
     * Saves a bitmap as a chunk image (legacy API for compatibility).
     *
     * @param sessionId The session this chunk belongs to
     * @param bitmap The image to save
     * @return The generated chunk ID and file path, or null if saving failed
     */
    suspend fun saveChunk(sessionId: String, bitmap: Bitmap): Pair<String, String>? = withContext(Dispatchers.IO) {
        // Get next available index
        val existingChunks = listChunksForSession(sessionId)
        val nextIndex = (existingChunks.maxOfOrNull { it.index } ?: -1) + 1

        when (val result = saveChunk(sessionId, nextIndex, bitmap)) {
            is StorageResult.Success -> result.data
            is StorageResult.Error -> {
                Log.e(TAG, "Failed to save chunk: ${result.message}")
                null
            }
        }
    }

    /**
     * Save a thumbnail for a chunk.
     */
    private fun saveThumbnail(sessionDir: File, sessionId: String, index: Int, bitmap: Bitmap) {
        try {
            val thumbnail = createThumbnail(bitmap)
            val thumbFilename = "${CHUNK_PREFIX}${sessionId}${CHUNK_MIDDLE}${index}${THUMB_SUFFIX}$StorageHelper.WEBP_EXTENSION"
            val thumbFile = File(sessionDir, thumbFilename)

            FileOutputStream(thumbFile).use { out ->
                thumbnail.compress(Bitmap.CompressFormat.WEBP_LOSSY, THUMBNAIL_QUALITY, out)
            }
            thumbnail.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save thumbnail", e)
            // Don't fail the main operation if thumbnail fails
        }
    }

    /**
     * Verify that an image file is valid.
     *
     * @param file The file to verify
     * @return true if the file appears to be a valid image
     */
    private fun verifyImageFile(file: File): Boolean {
        if (!file.exists() || file.length() < MIN_VALID_IMAGE_SIZE) {
            return false
        }

        return try {
            // Check WebP header
            file.inputStream().use { fis ->
                val header = ByteArray(12)
                val bytesRead = fis.read(header)

                if (bytesRead < 12) return false

                // Check RIFF header and WEBP signature
                val hasRiff = header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                        header[2] == 0x46.toByte() && header[3] == 0x46.toByte()
                val hasWebp = header[8] == 0x57.toByte() && header[9] == 0x45.toByte() &&
                        header[10] == 0x42.toByte() && header[11] == 0x50.toByte()

                hasRiff && hasWebp
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying image file", e)
            false
        }
    }

    /**
     * Loads a chunk image from storage.
     *
     * @param sessionId The session ID
     * @param chunkId The chunk ID (or just the index as string)
     * @return The bitmap, or null if not found or corrupted
     */
    suspend fun loadChunk(sessionId: String, chunkId: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // Check if marked as corrupted
            val sessionDir = File(chunksDir, sessionId)

            // Try to find the file - chunkId might be full ID or just index
            val file = findChunkFile(sessionDir, sessionId, chunkId)

            if (file == null || !file.exists()) {
                Log.w(TAG, "Chunk file not found: sessionId=$sessionId, chunkId=$chunkId")
                return@withContext null
            }

            // Check corruption marker
            val corruptedMarker = File(file.path + StorageHelper.CORRUPTED_EXTENSION)
            if (corruptedMarker.exists()) {
                Log.w(TAG, "Chunk is marked as corrupted: ${file.path}")
                return@withContext null
            }

            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap == null) {
                // Mark as corrupted
                markAsCorrupted(file.path)
                Log.e(TAG, "Failed to decode chunk, marking as corrupted: ${file.path}")
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load chunk $chunkId", e)
            null
        }
    }

    /**
     * Find a chunk file by session ID and chunk ID.
     */
    private fun findChunkFile(sessionDir: File, sessionId: String, chunkId: String): File? {
        if (!sessionDir.exists()) return null

        // If chunkId contains underscore, extract index
        val index = if (chunkId.contains("_")) {
            chunkId.substringAfterLast("_").toIntOrNull()
        } else {
            chunkId.toIntOrNull()
        }

        return if (index != null) {
            val filename = generateChunkFilename(sessionId, index)
            File(sessionDir, filename)
        } else {
            // Legacy support: look for file with matching chunk ID
            sessionDir.listFiles()?.find {
                it.name.contains(chunkId) &&
                it.name.endsWith(StorageHelper.WEBP_EXTENSION) &&
                !it.name.contains(THUMB_SUFFIX)
            }
        }
    }

    /**
     * Loads a chunk thumbnail from storage.
     *
     * @param sessionId The session ID
     * @param chunkId The chunk ID
     * @return The thumbnail bitmap, or null if not found
     */
    suspend fun loadThumbnail(sessionId: String, chunkId: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val sessionDir = File(chunksDir, sessionId)
            if (!sessionDir.exists()) return@withContext null

            // Extract index from chunkId
            val index = if (chunkId.contains("_")) {
                chunkId.substringAfterLast("_").toIntOrNull()
            } else {
                chunkId.toIntOrNull()
            }

            val thumbFile = if (index != null) {
                val thumbFilename = "${CHUNK_PREFIX}${sessionId}${CHUNK_MIDDLE}${index}${THUMB_SUFFIX}$StorageHelper.WEBP_EXTENSION"
                File(sessionDir, thumbFilename)
            } else {
                // Legacy support
                sessionDir.listFiles()?.find {
                    it.name.contains(chunkId) &&
                    it.name.contains(THUMB_SUFFIX)
                }
            }

            if (thumbFile == null || !thumbFile.exists()) {
                Log.w(TAG, "Thumbnail file not found for chunk $chunkId")
                return@withContext null
            }

            BitmapFactory.decodeFile(thumbFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load thumbnail $chunkId", e)
            null
        }
    }

    /**
     * Loads a chunk as a byte array (for API transmission).
     *
     * @param sessionId The session ID
     * @param chunkId The chunk ID
     * @return The image data, or null if not found
     */
    suspend fun loadChunkBytes(sessionId: String, chunkId: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val sessionDir = File(chunksDir, sessionId)
            val file = findChunkFile(sessionDir, sessionId, chunkId)

            if (file == null || !file.exists()) {
                return@withContext null
            }

            file.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load chunk bytes $chunkId", e)
            null
        }
    }

    /**
     * Gets the file path for a chunk.
     *
     * @param sessionId The session ID
     * @param index The chunk index
     * @return The absolute file path
     */
    fun getChunkPath(sessionId: String, index: Int): String {
        val sessionDir = File(chunksDir, sessionId)
        val filename = generateChunkFilename(sessionId, index)
        return File(sessionDir, filename).absolutePath
    }

    /**
     * Gets the file path for a chunk (legacy API).
     *
     * @param sessionId The session ID
     * @param chunkId The chunk ID
     * @return The absolute file path
     */
    fun getChunkPath(sessionId: String, chunkId: String): String {
        val index = if (chunkId.contains("_")) {
            chunkId.substringAfterLast("_").toIntOrNull() ?: 0
        } else {
            chunkId.toIntOrNull() ?: 0
        }
        return getChunkPath(sessionId, index)
    }

    /**
     * Checks if a chunk file exists and is valid.
     *
     * @param sessionId The session ID
     * @param chunkId The chunk ID
     * @return true if the chunk exists and has non-zero size
     */
    suspend fun chunkExists(sessionId: String, chunkId: String): Boolean = withContext(Dispatchers.IO) {
        val sessionDir = File(chunksDir, sessionId)
        val file = findChunkFile(sessionDir, sessionId, chunkId)
        file?.exists() == true && file.length() > 0
    }

    /**
     * Deletes a chunk and its thumbnail.
     *
     * @param sessionId The session ID
     * @param chunkId The chunk ID
     * @return true if deletion was successful
     */
    suspend fun deleteChunk(sessionId: String, chunkId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val sessionDir = File(chunksDir, sessionId)
            val chunkFile = findChunkFile(sessionDir, sessionId, chunkId)

            var deleted = true

            if (chunkFile != null && chunkFile.exists()) {
                // Delete main chunk file
                deleted = chunkFile.delete()

                // Delete thumbnail
                val thumbFilename = chunkFile.name.replace(StorageHelper.WEBP_EXTENSION, "${THUMB_SUFFIX}$StorageHelper.WEBP_EXTENSION")
                val thumbFile = File(sessionDir, thumbFilename)
                if (thumbFile.exists()) {
                    thumbFile.delete()
                }

                // Delete corrupted marker if exists
                val corruptedMarker = File(chunkFile.path + StorageHelper.CORRUPTED_EXTENSION)
                if (corruptedMarker.exists()) {
                    corruptedMarker.delete()
                }
            }

            Log.d(TAG, "Deleted chunk $chunkId: $deleted")
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete chunk $chunkId", e)
            false
        }
    }

    /**
     * Deletes all chunks for a session.
     *
     * @param sessionId The session ID
     * @return true if deletion was successful
     */
    suspend fun deleteSessionChunks(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val mutex = getSessionMutex(sessionId)

        mutex.withLock {
            try {
                val sessionDir = File(chunksDir, sessionId)
                if (sessionDir.exists()) {
                    sessionDir.deleteRecursively()
                }

                // Clean up mutex
                sessionMutexes.remove(sessionId)

                Log.d(TAG, "Deleted all chunks for session $sessionId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete session chunks $sessionId", e)
                false
            }
        }
    }

    /**
     * Lists all chunks for a session.
     *
     * @param sessionId The session ID
     * @return List of Chunks sorted by index
     */
    suspend fun listChunksForSession(sessionId: String): List<Chunk> = withContext(Dispatchers.IO) {
        val sessionDir = File(chunksDir, sessionId)
        if (!sessionDir.exists()) return@withContext emptyList()

        val chunks = mutableListOf<Chunk>()

        sessionDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(StorageHelper.WEBP_EXTENSION) && !file.name.contains(THUMB_SUFFIX)) {
                val parsed = parseChunkFilename(file.name)
                if (parsed != null) {
                    val (_, index) = parsed
                    val isCorrupted = File(file.path + StorageHelper.CORRUPTED_EXTENSION).exists()

                    chunks.add(
                        Chunk(
                            id = "${sessionId}_$index",
                            sessionId = sessionId,
                            index = index,
                            filePath = file.absolutePath,
                            timestampSeconds = file.lastModified() / 1000f,
                            createdAt = file.lastModified(),
                            isCorrupted = isCorrupted
                        )
                    )
                }
            }
        }

        chunks.sortedBy { it.index }
    }

    /**
     * Lists all chunk IDs for a session (legacy API).
     *
     * @param sessionId The session ID
     * @return List of chunk IDs (without extension)
     */
    suspend fun listChunkIds(sessionId: String): List<String> = withContext(Dispatchers.IO) {
        listChunksForSession(sessionId).map { it.id }
    }

    /**
     * Mark a chunk file as corrupted.
     *
     * @param filePath The path to the chunk file
     * @return true if marker was created successfully
     */
    suspend fun markAsCorrupted(filePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val markerFile = File(filePath + StorageHelper.CORRUPTED_EXTENSION)
            markerFile.createNewFile()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark file as corrupted: $filePath", e)
            false
        }
    }

    /**
     * Check if a chunk file is marked as corrupted.
     *
     * @param filePath The path to the chunk file
     * @return true if the chunk is marked as corrupted
     */
    fun isMarkedAsCorrupted(filePath: String): Boolean {
        val markerFile = File(filePath + StorageHelper.CORRUPTED_EXTENSION)
        return markerFile.exists()
    }

    /**
     * Validate all chunks for a session and mark corrupted ones.
     *
     * @param sessionId The session ID
     * @return Pair of (validCount, corruptedCount)
     */
    suspend fun validateSessionChunks(sessionId: String): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val chunks = listChunksForSession(sessionId)
        var validCount = 0
        var corruptedCount = 0

        for (chunk in chunks) {
            if (chunk.isCorrupted) {
                corruptedCount++
                continue
            }

            val file = File(chunk.filePath)
            if (verifyImageFile(file)) {
                validCount++
            } else {
                markAsCorrupted(chunk.filePath)
                corruptedCount++
            }
        }

        validCount to corruptedCount
    }

    /**
     * Gets the total size of all chunks for a session.
     *
     * @param sessionId The session ID
     * @return Size in bytes
     */
    suspend fun getSessionSize(sessionId: String): Long = withContext(Dispatchers.IO) {
        val sessionDir = File(chunksDir, sessionId)
        if (!sessionDir.exists()) return@withContext 0L

        sessionDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    /**
     * Gets the total storage used by all chunks.
     *
     * @return Size in bytes
     */
    suspend fun getTotalStorageUsed(): Long = withContext(Dispatchers.IO) {
        chunksDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    /**
     * Clean up orphaned temp files.
     *
     * @return Number of files cleaned up
     */
    suspend fun cleanupTempFiles(): Int = withContext(Dispatchers.IO) {
        var cleanedCount = 0

        chunksDir.walkTopDown().forEach { file ->
            if (file.isFile && file.name.endsWith(StorageHelper.TEMP_EXTENSION)) {
                if (file.delete()) {
                    cleanedCount++
                }
            }
        }

        Log.d(TAG, "Cleaned up $cleanedCount temp files")
        cleanedCount
    }

    /**
     * Get all session IDs that have stored chunks.
     *
     * @return Set of session IDs
     */
    suspend fun getAllSessionIds(): Set<String> = withContext(Dispatchers.IO) {
        chunksDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
    }

    private fun createThumbnail(bitmap: Bitmap): Bitmap {
        val ratio = minOf(
            THUMBNAIL_SIZE.toFloat() / bitmap.width,
            THUMBNAIL_SIZE.toFloat() / bitmap.height
        )
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

}
