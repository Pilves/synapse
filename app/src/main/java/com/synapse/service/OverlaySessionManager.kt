package com.synapse.service

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.synapse.data.repository.ChunkRepository
import com.synapse.data.repository.SessionRepository
import com.synapse.model.CapturedContext
import com.synapse.ui.overlay.CapturedChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages session lifecycle for OverlayService.
 *
 * Handles session creation, chunk saving, context capture,
 * and session ending/cleanup.
 */
class OverlaySessionManager(
    private val sessionRepository: SessionRepository,
    private val chunkRepository: ChunkRepository,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "OverlaySessionManager"
    }

    val currentSessionId = AtomicReference<String?>(null)
    private val sessionMutex = Mutex()

    /**
     * Ensures a session exists and saves a captured chunk to it.
     */
    fun saveChunk(capturedChunk: CapturedChunk, onChunkSaved: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                sessionMutex.withLock {
                    if (currentSessionId.get() == null) {
                        val session = sessionRepository.createSession()
                        currentSessionId.set(session.id)
                        Log.d(TAG, "Created new session: ${session.id}")
                    }
                }

                val sessionId = currentSessionId.get() ?: return@launch
                val timestampSeconds = capturedChunk.timestamp / 1000f

                try {
                    val chunk = chunkRepository.saveChunk(
                        sessionId = sessionId,
                        bitmap = capturedChunk.bitmap,
                        timestampSeconds = timestampSeconds
                    )
                    Log.d(TAG, "Saved chunk: ${chunk.id} to session $sessionId")
                } finally {
                    capturedChunk.bitmap.recycle()
                }

                launch(Dispatchers.Main) { onChunkSaved() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save chunk", e)
            }
        }
    }

    /**
     * Adds a captured context to the current (or new) session.
     */
    suspend fun addContext(context: CapturedContext) {
        sessionMutex.withLock {
            if (currentSessionId.get() == null) {
                val session = sessionRepository.createSession()
                currentSessionId.set(session.id)
                Log.d(TAG, "Created new session for context: ${session.id}")
            }
        }
        val sessionId = currentSessionId.get() ?: return
        sessionRepository.addContext(sessionId, context)
        Log.d(TAG, "Added context to session $sessionId")
    }

    /**
     * Ends the current session.
     */
    fun endCurrentSession(onComplete: (() -> Unit)? = null) {
        val sessionId = currentSessionId.get() ?: return
        scope.launch(Dispatchers.IO) {
            try {
                sessionRepository.endSession(sessionId)
                Log.d(TAG, "Ended session: $sessionId")
                currentSessionId.set(null)
                onComplete?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to end session", e)
            }
        }
    }

    /**
     * Discards the current session without ending it.
     */
    fun discardSession() {
        currentSessionId.set(null)
    }
}
