package com.synapse.data.repository

import android.graphics.Bitmap
import com.synapse.data.storage.ChunkStorage
import com.synapse.data.storage.SessionStorage
import com.synapse.model.Chunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository interface for managing chunks (captured handwriting images).
 *
 * Chunks represent individual screen captures within a session. Each chunk
 * contains the captured image and associated metadata like timestamp.
 */
interface ChunkRepository {

    /**
     * Saves a new chunk image to storage.
     *
     * @param sessionId The session this chunk belongs to
     * @param bitmap The captured image
     * @param timestampSeconds Time offset from session start in seconds
     * @return The created Chunk, or throws exception on failure
     */
    suspend fun saveChunk(sessionId: String, bitmap: Bitmap, timestampSeconds: Float): Chunk

    /**
     * Gets all chunks for a specific session.
     *
     * @param sessionId The session ID
     * @return List of chunks, sorted by index
     */
    suspend fun getChunksForSession(sessionId: String): List<Chunk>

    /**
     * Gets a specific chunk by ID.
     *
     * @param chunkId The chunk ID
     * @return The chunk, or null if not found
     */
    suspend fun getChunk(chunkId: String): Chunk?

    /**
     * Gets the full-resolution image for a chunk.
     *
     * @param chunkId The chunk ID
     * @return The bitmap, or null if not found or corrupted
     */
    suspend fun getChunkImage(chunkId: String): Bitmap?

    /**
     * Gets the thumbnail image for a chunk.
     *
     * @param chunkId The chunk ID
     * @return The thumbnail bitmap, or null if not found
     */
    suspend fun getChunkThumbnail(chunkId: String): Bitmap?

    /**
     * Deletes a chunk and its associated images.
     *
     * @param chunkId The chunk ID to delete
     */
    suspend fun deleteChunk(chunkId: String)

    /**
     * Marks a chunk as corrupted (e.g., unreadable image file).
     *
     * @param chunkId The chunk ID to mark
     */
    suspend fun markChunkCorrupted(chunkId: String)

    /**
     * Observes all chunks across all sessions.
     *
     * @return Flow of all chunks, sorted by creation time descending
     */
    fun observeChunks(): Flow<List<Chunk>>
}

/**
 * Default implementation of ChunkRepository.
 *
 * Uses ChunkStorage for image file operations and SessionStorage for metadata.
 */
class ChunkRepositoryImpl(
    private val chunkStorage: ChunkStorage,
    private val sessionStorage: SessionStorage
) : ChunkRepository {

    override suspend fun saveChunk(sessionId: String, bitmap: Bitmap, timestampSeconds: Float): Chunk {
        // Get current session to determine chunk index
        val session = sessionStorage.getSession(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        // Save the image to storage
        val (chunkId, filePath) = chunkStorage.saveChunk(sessionId, bitmap)
            ?: throw RuntimeException("Failed to save chunk image")

        // Create chunk metadata
        val chunk = Chunk(
            id = chunkId,
            sessionId = sessionId,
            index = session.chunks.size,
            filePath = filePath,
            timestampSeconds = timestampSeconds,
            createdAt = System.currentTimeMillis(),
            isCorrupted = false
        )

        // Add chunk to session
        sessionStorage.addChunk(sessionId, chunk)

        return chunk
    }

    override suspend fun getChunksForSession(sessionId: String): List<Chunk> {
        val session = sessionStorage.getSession(sessionId) ?: return emptyList()
        return session.chunks.sortedBy { it.index }
    }

    override suspend fun getChunk(chunkId: String): Chunk? {
        val result = sessionStorage.findChunk(chunkId)
        return result?.second
    }

    override suspend fun getChunkImage(chunkId: String): Bitmap? {
        val result = sessionStorage.findChunk(chunkId) ?: return null
        val (sessionId, chunk) = result

        if (chunk.isCorrupted) return null

        return chunkStorage.loadChunk(sessionId, chunkId)
    }

    override suspend fun getChunkThumbnail(chunkId: String): Bitmap? {
        val result = sessionStorage.findChunk(chunkId) ?: return null
        val (sessionId, _) = result

        return chunkStorage.loadThumbnail(sessionId, chunkId)
    }

    override suspend fun deleteChunk(chunkId: String) {
        val result = sessionStorage.findChunk(chunkId) ?: return
        val (sessionId, _) = result

        // Delete image files
        chunkStorage.deleteChunk(sessionId, chunkId)

        // Remove from session metadata
        sessionStorage.removeChunk(sessionId, chunkId)
    }

    override suspend fun markChunkCorrupted(chunkId: String) {
        val result = sessionStorage.findChunk(chunkId) ?: return
        val (sessionId, chunk) = result

        val corruptedChunk = chunk.copy(isCorrupted = true)
        sessionStorage.updateChunk(sessionId, corruptedChunk)
    }

    override fun observeChunks(): Flow<List<Chunk>> {
        return sessionStorage.observeSessions().map { sessions ->
            sessions.flatMap { it.chunks }
                .sortedByDescending { it.createdAt }
        }
    }
}
