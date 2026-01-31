package com.synapse.data.repository

import android.graphics.Bitmap
import android.util.LruCache
import com.synapse.data.storage.ChunkStorage
import com.synapse.data.storage.SessionStorage
import com.synapse.model.Chunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Repository for managing chunks (captured handwriting images).
 *
 * Chunks represent individual screen captures within a session. Each chunk
 * contains the captured image and associated metadata like timestamp.
 *
 * Uses ChunkStorage for image file operations and SessionStorage for metadata.
 */
class ChunkRepository(
    private val chunkStorage: ChunkStorage,
    private val sessionStorage: SessionStorage
) {

    private val imageCache = object : LruCache<String, Bitmap>(
        // Cap at 48MB to prevent excessive heap usage on high-end devices
        minOf((Runtime.getRuntime().maxMemory() / 16).toInt(), 48 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            // Don't recycle — callers may still hold references to evicted bitmaps.
            // GC will reclaim the native memory when no references remain.
        }
    }

    /**
     * Saves a new chunk image to storage.
     *
     * @param sessionId The session this chunk belongs to
     * @param bitmap The captured image
     * @param timestampSeconds Time offset from session start in seconds
     * @return The created Chunk, or throws exception on failure
     */
    suspend fun saveChunk(sessionId: String, bitmap: Bitmap, timestampSeconds: Float): Chunk {
        // Validate session exists
        sessionStorage.getSession(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        // Use single authoritative source for chunk index
        val nextIndex = sessionStorage.getNextChunkIndex(sessionId)

        // Save the image to storage using the authoritative index
        val (chunkId, filePath) = chunkStorage.saveChunk(sessionId, nextIndex, bitmap)

        // Create chunk metadata
        val chunk = Chunk(
            id = chunkId,
            sessionId = sessionId,
            index = nextIndex,
            filePath = filePath,
            timestampSeconds = timestampSeconds,
            createdAt = System.currentTimeMillis(),
            isCorrupted = false
        )

        // Add chunk to session
        sessionStorage.addChunk(sessionId, chunk)

        return chunk
    }

    /**
     * Gets all chunks for a specific session.
     *
     * @param sessionId The session ID
     * @return List of chunks, sorted by index
     */
    suspend fun getChunksForSession(sessionId: String): List<Chunk> {
        val session = sessionStorage.getSession(sessionId) ?: return emptyList()
        return session.chunks.sortedBy { it.index }
    }

    /**
     * Gets a specific chunk by ID.
     *
     * @param chunkId The chunk ID
     * @return The chunk, or null if not found
     */
    suspend fun getChunk(chunkId: String): Chunk? {
        val result = sessionStorage.findChunk(chunkId)
        return result?.second
    }

    /**
     * Gets the full-resolution image for a chunk.
     *
     * @param chunkId The chunk ID
     * @return The bitmap, or null if not found or corrupted
     */
    suspend fun getChunkImage(chunkId: String): Bitmap? {
        imageCache.get(chunkId)?.let { return it }

        val result = sessionStorage.findChunk(chunkId) ?: return null
        val (sessionId, chunk) = result

        if (chunk.isCorrupted) return null

        val bitmap = chunkStorage.loadChunk(sessionId, chunkId)
        if (bitmap != null) {
            imageCache.put(chunkId, bitmap)
        }
        return bitmap?.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
    }

    /**
     * Gets the thumbnail image for a chunk.
     *
     * @param chunkId The chunk ID
     * @return The thumbnail bitmap, or null if not found
     */
    suspend fun getChunkThumbnail(chunkId: String): Bitmap? {
        val result = sessionStorage.findChunk(chunkId) ?: return null
        val (sessionId, _) = result

        return chunkStorage.loadThumbnail(sessionId, chunkId)
    }

    /**
     * Deletes a chunk and its associated images.
     *
     * @param chunkId The chunk ID to delete
     */
    suspend fun deleteChunk(chunkId: String) {
        imageCache.remove(chunkId)

        val result = sessionStorage.findChunk(chunkId) ?: return
        val (sessionId, _) = result

        // Delete image files
        chunkStorage.deleteChunk(sessionId, chunkId)

        // Remove from session metadata
        sessionStorage.removeChunk(sessionId, chunkId)
    }

    /**
     * Marks a chunk as corrupted (e.g., unreadable image file).
     *
     * @param chunkId The chunk ID to mark
     */
    suspend fun markChunkCorrupted(chunkId: String) {
        val result = sessionStorage.findChunk(chunkId) ?: return
        val (sessionId, chunk) = result

        val corruptedChunk = chunk.copy(isCorrupted = true)
        sessionStorage.updateChunk(sessionId, corruptedChunk)
    }

    /**
     * Observes all chunks across all sessions.
     *
     * @return Flow of all chunks, sorted by creation time descending
     */
    fun observeChunks(): Flow<List<Chunk>> {
        return sessionStorage.observeSessions().map { sessions ->
            sessions.flatMap { it.chunks }
                .sortedByDescending { it.createdAt }
        }.distinctUntilChanged()
    }
}
