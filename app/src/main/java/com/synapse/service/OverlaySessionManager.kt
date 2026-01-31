package com.synapse.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.compose.runtime.MutableState
import com.synapse.data.repository.ChunkRepository
import com.synapse.data.repository.SessionRepository
import com.synapse.model.CapturedContext
import com.synapse.ui.ContextHolder
import com.synapse.ui.overlay.CapturedChunk
import com.synapse.ui.overlay.CaptureViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Manages session lifecycle and chunk/context persistence for the overlay capture system.
 *
 * Extracted from OverlayService to separate session management concerns from
 * Android service and window management responsibilities.
 */
class OverlaySessionManager(
    private val context: Context,
    private val sessionRepository: SessionRepository,
    private val chunkRepository: ChunkRepository,
    private val screenshotManager: ScreenshotManager,
    private val captureViewModel: CaptureViewModel,
    private val scope: CoroutineScope,
    private val onBadgeUpdate: (Int) -> Unit,
    private val onOpenReview: () -> Unit,
    private val onHideOverlay: () -> Unit,
    private val onRefreshOverlay: () -> Unit,
    private val onRequestPermission: () -> Unit
) {

    companion object {
        private const val TAG = "OverlaySessionManager"
    }

    /** Number of pending chunks saved in the current session. */
    var pendingChunkCount: Int = 0

    /** Preview text shown after a region text or selected text capture. Shared with CaptureOverlayManager. */
    val capturedTextPreview: MutableState<String?>

    // Current active session ID
    private var currentSessionId: String? = null
    private val sessionMutex = Mutex()

    init {
        capturedTextPreview = androidx.compose.runtime.mutableStateOf(null)
    }

    /**
     * Saves a captured chunk (handwriting bitmap) to the current session.
     * Creates a new session if one does not exist yet.
     */
    fun saveChunk(capturedChunk: CapturedChunk) {
        scope.launch(Dispatchers.IO) {
            try {
                // Create session if not exists (synchronized)
                sessionMutex.withLock {
                    if (currentSessionId == null) {
                        val session = sessionRepository.createSession()
                        currentSessionId = session.id
                        Log.d(TAG, "Created new session: ${session.id}")
                    }
                }

                val sessionId = currentSessionId ?: return@launch

                // Calculate timestamp in seconds from epoch
                val timestampSeconds = capturedChunk.timestamp / 1000f

                // Save chunk image and metadata
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

                // Update badge count
                launch(Dispatchers.Main) {
                    pendingChunkCount++
                    onBadgeUpdate(pendingChunkCount)
                }
            } catch (e: IOException) {
                Log.e(TAG, "IO error saving chunk", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Invalid state saving chunk", e)
            }
        }
    }

    /**
     * Handles a region selection from the capture canvas.
     * Extracts text via AccessibilityService and saves it as a context on the session.
     * Falls back to screenshot capture via MediaProjection if no text is found.
     * Auto-returns to write mode after selection.
     */
    fun handleRegionSelected(region: Rect) {
        Log.d(TAG, "Region selected: $region")
        scope.launch(Dispatchers.IO) {
            try {
                // Create session if needed (synchronized)
                sessionMutex.withLock {
                    if (currentSessionId == null) {
                        val session = sessionRepository.createSession()
                        currentSessionId = session.id
                        Log.d(TAG, "Created new session for region select: ${session.id}")
                    }
                }
                val sessionId = currentSessionId ?: return@launch

                // Try text extraction via accessibility service first
                var regionText: CapturedContext.RegionText? = null
                try {
                    regionText = SynapseAccessibilityService.getInstance()
                        ?.getTextInRegion(region)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Text extraction denied", e)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Text extraction failed, will try screenshot", e)
                }

                if (regionText != null && regionText.text.isNotBlank()) {
                    sessionRepository.addContext(sessionId, regionText)
                    Log.d(TAG, "Saved region text context: ${regionText.text.take(80)}")

                    val preview = regionText.text.take(60).let {
                        if (regionText.text.length > 60) "$it..." else it
                    }
                    capturedTextPreview.value = preview
                } else {
                    // Fallback: capture screenshot via MediaProjection
                    Log.d(TAG, "No text found, attempting screenshot fallback")
                    if (screenshotManager.hasPermission()) {
                        val bitmap = screenshotManager.captureRegion(region)
                        if (bitmap != null) {
                            try {
                            Log.d(TAG, "Screenshot captured: ${bitmap.width}x${bitmap.height}")
                            val imagePath = saveScreenshot(bitmap)
                            if (imagePath != null) {
                                val imageContext = CapturedContext.RegionImage(
                                    imagePath = imagePath,
                                    bounds = region,
                                    description = null
                                )
                                sessionRepository.addContext(sessionId, imageContext)
                                capturedTextPreview.value = "[Screenshot captured]"
                            } else {
                                capturedTextPreview.value = "[Failed to save screenshot]"
                            }
                            } finally {
                                bitmap.recycle()
                            }
                        } else {
                            Log.w(TAG, "Screenshot capture returned null — projection may be dead")
                            // captureRegion already called invalidateProjection() if VD setup failed
                            if (!screenshotManager.hasPermission()) {
                                Log.d(TAG, "Projection invalidated, requesting permission again")
                                capturedTextPreview.value = "[Re-requesting screen permission...]"
                                withContext(Dispatchers.Main) {
                                    onHideOverlay()
                                    onRequestPermission()
                                }
                            } else {
                                capturedTextPreview.value = "[Screenshot failed]"
                            }
                        }
                    } else {
                        Log.d(TAG, "No screenshot permission, requesting it now")
                        withContext(Dispatchers.Main) {
                            onHideOverlay()
                            onRequestPermission()
                        }
                    }
                }

                // Auto-switch back to write mode
                withContext(Dispatchers.Main) {
                    onRefreshOverlay()
                }
            } catch (e: IOException) {
                Log.e(TAG, "IO error capturing region", e)
                capturedTextPreview.value = "[Selection failed]"
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied capturing region", e)
                capturedTextPreview.value = "[Selection failed]"
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Invalid state capturing region", e)
                capturedTextPreview.value = "[Selection failed]"
                withContext(Dispatchers.Main) {
                    onRefreshOverlay()
                }
            }
        }
    }

    /**
     * Saves a bitmap screenshot to the app's internal screenshots directory.
     * Returns the absolute file path on success, or null on failure.
     */
    fun saveScreenshot(bitmap: Bitmap): String? {
        return try {
            val dir = File(context.filesDir, "screenshots")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "region_${UUID.randomUUID()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            Log.d(TAG, "Screenshot saved to ${file.absolutePath}")
            file.absolutePath
        } catch (e: IOException) {
            Log.e(TAG, "IO error saving screenshot", e)
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied saving screenshot", e)
            null
        }
    }

    /**
     * Consumes pending context from ProcessTextActivity (text selection from other apps)
     * and attaches it to the current or a new session.
     */
    fun handlePendingContext() {
        val pendingContext = ContextHolder.consumeContext() ?: return
        Log.d(TAG, "Consuming pending context: ${pendingContext::class.simpleName}")

        scope.launch(Dispatchers.IO) {
            try {
                if (currentSessionId == null) {
                    val session = sessionRepository.createSession()
                    currentSessionId = session.id
                    Log.d(TAG, "Created new session for pending context: ${session.id}")
                }
                val sessionId = currentSessionId ?: return@launch
                sessionRepository.addContext(sessionId, pendingContext)
                Log.d(TAG, "Added pending context to session $sessionId")

                if (pendingContext is CapturedContext.SelectedText) {
                    val preview = pendingContext.text.take(60).let {
                        if (pendingContext.text.length > 60) "$it..." else it
                    }
                    capturedTextPreview.value = preview
                }
            } catch (e: IOException) {
                Log.e(TAG, "IO error adding pending context", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Invalid state adding pending context", e)
            }
        }
    }

    /**
     * Deletes the most recent scribble (chunk) or captured image/text (context) from the session.
     * If there are unsaved strokes on the canvas, clears those first.
     * If the session becomes empty after deletion, discards the session entirely.
     */
    fun deleteLastSessionItem() {
        // If there are unsaved strokes on the canvas, just clear them
        if (captureViewModel.hasStrokes()) {
            captureViewModel.clearStrokes()
            Log.d(TAG, "Cleared unsaved strokes from canvas")
            return
        }

        val sessionId = currentSessionId
        if (sessionId == null) {
            Log.d(TAG, "No active session, nothing to delete")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val session = sessionRepository.getSession(sessionId) ?: return@launch

                // Find the latest item by timestamp
                val lastChunk = session.chunks.maxByOrNull { it.createdAt }
                val lastContext = session.contexts.maxByOrNull { it.timestamp }

                val chunkTs = lastChunk?.createdAt ?: 0L
                val contextTs = lastContext?.timestamp ?: 0L

                if (chunkTs == 0L && contextTs == 0L) {
                    Log.d(TAG, "Session is empty, discarding")
                    currentSessionId = null
                    return@launch
                }

                if (chunkTs >= contextTs && lastChunk != null) {
                    // Delete the last chunk
                    sessionRepository.deleteChunk(sessionId, lastChunk.id)
                    Log.d(TAG, "Deleted last chunk: ${lastChunk.id}")
                    launch(Dispatchers.Main) {
                        if (pendingChunkCount > 0) {
                            pendingChunkCount--
                            onBadgeUpdate(pendingChunkCount)
                        }
                    }
                } else if (lastContext != null) {
                    // Delete the last context
                    sessionRepository.removeContext(sessionId, lastContext.id)
                    Log.d(TAG, "Deleted last context: ${lastContext.id}")
                }

                // Check if session is now empty — if so, discard it
                val updated = sessionRepository.getSession(sessionId)
                if (updated != null && updated.chunks.isEmpty() && updated.contexts.isEmpty()) {
                    sessionRepository.deleteSession(sessionId)
                    currentSessionId = null
                    Log.d(TAG, "Session now empty, discarded")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete last session item", e)
            }
        }
    }

    /**
     * Ends the current session without opening review.
     * The session is finalized in the repository and the session ID is cleared.
     */
    fun endCurrentSession() {
        val sessionId = currentSessionId ?: return
        scope.launch(Dispatchers.IO) {
            try {
                sessionRepository.endSession(sessionId)
                Log.d(TAG, "Ended session: $sessionId")
                currentSessionId = null
                // Don't clean up screenshots here — they're needed by SyncRepository
                // Cleanup happens after sync in finishSessionAndOpenReview or on next service start
            } catch (e: IOException) {
                Log.e(TAG, "IO error ending session", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Invalid state ending session", e)
            }
        }
    }

    /**
     * Ends the session and opens Review screen after session is saved.
     *
     * Captures any remaining strokes synchronously on the Main thread,
     * then saves the chunk and ends the session sequentially on IO to
     * avoid race conditions with the async event system.
     */
    fun finishSessionAndOpenReview() {
        // Capture remaining strokes synchronously BEFORE ending session.
        // This returns the bitmap directly instead of going through the async
        // ChunkCaptured event, preventing a race where the session is ended
        // before the final chunk is saved.
        val pendingChunk = captureViewModel.captureRemainingStrokes()

        // End UI session (strokes already cleared, so no ChunkCaptured event emitted)
        captureViewModel.endSession()

        // Reset badge count since user is going to review
        pendingChunkCount = 0
        onBadgeUpdate(0)

        scope.launch(Dispatchers.IO) {
            try {
                // Ensure session exists for the pending chunk
                if (pendingChunk != null) {
                    sessionMutex.withLock {
                        if (currentSessionId == null) {
                            val session = sessionRepository.createSession()
                            currentSessionId = session.id
                            Log.d(TAG, "Created session for final chunk: ${session.id}")
                        }
                    }
                }

                val sessionId = currentSessionId

                // Save pending chunk to the correct session
                if (pendingChunk != null) {
                    try {
                        if (sessionId != null) {
                            val timestampSeconds = pendingChunk.timestamp / 1000f
                            val chunk = chunkRepository.saveChunk(
                                sessionId = sessionId,
                                bitmap = pendingChunk.bitmap,
                                timestampSeconds = timestampSeconds
                            )
                            Log.d(TAG, "Saved final chunk: ${chunk.id} to session $sessionId")
                        } else {
                            Log.w(TAG, "No session for pending chunk, discarding")
                        }
                    } finally {
                        pendingChunk.bitmap.recycle()
                    }
                }

                // End session AFTER all chunks are saved
                if (sessionId != null) {
                    sessionRepository.endSession(sessionId)
                    Log.d(TAG, "Ended session: $sessionId")
                    currentSessionId = null
                }

                // Don't clean up screenshots here — sync hasn't happened yet.
                // SyncRepository will clean up after sync completes.
            } catch (e: IOException) {
                Log.e(TAG, "IO error ending session for review", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Invalid state ending session for review", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error ending session for review", e)
            }

            // Hide overlay and open review on main thread after session is saved
            // Small delay to let ripple animation finish
            launch(Dispatchers.Main) {
                delay(50)
                onHideOverlay()
                onOpenReview()
            }
        }
    }

    /**
     * Triggers a short haptic vibration for region selection feedback.
     */
    fun vibrateForRegionSelection() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(50)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Vibration permission denied", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Vibrator not available", e)
        }
    }
}
