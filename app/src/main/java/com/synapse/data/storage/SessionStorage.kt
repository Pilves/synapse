package com.synapse.data.storage

import android.content.Context
import android.util.Log
import com.synapse.model.CapturedContext
import com.synapse.model.Chunk
import com.synapse.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException

/**
 * Handles persistent storage for session and chunk metadata.
 *
 * Sessions are stored as JSON files in the app's internal storage:
 * sessions/{sessionId}.json
 *
 * This class manages the session lifecycle and provides reactive updates
 * via Kotlin Flow.
 *
 * Features:
 * - Create new sessions
 * - Add chunks to sessions
 * - End sessions
 * - List pending (not ended) sessions
 * - Delete sessions and their chunks
 * - Persist session metadata as JSON
 */
class SessionStorage(
    private val context: Context,
    private val chunkStorage: ChunkStorage? = null
) {

    companion object {
        private const val TAG = "SessionStorage"
        private const val SESSIONS_DIR = "sessions"
        private const val JSON_EXTENSION = ".json"
        private const val TEMP_EXTENSION = ".tmp"
    }

    private val mutex = Mutex()
    private val _sessionsFlow = MutableStateFlow<List<Session>>(emptyList())

    private val sessionsDir: File
        get() = File(context.filesDir, SESSIONS_DIR).also { it.mkdirs() }

    /**
     * Result of a session operation.
     */
    sealed class SessionResult<out T> {
        data class Success<T>(val data: T) : SessionResult<T>()
        data class Error(
            val type: ErrorType,
            val message: String,
            val exception: Exception? = null
        ) : SessionResult<Nothing>()

        enum class ErrorType {
            SESSION_NOT_FOUND,
            WRITE_FAILED,
            READ_FAILED,
            PERMISSION_DENIED,
            UNKNOWN
        }
    }

    /**
     * Initializes the storage and loads existing sessions.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        loadAllSessions()
    }

    /**
     * Observes all sessions.
     *
     * @return Flow of session list, sorted by start time descending
     */
    fun observeSessions(): Flow<List<Session>> = _sessionsFlow.asStateFlow()

    /**
     * Generate a unique session ID based on timestamp.
     *
     * @return Session ID (timestamp string)
     */
    fun generateSessionId(): String {
        return System.currentTimeMillis().toString()
    }

    /**
     * Creates a new session.
     *
     * @param sessionId Optional custom session ID (defaults to timestamp)
     * @return The newly created session
     */
    suspend fun createSession(sessionId: String? = null): Session = mutex.withLock {
        val id = sessionId ?: generateSessionId()
        val startTime = System.currentTimeMillis()

        val session = Session(
            id = id,
            startedAt = startTime,
            endedAt = null,
            chunks = emptyList()
        )

        saveSessionInternal(session)
        refreshSessionInMemory(session)

        Log.d(TAG, "Created session ${session.id}")
        session
    }

    /**
     * Creates a new session with result type for better error handling.
     *
     * @param sessionId Optional custom session ID
     * @return SessionResult with the created Session
     */
    suspend fun createSessionWithResult(sessionId: String? = null): SessionResult<Session> = mutex.withLock {
        try {
            val id = sessionId ?: generateSessionId()
            val startTime = System.currentTimeMillis()

            val session = Session(
                id = id,
                startedAt = startTime,
                endedAt = null,
                chunks = emptyList()
            )

            val metadataFile = File(sessionsDir, "$id$JSON_EXTENSION")
            val dto = SessionDto.fromSession(session)
            StorageHelper.atomicWriteText(metadataFile, StorageJson.instance.encodeToString(dto))

            refreshSessionInMemory(session)

            Log.d(TAG, "Created session $id")
            SessionResult.Success(session)
        } catch (e: IOException) {
            SessionResult.Error(
                SessionResult.ErrorType.WRITE_FAILED,
                "Failed to create session: ${e.message}",
                e
            )
        } catch (e: Exception) {
            SessionResult.Error(
                SessionResult.ErrorType.UNKNOWN,
                "Unexpected error creating session: ${e.message}",
                e
            )
        }
    }

    /**
     * Gets a session by ID.
     *
     * @param sessionId The session ID
     * @return The session, or null if not found
     */
    suspend fun getSession(sessionId: String): Session? = withContext(Dispatchers.IO) {
        try {
            val file = File(sessionsDir, "$sessionId$JSON_EXTENSION")
            if (!file.exists()) return@withContext null

            val dto = StorageJson.instance.decodeFromString<SessionDto>(file.readText())
            dto.toSession()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load session $sessionId", e)
            null
        }
    }

    /**
     * Gets a session by ID with result type for error handling.
     *
     * @param sessionId The session ID
     * @return SessionResult with the Session
     */
    suspend fun getSessionWithResult(sessionId: String): SessionResult<Session> = withContext(Dispatchers.IO) {
        try {
            val file = File(sessionsDir, "$sessionId$JSON_EXTENSION")
            if (!file.exists()) {
                return@withContext SessionResult.Error(
                    SessionResult.ErrorType.SESSION_NOT_FOUND,
                    "Session not found: $sessionId"
                )
            }

            val dto = StorageJson.instance.decodeFromString<SessionDto>(file.readText())
            SessionResult.Success(dto.toSession())
        } catch (e: Exception) {
            SessionResult.Error(
                SessionResult.ErrorType.READ_FAILED,
                "Failed to read session: ${e.message}",
                e
            )
        }
    }

    /**
     * Gets all sessions.
     *
     * @return List of all sessions, sorted by start time descending
     */
    suspend fun getAllSessions(): List<Session> = withContext(Dispatchers.IO) {
        loadSessionsFromDisk()
    }

    /**
     * Gets the currently active (non-ended) session.
     *
     * @return The active session, or null if none
     */
    suspend fun getActiveSession(): Session? = withContext(Dispatchers.IO) {
        loadSessionsFromDisk().find { it.endedAt == null }
    }

    /**
     * Gets sessions that are pending (ended but not yet processed/synced).
     * A pending session is one that:
     * - Has ended (endedAt is not null)
     * - Has at least one chunk
     *
     * @return List of pending sessions, sorted by start time descending
     */
    suspend fun getPendingSessions(): List<Session> = withContext(Dispatchers.IO) {
        loadSessionsFromDisk().filter { session ->
            session.endedAt != null && (session.chunks.isNotEmpty() || session.contexts.isNotEmpty())
        }
    }

    /**
     * Gets sessions that are still in progress (not yet ended).
     *
     * @return List of in-progress sessions
     */
    suspend fun getInProgressSessions(): List<Session> = withContext(Dispatchers.IO) {
        loadSessionsFromDisk().filter { session ->
            session.endedAt == null
        }
    }

    /**
     * Saves or updates a session.
     *
     * @param session The session to save
     */
    suspend fun saveSession(session: Session) = mutex.withLock {
        saveSessionInternal(session)
        refreshSessionInMemory(session)
        Log.d(TAG, "Saved session ${session.id}")
    }

    /**
     * Ends a session by setting its end timestamp.
     *
     * @param sessionId The session ID
     * @return The updated session, or null if not found
     */
    suspend fun endSession(sessionId: String): Session? = mutex.withLock {
        val session = getSession(sessionId) ?: return@withLock null

        val updatedSession = session.copy(endedAt = System.currentTimeMillis())
        saveSessionInternal(updatedSession)
        refreshSessionInMemory(updatedSession)

        Log.d(TAG, "Ended session $sessionId")
        updatedSession
    }

    /**
     * Ends a session with result type for better error handling.
     *
     * @param sessionId The session ID
     * @return SessionResult with the ended Session
     */
    suspend fun endSessionWithResult(sessionId: String): SessionResult<Session> = mutex.withLock {
        val session = getSession(sessionId)
            ?: return@withLock SessionResult.Error(
                SessionResult.ErrorType.SESSION_NOT_FOUND,
                "Session not found: $sessionId"
            )

        try {
            val updatedSession = session.copy(endedAt = System.currentTimeMillis())
            saveSessionInternal(updatedSession)
            refreshSessionInMemory(updatedSession)

            Log.d(TAG, "Ended session $sessionId")
            SessionResult.Success(updatedSession)
        } catch (e: Exception) {
            SessionResult.Error(
                SessionResult.ErrorType.WRITE_FAILED,
                "Failed to end session: ${e.message}",
                e
            )
        }
    }

    /**
     * Adds a chunk to a session.
     *
     * @param sessionId The session ID
     * @param chunk The chunk to add
     * @return The updated session, or null if session not found
     */
    suspend fun addChunk(sessionId: String, chunk: Chunk): Session? = mutex.withLock {
        val session = getSession(sessionId) ?: return@withLock null

        val updatedSession = session.copy(
            chunks = session.chunks + chunk
        )
        saveSessionInternal(updatedSession)
        refreshSessionInMemory(updatedSession)

        Log.d(TAG, "Added chunk ${chunk.id} to session $sessionId")
        updatedSession
    }

    /**
     * Adds a captured context to a session.
     *
     * @param sessionId The session ID
     * @param context The captured context to add
     * @return The updated session, or null if session not found
     */
    suspend fun addContext(sessionId: String, context: CapturedContext): Session? = mutex.withLock {
        val session = getSession(sessionId) ?: return@withLock null

        val updatedSession = session.copy(
            contexts = session.contexts + context
        )
        saveSessionInternal(updatedSession)
        refreshSessionInMemory(updatedSession)

        Log.d(TAG, "Added context ${context.id} to session $sessionId")
        updatedSession
    }

    /**
     * Adds a chunk to a session with result type.
     *
     * @param sessionId The session ID
     * @param chunk The chunk to add
     * @return SessionResult with the updated Session
     */
    suspend fun addChunkWithResult(sessionId: String, chunk: Chunk): SessionResult<Session> = mutex.withLock {
        val session = getSession(sessionId)
            ?: return@withLock SessionResult.Error(
                SessionResult.ErrorType.SESSION_NOT_FOUND,
                "Session not found: $sessionId"
            )

        try {
            val updatedSession = session.copy(
                chunks = session.chunks + chunk
            )
            saveSessionInternal(updatedSession)
            refreshSessionInMemory(updatedSession)

            Log.d(TAG, "Added chunk ${chunk.id} to session $sessionId")
            SessionResult.Success(updatedSession)
        } catch (e: Exception) {
            SessionResult.Error(
                SessionResult.ErrorType.WRITE_FAILED,
                "Failed to add chunk: ${e.message}",
                e
            )
        }
    }

    /**
     * Updates a chunk within a session.
     *
     * @param sessionId The session ID
     * @param chunk The updated chunk
     * @return The updated session, or null if session not found
     */
    suspend fun updateChunk(sessionId: String, chunk: Chunk): Session? = mutex.withLock {
        val session = getSession(sessionId) ?: return@withLock null

        val updatedChunks = session.chunks.map {
            if (it.id == chunk.id) chunk else it
        }
        val updatedSession = session.copy(chunks = updatedChunks)
        saveSessionInternal(updatedSession)
        refreshSessionInMemory(updatedSession)

        Log.d(TAG, "Updated chunk ${chunk.id} in session $sessionId")
        updatedSession
    }

    /**
     * Removes a chunk from a session.
     *
     * @param sessionId The session ID
     * @param chunkId The chunk ID to remove
     * @return The updated session, or null if session not found
     */
    suspend fun removeChunk(sessionId: String, chunkId: String): Session? = mutex.withLock {
        val session = getSession(sessionId) ?: return@withLock null

        val updatedSession = session.copy(
            chunks = session.chunks.filter { it.id != chunkId }
        )
        saveSessionInternal(updatedSession)
        refreshSessionInMemory(updatedSession)

        Log.d(TAG, "Removed chunk $chunkId from session $sessionId")
        updatedSession
    }

    /**
     * Deletes a session and its metadata file.
     * Optionally also deletes associated chunk files.
     *
     * @param sessionId The session ID
     * @param deleteChunks Whether to also delete chunk files (default true)
     * @return true if deletion was successful
     */
    suspend fun deleteSession(sessionId: String, deleteChunks: Boolean = true): Boolean = mutex.withLock {
        try {
            // Delete chunk files if requested and ChunkStorage is available
            if (deleteChunks && chunkStorage != null) {
                chunkStorage.deleteSessionChunks(sessionId)
            }

            // Delete metadata file
            val file = File(sessionsDir, "$sessionId$JSON_EXTENSION")
            val deleted = !file.exists() || file.delete()

            // Also delete any temp file
            val tempFile = File(sessionsDir, "$sessionId$JSON_EXTENSION$TEMP_EXTENSION")
            if (tempFile.exists()) {
                tempFile.delete()
            }

            if (deleted) removeSessionInMemory(sessionId)

            Log.d(TAG, "Deleted session $sessionId: $deleted")
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete session $sessionId", e)
            false
        }
    }

    /**
     * Deletes multiple sessions.
     *
     * @param sessionIds List of session IDs to delete
     * @param deleteChunks Whether to also delete chunk files
     * @return Number of sessions successfully deleted
     */
    suspend fun deleteSessions(sessionIds: List<String>, deleteChunks: Boolean = true): Int {
        var deletedCount = 0

        for (sessionId in sessionIds) {
            if (deleteSession(sessionId, deleteChunks)) {
                deletedCount++
            }
        }

        return deletedCount
    }

    /**
     * Gets the chunk with the given ID from any session.
     *
     * @param chunkId The chunk ID
     * @return Pair of session ID and chunk, or null if not found
     */
    suspend fun findChunk(chunkId: String): Pair<String, Chunk>? = withContext(Dispatchers.IO) {
        loadSessionsFromDisk().forEach { session ->
            session.chunks.find { it.id == chunkId }?.let { chunk ->
                return@withContext Pair(session.id, chunk)
            }
        }
        null
    }

    /**
     * Check if a session exists.
     *
     * @param sessionId The session ID
     * @return true if the session exists
     */
    suspend fun sessionExists(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(sessionsDir, "$sessionId$JSON_EXTENSION")
        file.exists()
    }

    /**
     * Get the next chunk index for a session.
     *
     * @param sessionId The session ID
     * @return The next available chunk index
     */
    suspend fun getNextChunkIndex(sessionId: String): Int = withContext(Dispatchers.IO) {
        val session = getSession(sessionId) ?: return@withContext 0
        (session.chunks.maxOfOrNull { it.index } ?: -1) + 1
    }

    /**
     * Get session statistics.
     *
     * @return Map of statistic names to values
     */
    suspend fun getSessionStatistics(): Map<String, Any> = withContext(Dispatchers.IO) {
        val allSessions = loadSessionsFromDisk()
        val pendingSessions = allSessions.filter { it.endedAt != null && it.chunks.isNotEmpty() }
        val inProgressSessions = allSessions.filter { it.endedAt == null }
        val completedSessions = allSessions.filter { it.endedAt != null }
        val totalChunks = allSessions.sumOf { it.chunks.size }

        mapOf(
            "totalSessions" to allSessions.size,
            "pendingSessions" to pendingSessions.size,
            "inProgressSessions" to inProgressSessions.size,
            "completedSessions" to completedSessions.size,
            "totalChunks" to totalChunks
        )
    }

    /**
     * Clean up orphaned sessions (sessions with no chunks).
     *
     * @param deleteEmpty Whether to delete sessions with no chunks
     * @return Number of sessions cleaned up
     */
    suspend fun cleanupOrphanedSessions(deleteEmpty: Boolean = true): Int = withContext(Dispatchers.IO) {
        if (!deleteEmpty) return@withContext 0

        var cleanedCount = 0
        val sessions = loadSessionsFromDisk()

        for (session in sessions) {
            // Only clean up ended sessions with no chunks
            if (session.endedAt != null && session.chunks.isEmpty()) {
                if (deleteSession(session.id, deleteChunks = false)) {
                    cleanedCount++
                }
            }
        }

        Log.d(TAG, "Cleaned up $cleanedCount orphaned sessions")
        cleanedCount
    }

    private suspend fun saveSessionInternal(session: Session) {
        val file = File(sessionsDir, "${session.id}$JSON_EXTENSION")
        val dto = SessionDto.fromSession(session)
        StorageHelper.atomicWriteText(file, StorageJson.instance.encodeToString(dto))
    }

    private suspend fun loadAllSessions() {
        refreshSessions()
    }

    private suspend fun refreshSessions() {
        val sessions = loadSessionsFromDisk()
        _sessionsFlow.value = sessions
    }

    /**
     * Updates the in-memory sessions flow after a single session is saved/updated.
     * Avoids a full disk reload — the session is either replaced or appended.
     */
    private fun refreshSessionInMemory(updatedSession: Session) {
        _sessionsFlow.update { sessions ->
            val index = sessions.indexOfFirst { it.id == updatedSession.id }
            if (index >= 0) {
                sessions.toMutableList().apply { set(index, updatedSession) }
            } else {
                (sessions + updatedSession).sortedByDescending { it.startedAt }
            }
        }
    }

    /**
     * Removes a session from the in-memory flow without reloading from disk.
     */
    private fun removeSessionInMemory(sessionId: String) {
        _sessionsFlow.update { sessions ->
            sessions.filter { it.id != sessionId }
        }
    }

    private fun loadSessionsFromDisk(): List<Session> {
        return try {
            sessionsDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(JSON_EXTENSION) && !it.name.endsWith(TEMP_EXTENSION) }
                ?.mapNotNull { file ->
                    try {
                        val dto = StorageJson.instance.decodeFromString<SessionDto>(file.readText())
                        dto.toSession()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse session file: ${file.name}", e)
                        null
                    }
                }
                ?.sortedByDescending { it.startedAt }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions", e)
            emptyList()
        }
    }
}

/**
 * DTO for session serialization.
 */
@Serializable
private data class SessionDto(
    val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val chunks: List<ChunkDto> = emptyList(),
    val contexts: List<CapturedContextDto> = emptyList()
) {
    fun toSession(): Session = Session(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        chunks = chunks.map { it.toChunk() },
        contexts = contexts.mapNotNull { it.toCapturedContext() }
    )

    companion object {
        fun fromSession(session: Session): SessionDto = SessionDto(
            id = session.id,
            startedAt = session.startedAt,
            endedAt = session.endedAt,
            chunks = session.chunks.map { ChunkDto.fromChunk(it) },
            contexts = session.contexts.map { CapturedContextDto.from(it) }
        )
    }
}

/**
 * DTO for chunk serialization.
 */
@Serializable
private data class ChunkDto(
    val id: String,
    val sessionId: String,
    val index: Int,
    val filePath: String,
    val timestampSeconds: Float,
    val createdAt: Long,
    val isCorrupted: Boolean = false
) {
    fun toChunk(): Chunk = Chunk(
        id = id,
        sessionId = sessionId,
        index = index,
        filePath = filePath,
        timestampSeconds = timestampSeconds,
        createdAt = createdAt,
        isCorrupted = isCorrupted
    )

    companion object {
        fun fromChunk(chunk: Chunk): ChunkDto = ChunkDto(
            id = chunk.id,
            sessionId = chunk.sessionId,
            index = chunk.index,
            filePath = chunk.filePath,
            timestampSeconds = chunk.timestampSeconds,
            createdAt = chunk.createdAt,
            isCorrupted = chunk.isCorrupted
        )
    }
}

/**
 * DTO for captured context serialization.
 */
@Serializable
private data class CapturedContextDto(
    val type: String,
    val id: String,
    val timestamp: Long,
    val text: String? = null,
    val boundsLeft: Int? = null,
    val boundsTop: Int? = null,
    val boundsRight: Int? = null,
    val boundsBottom: Int? = null,
    val sourceApp: String? = null,
    val sourceUrl: String? = null,
    val pageTitle: String? = null,
    val imagePath: String? = null,
    val description: String? = null
) {
    fun toCapturedContext(): CapturedContext? {
        return when (type) {
            "selected_text" -> CapturedContext.SelectedText(
                id = id,
                timestamp = timestamp,
                text = text ?: return null,
                sourceApp = sourceApp,
                sourceUrl = sourceUrl
            )
            "region_text" -> CapturedContext.RegionText(
                id = id,
                timestamp = timestamp,
                text = text ?: return null,
                bounds = android.graphics.Rect(
                    boundsLeft ?: 0, boundsTop ?: 0,
                    boundsRight ?: 0, boundsBottom ?: 0
                )
            )
            "region_image" -> CapturedContext.RegionImage(
                id = id,
                timestamp = timestamp,
                imagePath = imagePath ?: return null,
                bounds = android.graphics.Rect(
                    boundsLeft ?: 0, boundsTop ?: 0,
                    boundsRight ?: 0, boundsBottom ?: 0
                ),
                description = description
            )
            "auto_context" -> CapturedContext.AutoContext(
                id = id,
                timestamp = timestamp,
                sourceApp = sourceApp ?: return null,
                sourceUrl = sourceUrl,
                pageTitle = pageTitle
            )
            else -> null
        }
    }

    companion object {
        fun from(context: CapturedContext): CapturedContextDto = when (context) {
            is CapturedContext.SelectedText -> CapturedContextDto(
                type = "selected_text",
                id = context.id,
                timestamp = context.timestamp,
                text = context.text,
                sourceApp = context.sourceApp,
                sourceUrl = context.sourceUrl
            )
            is CapturedContext.RegionText -> CapturedContextDto(
                type = "region_text",
                id = context.id,
                timestamp = context.timestamp,
                text = context.text,
                boundsLeft = context.bounds.left,
                boundsTop = context.bounds.top,
                boundsRight = context.bounds.right,
                boundsBottom = context.bounds.bottom
            )
            is CapturedContext.RegionImage -> CapturedContextDto(
                type = "region_image",
                id = context.id,
                timestamp = context.timestamp,
                imagePath = context.imagePath,
                boundsLeft = context.bounds.left,
                boundsTop = context.bounds.top,
                boundsRight = context.bounds.right,
                boundsBottom = context.bounds.bottom,
                description = context.description
            )
            is CapturedContext.AutoContext -> CapturedContextDto(
                type = "auto_context",
                id = context.id,
                timestamp = context.timestamp,
                sourceApp = context.sourceApp,
                sourceUrl = context.sourceUrl,
                pageTitle = context.pageTitle
            )
        }
    }
}
