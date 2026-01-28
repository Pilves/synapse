package com.synapse.data.repository

import com.synapse.data.storage.ChunkStorage
import com.synapse.data.storage.SessionStorage
import com.synapse.model.Session
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing capture sessions.
 *
 * A session represents a recording period where handwriting is being captured.
 * Sessions track start/end times and contain multiple chunks.
 */
interface SessionRepository {

    /**
     * Creates a new capture session.
     *
     * Only one session can be active at a time. Creating a new session
     * while one is active will throw an exception.
     *
     * @return The newly created session
     * @throws IllegalStateException if a session is already active
     */
    suspend fun createSession(): Session

    /**
     * Ends the current active session.
     *
     * @param sessionId The session ID to end
     * @throws IllegalArgumentException if session not found
     */
    suspend fun endSession(sessionId: String)

    /**
     * Gets a session by ID.
     *
     * @param sessionId The session ID
     * @return The session, or null if not found
     */
    suspend fun getSession(sessionId: String): Session?

    /**
     * Gets all sessions that are pending sync.
     *
     * Pending sessions are those that have ended but haven't been
     * successfully synced to a project file yet.
     *
     * @return List of pending sessions
     */
    suspend fun getPendingSessions(): List<Session>

    /**
     * Deletes a session and all its associated data.
     *
     * This includes all chunks and their image files.
     *
     * @param sessionId The session ID to delete
     */
    suspend fun deleteSession(sessionId: String)

    /**
     * Deletes a single chunk from a session.
     *
     * @param sessionId The session ID
     * @param chunkId The chunk ID to delete
     */
    suspend fun deleteChunk(sessionId: String, chunkId: String)

    /**
     * Observes all sessions.
     *
     * @return Flow of session list, sorted by start time descending
     */
    fun observeSessions(): Flow<List<Session>>

    /**
     * Gets the currently active session.
     *
     * @return The active session, or null if none
     */
    suspend fun getActiveSession(): Session?
}

/**
 * Default implementation of SessionRepository.
 *
 * Uses SessionStorage for metadata and ChunkStorage for cleanup operations.
 */
class SessionRepositoryImpl(
    private val sessionStorage: SessionStorage,
    private val chunkStorage: ChunkStorage
) : SessionRepository {

    override suspend fun createSession(): Session {
        // Return existing active session if one exists, otherwise create new
        val activeSession = sessionStorage.getActiveSession()
        if (activeSession != null) {
            return activeSession
        }

        return sessionStorage.createSession()
    }

    override suspend fun endSession(sessionId: String) {
        val session = sessionStorage.getSession(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        if (session.endedAt != null) {
            // Session already ended, no-op
            return
        }

        sessionStorage.endSession(sessionId)
    }

    override suspend fun getSession(sessionId: String): Session? {
        return sessionStorage.getSession(sessionId)
    }

    override suspend fun getPendingSessions(): List<Session> {
        return sessionStorage.getPendingSessions()
    }

    override suspend fun deleteSession(sessionId: String) {
        // Delete all chunk files first
        chunkStorage.deleteSessionChunks(sessionId)

        // Delete session metadata
        sessionStorage.deleteSession(sessionId)
    }

    override suspend fun deleteChunk(sessionId: String, chunkId: String) {
        // Delete chunk file
        chunkStorage.deleteChunk(sessionId, chunkId)

        // Update session metadata to remove the chunk
        sessionStorage.removeChunk(sessionId, chunkId)
    }

    override fun observeSessions(): Flow<List<Session>> {
        return sessionStorage.observeSessions()
    }

    override suspend fun getActiveSession(): Session? {
        return sessionStorage.getActiveSession()
    }
}
