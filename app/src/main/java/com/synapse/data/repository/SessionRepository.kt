package com.synapse.data.repository

import com.synapse.data.storage.ChunkStorage
import com.synapse.data.storage.SessionStorage
import com.synapse.model.CapturedContext
import com.synapse.model.Session
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing capture sessions.
 *
 * A session represents a recording period where handwriting is being captured.
 * Sessions track start/end times and contain multiple chunks.
 *
 * Uses SessionStorage for metadata and ChunkStorage for cleanup operations.
 */
class SessionRepository(
    private val sessionStorage: SessionStorage,
    private val chunkStorage: ChunkStorage
) {

    /**
     * Creates a new capture session.
     *
     * Only one session can be active at a time. If a session is already active,
     * it will be returned instead of creating a new one.
     *
     * @return The newly created or existing active session
     */
    suspend fun createSession(): Session {
        // Return existing active session if one exists, otherwise create new
        val activeSession = sessionStorage.getActiveSession()
        if (activeSession != null) {
            return activeSession
        }

        return sessionStorage.createSession()
    }

    /**
     * Ends the current active session.
     *
     * @param sessionId The session ID to end
     * @throws IllegalArgumentException if session not found
     */
    suspend fun endSession(sessionId: String) {
        val session = sessionStorage.getSession(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        if (session.endedAt != null) {
            // Session already ended, no-op
            return
        }

        sessionStorage.endSession(sessionId)
    }

    /**
     * Gets a session by ID.
     *
     * @param sessionId The session ID
     * @return The session, or null if not found
     */
    suspend fun getSession(sessionId: String): Session? {
        return sessionStorage.getSession(sessionId)
    }

    /**
     * Gets all sessions that are pending sync.
     *
     * Pending sessions are those that have ended but haven't been
     * successfully synced to a project file yet.
     *
     * @return List of pending sessions
     */
    suspend fun getPendingSessions(): List<Session> {
        return sessionStorage.getPendingSessions()
    }

    /**
     * Deletes a session and all its associated data.
     *
     * This includes all chunks and their image files.
     *
     * @param sessionId The session ID to delete
     */
    suspend fun deleteSession(sessionId: String) {
        // Delete all chunk files first
        chunkStorage.deleteSessionChunks(sessionId)

        // Delete session metadata
        sessionStorage.deleteSession(sessionId)
    }

    /**
     * Deletes a single chunk from a session.
     *
     * @param sessionId The session ID
     * @param chunkId The chunk ID to delete
     */
    suspend fun deleteChunk(sessionId: String, chunkId: String) {
        // Delete chunk file
        chunkStorage.deleteChunk(sessionId, chunkId)

        // Update session metadata to remove the chunk
        sessionStorage.removeChunk(sessionId, chunkId)
    }

    /**
     * Observes all sessions.
     *
     * @return Flow of session list, sorted by start time descending
     */
    fun observeSessions(): Flow<List<Session>> {
        return sessionStorage.observeSessions()
    }

    /**
     * Gets the currently active session.
     *
     * @return The active session, or null if none
     */
    suspend fun getActiveSession(): Session? {
        return sessionStorage.getActiveSession()
    }

    /**
     * Adds a captured context to a session.
     *
     * @param sessionId The session ID
     * @param context The captured context to add
     */
    suspend fun addContext(sessionId: String, context: CapturedContext) {
        sessionStorage.addContext(sessionId, context)
    }

    /**
     * Removes a captured context from a session.
     *
     * @param sessionId The session ID
     * @param contextId The context ID to remove
     */
    suspend fun removeContext(sessionId: String, contextId: String) {
        sessionStorage.removeContext(sessionId, contextId)
    }
}
