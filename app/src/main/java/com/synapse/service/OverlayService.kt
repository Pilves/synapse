package com.synapse.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.synapse.R
import com.synapse.SynapseApplication
import com.synapse.data.repository.ChunkRepository
import com.synapse.data.repository.SessionRepository
import com.synapse.model.CapturedContext
import com.synapse.ui.MainActivity
import com.synapse.ui.overlay.CaptureCanvas
import com.synapse.ui.overlay.CaptureEvent
import com.synapse.ui.overlay.CaptureViewModel
import com.synapse.ui.overlay.InputMode
import com.synapse.ui.overlay.PalmRejectionFilter
import com.synapse.ui.theme.SynapseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.android.ext.android.inject
import android.view.Choreographer
import android.widget.Toast
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.synapse.ui.settings.settingsDataStore

/**
 * Foreground service that manages the floating overlay capture system.
 *
 * This service provides:
 * - A floating bubble that can be tapped to start capture
 * - A fullscreen transparent canvas for handwriting capture
 * - Stylus writes, finger passes through (for scrolling underlying apps)
 */
class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var floatingBubbleView: View? = null
    private var bubbleBadgeView: android.widget.TextView? = null
    private var bubbleWarningView: View? = null
    private var bubbleIconView: android.widget.ImageView? = null
    private var captureOverlayView: View? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    private var captureViewModel: CaptureViewModel? = null
    private var pendingChunkCount = 0
    private var isCaptureActive = false
    private var isRegionMode = false
    private var capturedTextPreview = mutableStateOf<String?>(null)
    private var showHealthWarning = false

    // Repositories for saving sessions and chunks
    private val sessionRepository: SessionRepository by inject()
    private val chunkRepository: ChunkRepository by inject()

    // Screenshot manager for media projection handling
    private val screenshotManager: ScreenshotManager by inject()

    // Capability detection
    private val capabilities: SynapseCapabilities by inject()

    // Permission health monitoring
    private val permissionHealthMonitor: PermissionHealthMonitor by inject()

    // Coroutine scope for the service
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Current active session ID
    private val currentSessionId = AtomicReference<String?>(null)
    private val sessionMutex = Mutex()

    // Settings keys
    private val chunkTimeoutKey = floatPreferencesKey("chunk_timeout_seconds")
    private val bubbleXKey = intPreferencesKey("bubble_position_x")
    private val bubbleYKey = intPreferencesKey("bubble_position_y")

    // Cached settings values (read when overlay opens)
    private var chunkTimeoutMs: Long = 1000L

    // Last known bubble position (used to anchor the toolbar)
    private var lastBubbleX = 100
    private var lastBubbleY = 300

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        captureViewModel = CaptureViewModel()

        // Start permission health monitoring
        permissionHealthMonitor.startMonitoring()
        serviceScope.launch {
            permissionHealthMonitor.health.collect { health ->
                val warning = health != PermissionHealthMonitor.PermissionHealth.HEALTHY
                showHealthWarning = warning
                updateBubbleWarning(warning)
            }
        }

        // Collect capture events and save chunks
        serviceScope.launch {
            captureViewModel?.events?.collect { event ->
                when (event) {
                    is CaptureEvent.ChunkCaptured -> {
                        Log.d(TAG, "Chunk captured: index=${event.chunk.index}")
                        saveChunk(event.chunk)
                    }
                    is CaptureEvent.SessionEnded -> {
                        Log.d(TAG, "Session ended")
                        endCurrentSession()
                    }
                    is CaptureEvent.SessionTimeout -> {
                        Log.d(TAG, "Session timeout — ending session and closing overlay")
                        endCurrentSession()
                        withContext(Dispatchers.Main) { hideCaptureOverlay() }
                    }
                    is CaptureEvent.Error -> {
                        Log.e(TAG, "Capture error: ${event.message}")
                    }
                }
            }
        }
    }

    private fun saveChunk(capturedChunk: com.synapse.ui.overlay.CapturedChunk) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Create session if not exists (synchronized)
                sessionMutex.withLock {
                    if (currentSessionId.get() == null) {
                        val session = sessionRepository.createSession()
                        currentSessionId.set(session.id)
                        Log.d(TAG, "Created new session: ${session.id}")
                    }
                }

                val sessionId = currentSessionId.get() ?: return@launch

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
                    updateBubbleBadge(pendingChunkCount)
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
     * Auto-returns to write mode after selection.
     */
    private fun handleRegionSelected(region: Rect) {
        Log.d(TAG, "Region selected: $region")
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Create session if needed (synchronized)
                sessionMutex.withLock {
                    if (currentSessionId.get() == null) {
                        val session = sessionRepository.createSession()
                        currentSessionId.set(session.id)
                        Log.d(TAG, "Created new session for region select: ${session.id}")
                    }
                }
                val sessionId = currentSessionId.get() ?: return@launch

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
                        } else {
                            Log.w(TAG, "Screenshot capture returned null — projection may be dead")
                            // captureRegion already called invalidateProjection() if VD setup failed
                            if (!screenshotManager.hasPermission()) {
                                Log.d(TAG, "Projection invalidated, requesting permission again")
                                capturedTextPreview.value = "[Re-requesting screen permission...]"
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    hideCaptureOverlay()
                                    requestScreenCapturePermission()
                                }
                            } else {
                                capturedTextPreview.value = "[Screenshot failed]"
                            }
                        }
                    } else {
                        Log.d(TAG, "No screenshot permission, requesting it now")
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            hideCaptureOverlay()
                            requestScreenCapturePermission()
                        }
                    }
                }

                // Auto-switch back to write mode
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    isRegionMode = false
                    refreshCaptureOverlay()
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
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    isRegionMode = false
                    refreshCaptureOverlay()
                }
            }
        }
    }

    private fun saveScreenshot(bitmap: android.graphics.Bitmap): String? {
        return try {
            val dir = java.io.File(filesDir, "screenshots")
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, "region_${java.util.UUID.randomUUID()}.png")
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
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
    private fun handlePendingContext() {
        val context = com.synapse.ui.ContextHolder.consumeContext() ?: return
        Log.d(TAG, "Consuming pending context: ${context::class.simpleName}")

        serviceScope.launch(Dispatchers.IO) {
            try {
                if (currentSessionId.get() == null) {
                    val session = sessionRepository.createSession()
                    currentSessionId.set(session.id)
                    Log.d(TAG, "Created new session for pending context: ${session.id}")
                }
                val sessionId = currentSessionId.get() ?: return@launch
                sessionRepository.addContext(sessionId, context)
                Log.d(TAG, "Added pending context to session $sessionId")

                if (context is com.synapse.model.CapturedContext.SelectedText) {
                    val preview = context.text.take(60).let {
                        if (context.text.length > 60) "$it..." else it
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
     * Triggers a short haptic vibration for region selection feedback.
     */
    private fun vibrateForRegionSelection() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
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

    /**
     * Deletes the most recent scribble (chunk) or captured image/text (context) from the session.
     * If there are unsaved strokes on the canvas, clears those first.
     * If the session becomes empty after deletion, discards the session entirely.
     */
    private fun deleteLastSessionItem() {
        // If there are unsaved strokes on the canvas, just clear them
        val vm = captureViewModel
        if (vm != null && vm.hasStrokes()) {
            vm.clearStrokes()
            Log.d(TAG, "Cleared unsaved strokes from canvas")
            return
        }

        val sessionId = currentSessionId.get()
        if (sessionId == null) {
            Log.d(TAG, "No active session, nothing to delete")
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val session = sessionRepository.getSession(sessionId) ?: return@launch

                // Find the latest item by timestamp
                val lastChunk = session.chunks.maxByOrNull { it.createdAt }
                val lastContext = session.contexts.maxByOrNull { it.timestamp }

                val chunkTs = lastChunk?.createdAt ?: 0L
                val contextTs = lastContext?.timestamp ?: 0L

                if (chunkTs == 0L && contextTs == 0L) {
                    Log.d(TAG, "Session is empty, discarding")
                    currentSessionId.set(null)
                    return@launch
                }

                if (chunkTs >= contextTs && lastChunk != null) {
                    // Delete the last chunk
                    sessionRepository.deleteChunk(sessionId, lastChunk.id)
                    Log.d(TAG, "Deleted last chunk: ${lastChunk.id}")
                    launch(Dispatchers.Main) {
                        if (pendingChunkCount > 0) {
                            pendingChunkCount--
                            updateBubbleBadge(pendingChunkCount)
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
                    currentSessionId.set(null)
                    Log.d(TAG, "Session now empty, discarded")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete last session item", e)
            }
        }
    }

    private fun endCurrentSession() {
        val sessionId = currentSessionId.get() ?: return
        serviceScope.launch(Dispatchers.IO) {
            try {
                sessionRepository.endSession(sessionId)
                Log.d(TAG, "Ended session: $sessionId")
                currentSessionId.set(null)
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
    private fun finishSessionAndOpenReview() {
        // Capture remaining strokes synchronously BEFORE ending session.
        // This returns the bitmap directly instead of going through the async
        // ChunkCaptured event, preventing a race where the session is ended
        // before the final chunk is saved.
        val pendingChunk = captureViewModel?.captureRemainingStrokes()

        // End UI session (strokes already cleared, so no ChunkCaptured event emitted)
        captureViewModel?.endSession()

        // Reset badge count since user is going to review
        pendingChunkCount = 0
        updateBubbleBadge(0)

        serviceScope.launch(Dispatchers.IO) {
            try {
                // Ensure session exists for the pending chunk
                if (pendingChunk != null) {
                    sessionMutex.withLock {
                        if (currentSessionId.get() == null) {
                            val session = sessionRepository.createSession()
                            currentSessionId.set(session.id)
                            Log.d(TAG, "Created session for final chunk: ${session.id}")
                        }
                    }
                }

                val sessionId = currentSessionId.get()

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
                    currentSessionId.set(null)
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
                kotlinx.coroutines.delay(50)
                hideCaptureOverlay()
                openReviewScreen()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startOverlay(userInitiated = true)
            null -> {
                // Service restarted by system (START_STICKY) — re-initialize
                Log.d(TAG, "Service restarted by system, re-initializing overlay")
                startOverlay(userInitiated = false)
            }
            ACTION_STOP -> stopOverlay()
            ACTION_SHOW_CAPTURE -> showCaptureOverlay()
            ACTION_HIDE_CAPTURE -> hideCaptureOverlay()
            ACTION_UPDATE_BADGE -> {
                pendingChunkCount = intent.getIntExtra(EXTRA_CHUNK_COUNT, 0)
                updateBubbleBadge(pendingChunkCount)
            }
            ACTION_TOGGLE_REGION_MODE -> {
                // Check if accessibility is available for text extraction
                if (!isRegionMode && !capabilities.canExtractText) {
                    Log.w(TAG, "Region mode toggled without accessibility — text extraction unavailable, screenshot fallback only")
                }
                isRegionMode = !isRegionMode
                Log.d(TAG, "Region capture mode: $isRegionMode")
                // Request screen capture permission if entering region mode without it
                if (isRegionMode && !screenshotManager.hasPermission()) {
                    Log.d(TAG, "Requesting screen capture permission for region mode")
                    requestScreenCapturePermission()
                }
                // Re-show overlay to apply the mode change
                if (isCaptureActive) {
                    refreshCaptureOverlay()
                }
            }
            ACTION_SET_MEDIA_PROJECTION -> {
                handleMediaProjectionResult(intent)
            }
            ACTION_SHOW_WITH_CONTEXT -> {
                handlePendingContext()
                showCaptureOverlay()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        permissionHealthMonitor.stopMonitoring()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceScope.cancel()
        hideCaptureOverlay()
        hideFloatingBubble()
        screenshotManager.releaseProjection()
        super.onDestroy()
    }

    private fun startOverlay(userInitiated: Boolean = false) {
        Log.d(TAG, "startOverlay called (userInitiated=$userInitiated)")

        // Check overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission not granted!")
            return
        }
        Log.d(TAG, "Overlay permission OK")

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val notification = createNotification()
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        Log.d(TAG, "Started foreground service")
        showFloatingBubble()

        // Request screen capture permission when user explicitly starts the overlay
        // (e.g. from Review screen), but not on system restarts
        if (userInitiated) {
            if (!screenshotManager.hasPermission()) {
                Log.d(TAG, "No screen capture permission, requesting on user-initiated start")
                requestScreenCapturePermission()
            } else if (!ensureProjectionReady()) {
                Log.w(TAG, "Projection stale, re-requesting on user-initiated start")
                screenshotManager.invalidateProjection()
                requestScreenCapturePermission()
            }
        } else {
            // System restart: silently try to restore, don't prompt
            if (screenshotManager.hasPermission()) {
                ensureProjectionReady()
            }
        }
    }

    /**
     * Handles media projection result from MainActivity.
     * Upgrades the foreground service type to include mediaProjection,
     * then creates the MediaProjection (required on Android 14+).
     */
    private fun handleMediaProjectionResult(intent: Intent?) {
        val resultCode = intent?.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0) ?: return
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        } ?: return

        try {
            // Upgrade foreground service type to include mediaProjection
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }

            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, data)
            if (projection != null) {
                screenshotManager.setMediaProjection(projection)
                Log.d(TAG, "MediaProjection set from service")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied setting media projection", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Invalid state setting media projection", e)
        }
    }

    /**
     * Upgrades the foreground service type and restores the MediaProjection.
     * Must be called from the service since only the service can upgrade its type.
     */
    private fun ensureProjectionReady(): Boolean {
        if (screenshotManager.hasPermission() && !MediaProjectionHolder.hasResult()) {
            // Projection already set directly, no restore needed
            return true
        }
        if (!MediaProjectionHolder.hasResult()) return false

        try {
            // Upgrade foreground service type to include mediaProjection
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                Log.d(TAG, "Upgraded foreground service type for media projection")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied upgrading foreground service type", e)
            return false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Invalid state upgrading foreground service type", e)
            return false
        }

        return screenshotManager.tryRestoreProjection()
    }

    private fun stopOverlay() {
        // Delay to let ripple animation finish before removing views
        serviceScope.launch {
            kotlinx.coroutines.delay(100)
            hideCaptureOverlay()
            hideFloatingBubble()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingBubble() {
        Log.d(TAG, "showFloatingBubble called, existing view: ${floatingBubbleView != null}")
        if (floatingBubbleView != null) return

        // Load saved bubble position (or use defaults)
        var savedX = 100
        var savedY = 300
        try {
            val prefs = kotlinx.coroutines.runBlocking {
                settingsDataStore.data.first()
            }
            savedX = prefs[bubbleXKey] ?: 100
            savedY = prefs[bubbleYKey] ?: 300
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load bubble position, using defaults", e)
        }

        try {
        // Get screen dimensions
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density

        // Resolve colors based on current dark/light mode
        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val primaryColor = if (isDark) 0xFFD0BCFF.toInt() else 0xFF7B5EAE.toInt()
        val onPrimaryColor = if (isDark) 0xFF3E1F6E.toInt() else 0xFFFFFFFF.toInt()
        val errorColor = if (isDark) 0xFFFFB4AB.toInt() else 0xFFBA1A1A.toInt()
        val onErrorColor = if (isDark) 0xFF690005.toInt() else 0xFFFFFFFF.toInt()
        val amberColor = 0xFFFFA000.toInt()

        val bubbleSizePx = (56 * density).roundToInt()
        val badgeSizePx = (20 * density).roundToInt()
        val elevationPx = 8 * density

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }
        lastBubbleX = savedX
        lastBubbleY = savedY

        // Build the bubble view hierarchy programmatically
        val container = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                bubbleSizePx + badgeSizePx / 2,
                bubbleSizePx + badgeSizePx / 2
            )
        }

        // Circular FAB background
        val fabBackground = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(primaryColor)
        }

        val iconView = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_bubble_edit)
            setColorFilter(onPrimaryColor, android.graphics.PorterDuff.Mode.SRC_IN)
            background = fabBackground
            scaleType = android.widget.ImageView.ScaleType.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = elevationPx
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
        }
        bubbleIconView = iconView
        container.addView(iconView)

        // Badge (pending chunk count)
        val badgeView = android.widget.TextView(this).apply {
            textSize = 10f
            setTextColor(onErrorColor)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(errorColor)
            }
            visibility = if (pendingChunkCount > 0) View.VISIBLE else View.GONE
            text = if (pendingChunkCount > 99) "99+" else pendingChunkCount.toString()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = elevationPx + 1
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(badgeSizePx, badgeSizePx).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }
        bubbleBadgeView = badgeView
        container.addView(badgeView)

        // Warning dot (amber, permission degraded)
        val warningView = android.widget.TextView(this).apply {
            textSize = 10f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            text = "!"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(amberColor)
            }
            visibility = if (showHealthWarning) View.VISIBLE else View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = elevationPx + 1
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(badgeSizePx, badgeSizePx).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        }
        bubbleWarningView = warningView
        container.addView(warningView)

        // Touch handling: tap vs drag
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var isDragging = false
        var currentY = params.y.toFloat()

        // Choreographer vsync batching for drag
        var pendingDx = 0f
        var pendingDy = 0f
        var frameCallbackScheduled = false
        val choreographer = Choreographer.getInstance()

        val dismissZoneThreshold = screenHeight * 0.85f

        container.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!isDragging && (dx * dx + dy * dy > touchSlop * touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        startX = event.rawX
                        startY = event.rawY
                        currentY += dy

                        pendingDx += dx
                        pendingDy += dy
                        if (!frameCallbackScheduled) {
                            frameCallbackScheduled = true
                            choreographer.postFrameCallback {
                                frameCallbackScheduled = false
                                params.x += pendingDx.roundToInt()
                                params.y += pendingDy.roundToInt()
                                pendingDx = 0f
                                pendingDy = 0f
                                try {
                                    windowManager.updateViewLayout(container, params)
                                } catch (e: IllegalArgumentException) {
                                    Log.w(TAG, "Bubble view not attached during drag update", e)
                                }
                            }
                        }

                        // Update appearance when in/out of dismiss zone
                        val inDismissZone = currentY > dismissZoneThreshold
                        val bg = iconView.background as android.graphics.drawable.GradientDrawable
                        if (inDismissZone) {
                            bg.setColor(errorColor)
                            iconView.setImageResource(R.drawable.ic_bubble_close)
                            iconView.setColorFilter(onErrorColor, android.graphics.PorterDuff.Mode.SRC_IN)
                            badgeView.visibility = View.GONE
                            warningView.visibility = View.GONE
                        } else {
                            bg.setColor(primaryColor)
                            iconView.setImageResource(R.drawable.ic_bubble_edit)
                            iconView.setColorFilter(onPrimaryColor, android.graphics.PorterDuff.Mode.SRC_IN)
                            if (pendingChunkCount > 0) badgeView.visibility = View.VISIBLE
                            if (showHealthWarning) warningView.visibility = View.VISIBLE
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Tap — open capture overlay
                        showCaptureOverlay()
                    } else {
                        // Drag ended
                        if (currentY > dismissZoneThreshold) {
                            stopOverlay()
                        } else {
                            // Track position for toolbar anchoring
                            lastBubbleX = params.x
                            lastBubbleY = params.y
                            // Persist bubble position
                            serviceScope.launch(Dispatchers.IO) {
                                try {
                                    settingsDataStore.edit { prefs ->
                                        prefs[bubbleXKey] = params.x
                                        prefs[bubbleYKey] = params.y
                                    }
                                } catch (e: IOException) {
                                    Log.w(TAG, "Failed to save bubble position", e)
                                }
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }

        // Warning dot tap opens accessibility settings
        warningView.setOnClickListener {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
            ).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }

        floatingBubbleView = container
        windowManager.addView(container, params)
            Log.d(TAG, "Floating bubble added to window manager")
        } catch (e: SecurityException) {
            Log.e(TAG, "Overlay permission denied for bubble", e)
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Bad window token showing bubble", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Invalid state showing bubble", e)
        }
    }

    private fun updateBubbleBadge(count: Int) {
        bubbleBadgeView?.let { badge ->
            if (count > 0) {
                badge.text = if (count > 99) "99+" else count.toString()
                badge.visibility = View.VISIBLE
            } else {
                badge.visibility = View.GONE
            }
        }
    }

    private fun updateBubbleWarning(show: Boolean) {
        bubbleWarningView?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun hideFloatingBubble() {
        floatingBubbleView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Bubble view not attached", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Invalid state removing bubble view", e)
            }
            floatingBubbleView = null
            bubbleBadgeView = null
            bubbleWarningView = null
            bubbleIconView = null
        }
    }


    @SuppressLint("ClickableViewAccessibility")
    private fun showCaptureOverlay() {
        if (captureOverlayView != null || isCaptureActive) return
        val vm = captureViewModel ?: return
        isCaptureActive = true

        // If screenshot permission is missing, close the overlay so the user
        // doesn't scribble on the permission dialog, then request permission.
        if (!screenshotManager.hasPermission()) {
            Log.d(TAG, "No screenshot permission on capture show, closing overlay and requesting")
            isCaptureActive = false
            hideCaptureOverlay()
            requestScreenCapturePermission()
            return
        }

        // Read settings on IO thread (use cached default until loaded)
        serviceScope.launch(Dispatchers.IO) {
            try {
                val prefs = settingsDataStore.data.first()
                val chunkTimeoutSeconds = prefs[chunkTimeoutKey] ?: 1f
                chunkTimeoutMs = (chunkTimeoutSeconds * 1000).toLong()
            } catch (e: IOException) {
                Log.e(TAG, "IO error reading settings", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Invalid state reading settings", e)
            }
        }

        // Hide bubble while capturing
        hideFloatingBubble()

        // Create the capture overlay with special touch handling
        // Strategy: Start with FLAG_NOT_TOUCHABLE so finger touches pass through.
        // When stylus hovers or touches, we remove the flag to capture stylus input.
        // After stylus leaves, we restore the flag for finger pass-through.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            // Simple flags - overlay captures all input when open
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        // Simple ComposeView - when overlay is open, all input draws
        val overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                SynapseTheme {
                    CaptureOverlayContent(
                        viewModel = vm,
                        chunkTimeoutMs = chunkTimeoutMs,
                        isRegionMode = isRegionMode,
                        initialToolbarX = lastBubbleX.toFloat(),
                        initialToolbarY = lastBubbleY.toFloat(),
                        capturedTextPreview = capturedTextPreview.value,
                        onClearPreview = { capturedTextPreview.value = null },
                        onMinimize = {
                            // Hide overlay but keep session active
                            hideCaptureOverlay()
                        },
                        onDone = {
                            finishSessionAndOpenReview()
                        },
                        onDiscard = {
                            deleteLastSessionItem()
                        },
                        onToggleRegionMode = {
                            isRegionMode = !isRegionMode
                            Log.d(TAG, "Region mode toggled: $isRegionMode")
                            refreshCaptureOverlay()
                        },
                        onRegionSelected = { rect ->
                            handleRegionSelected(rect)
                        },
                        onVibrate = {
                            vibrateForRegionSelection()
                        }
                    )
                }
            }
        }

        captureOverlayView = overlayView
        windowManager.addView(overlayView, params)

        // Auto-minimize when user navigates away (home/recents/back)
        SynapseAccessibilityService.getInstance()?.let { a11y ->
            a11y.onWindowChanged = {
                if (isCaptureActive) {
                    serviceScope.launch(Dispatchers.Main) {
                        hideCaptureOverlay()
                    }
                }
            }
            a11y.onBackPressed = {
                if (isCaptureActive) {
                    serviceScope.launch(Dispatchers.Main) {
                        hideCaptureOverlay()
                    }
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * Re-creates the capture overlay to apply mode changes (e.g. region mode toggle).
     * Preserves the active session state.
     */
    private fun refreshCaptureOverlay() {
        captureOverlayView?.let {
            windowManager.removeView(it)
            captureOverlayView = null
        }
        // Don't reset isCaptureActive - we're just refreshing
        isCaptureActive = false
        showCaptureOverlay()
    }

    private fun hideCaptureOverlay() {
        // Stop listening for navigation events while not capturing
        SynapseAccessibilityService.getInstance()?.let {
            it.onWindowChanged = null
            it.onBackPressed = null
        }

        captureOverlayView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Capture overlay view not attached", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Invalid state removing capture overlay", e)
            }
            captureOverlayView = null
        }
        isCaptureActive = false
        isRegionMode = false
        // Pause screen mirroring to save resources while not capturing
        screenshotManager.pauseCapture()
        showFloatingBubble()
    }

    /**
     * Launches MainActivity to request screen capture (MediaProjection) permission.
     * The permission can only be requested from an Activity, not from a Service.
     */
    private fun requestScreenCapturePermission() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_REQUEST_SCREEN_CAPTURE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun openReviewScreen() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_REVIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SynapseApplication.OVERLAY_CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "OverlayService"
        const val ACTION_START = "com.synapse.action.START_OVERLAY"
        const val ACTION_STOP = "com.synapse.action.STOP_OVERLAY"
        const val ACTION_SHOW_CAPTURE = "com.synapse.action.SHOW_CAPTURE"
        const val ACTION_HIDE_CAPTURE = "com.synapse.action.HIDE_CAPTURE"
        const val ACTION_UPDATE_BADGE = "com.synapse.action.UPDATE_BADGE"
        const val ACTION_TOGGLE_REGION_MODE = "com.synapse.action.TOGGLE_REGION_MODE"
        const val ACTION_SET_MEDIA_PROJECTION = "com.synapse.action.SET_MEDIA_PROJECTION"
        const val ACTION_SHOW_WITH_CONTEXT = "com.synapse.action.SHOW_WITH_CONTEXT"
        const val EXTRA_CHUNK_COUNT = "chunk_count"
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun showCapture(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SHOW_CAPTURE
            }
            context.startService(intent)
        }

        fun updateBadge(context: Context, count: Int) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_UPDATE_BADGE
                putExtra(EXTRA_CHUNK_COUNT, count)
            }
            context.startService(intent)
        }

        fun toggleRegionMode(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_TOGGLE_REGION_MODE
            }
            context.startService(intent)
        }
    }
}

/**
 * Custom ComposeView that differentiates between stylus and finger input at the View level.
 * Finger touches are passed through to the window below, stylus input is captured.
 *
 * Strategy:
 * - Window starts in pass-through mode (FLAG_NOT_TOUCHABLE)
 * - When stylus hovers or touches, we enable touch capture
 * - After stylus leaves (hover exit or touch up), we restore pass-through after a delay
 * - This allows finger scrolling while stylus can still draw
 */
@SuppressLint("ViewConstructor")
class TouchDifferentiatingOverlayView(
    context: Context,
    private val content: @Composable () -> Unit
) : AbstractComposeView(context) {

    companion object {
        private const val TAG = "TouchDiffOverlay"
        // Delay before returning to pass-through after stylus leaves
        private const val PASS_THROUGH_DELAY_MS = 300L
    }

    private var windowManager: WindowManager? = null
    private var isTouchable = true  // Window now starts touchable
    private var pendingPassThroughRunnable: Runnable? = null
    private val palmFilter = PalmRejectionFilter()

    fun setWindowManager(wm: WindowManager) {
        windowManager = wm
        // Window starts touchable, no need to enable
    }

    @Composable
    override fun Content() {
        content()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // Handle stylus hover events
        if (event.isFromSource(android.view.InputDevice.SOURCE_STYLUS)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    Log.d(TAG, "Stylus hover enter")
                    cancelPendingPassThrough()
                    enableTouchCapture()
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    Log.d(TAG, "Stylus hover exit")
                    schedulePassThrough()
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Palm rejection: filter large-area touches before any processing
        if (palmFilter.filterEvent(event) == PalmRejectionFilter.FilterResult.REJECT) {
            return true // consume the event so it doesn't reach the canvas
        }

        val toolType = event.getToolType(0)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
                       toolType == MotionEvent.TOOL_TYPE_ERASER

        if (isStylus) {
            // Stylus touch - ensure we're capturing and handle it
            cancelPendingPassThrough()
            if (!isTouchable) {
                enableTouchCapture()
            }
            // Once stylus is used, keep overlay touchable so user can tap toolbar buttons
            // Don't schedule pass-through - overlay stays touchable until closed
            return super.dispatchTouchEvent(event)
        } else {
            // Finger touch on toolbar buttons - handle it if we're in touchable mode
            if (isTouchable) {
                return super.dispatchTouchEvent(event)
            }
            Log.d(TAG, "Finger touch received in pass-through mode")
            return false
        }
    }

    private fun enableTouchCapture() {
        if (isTouchable) return
        isTouchable = true

        val params = layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try {
            windowManager?.updateViewLayout(this, params)
            Log.d(TAG, "Touch capture enabled")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "View not attached for touch capture", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Invalid state enabling touch capture", e)
        }
    }

    private fun enablePassThrough() {
        if (!isTouchable) return
        isTouchable = false

        val params = layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            windowManager?.updateViewLayout(this, params)
            Log.d(TAG, "Pass-through enabled")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "View not attached for pass-through", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Invalid state enabling pass-through", e)
        }
    }

    private fun schedulePassThrough() {
        cancelPendingPassThrough()
        pendingPassThroughRunnable = Runnable {
            enablePassThrough()
            pendingPassThroughRunnable = null
        }
        postDelayed(pendingPassThroughRunnable, PASS_THROUGH_DELAY_MS)
    }

    private fun cancelPendingPassThrough() {
        pendingPassThroughRunnable?.let {
            removeCallbacks(it)
            pendingPassThroughRunnable = null
        }
    }
}

/**
 * Fullscreen capture overlay content with canvas and toolbar.
 */
@Composable
private fun CaptureOverlayContent(
    viewModel: CaptureViewModel,
    chunkTimeoutMs: Long,
    isRegionMode: Boolean = false,
    initialToolbarX: Float = 0f,
    initialToolbarY: Float = 100f,
    capturedTextPreview: String? = null,
    onClearPreview: (() -> Unit)? = null,
    onMinimize: () -> Unit,
    onDone: () -> Unit,
    onDiscard: () -> Unit,
    onToggleRegionMode: (() -> Unit)? = null,
    onRegionSelected: ((android.graphics.Rect) -> Unit)? = null,
    onVibrate: (() -> Unit)? = null
) {
    val density = LocalDensity.current
    var toolbarWidth by remember { mutableFloatStateOf(0f) }
    var toolbarHeight by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier
            .background(Color.Transparent)
    ) {
        val screenWidthPx = constraints.maxWidth.toFloat()
        val screenHeightPx = constraints.maxHeight.toFloat()

        // Clamp initial position: if toolbar would go off-screen, flip to opposite side
        val clampedX = remember(initialToolbarX, toolbarWidth, screenWidthPx) {
            if (toolbarWidth > 0f && initialToolbarX + toolbarWidth > screenWidthPx) {
                // Flip to left side of bubble position
                (initialToolbarX - toolbarWidth).coerceAtLeast(0f)
            } else {
                initialToolbarX.coerceAtLeast(0f)
            }
        }
        val clampedY = remember(initialToolbarY, toolbarHeight, screenHeightPx) {
            if (toolbarHeight > 0f && initialToolbarY + toolbarHeight > screenHeightPx) {
                (screenHeightPx - toolbarHeight).coerceAtLeast(0f)
            } else {
                initialToolbarY.coerceAtLeast(0f)
            }
        }

        var toolbarOffsetX by remember(clampedX) { mutableFloatStateOf(clampedX) }
        var toolbarOffsetY by remember(clampedY) { mutableFloatStateOf(clampedY) }
        // Apply chunk timeout setting to viewModel
        androidx.compose.runtime.LaunchedEffect(chunkTimeoutMs) {
            viewModel.setChunkTimeout(chunkTimeoutMs)
        }

        // Transparent capture canvas - both stylus and finger can write
        CaptureCanvas(
            viewModel = viewModel,
            inputMode = InputMode.BOTH_WRITE,
            onInputTypeChanged = { inputType ->
                // Could show indicator for input type
            },
            onFingerTouchPassThrough = {
                // Finger touch is passing through to app below
            },
            regionSelectionEnabled = isRegionMode,
            onRegionSelected = onRegionSelected,
            onVibrate = onVibrate
        )

        // Floating toolbar (draggable)
        Box(
            modifier = Modifier
                .offset { IntOffset(toolbarOffsetX.roundToInt(), toolbarOffsetY.roundToInt()) }
                .onGloballyPositioned { coords ->
                    toolbarWidth = coords.size.width.toFloat()
                    toolbarHeight = coords.size.height.toFloat()
                }
                .padding(16.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.medium
                )
                .padding(8.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        toolbarOffsetX = (toolbarOffsetX + dragAmount.x)
                            .coerceIn(0f, (screenWidthPx - toolbarWidth).coerceAtLeast(0f))
                        toolbarOffsetY = (toolbarOffsetY + dragAmount.y)
                            .coerceIn(0f, (screenHeightPx - toolbarHeight).coerceAtLeast(0f))
                    }
                }
        ) {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                // Minimize - hide overlay, keep session (tap bubble to continue)
                SmallFloatingActionButton(
                    onClick = onMinimize,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Minimize",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Undo last stroke
                SmallFloatingActionButton(
                    onClick = { viewModel.undoLastStroke() },
                    containerColor = MaterialTheme.colorScheme.secondary
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo",
                        tint = MaterialTheme.colorScheme.onSecondary
                    )
                }

                // Toggle region capture mode
                SmallFloatingActionButton(
                    onClick = { onToggleRegionMode?.invoke() },
                    containerColor = if (isRegionMode)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Icon(
                        imageVector = Icons.Default.Crop,
                        contentDescription = if (isRegionMode) "Exit Region Mode" else "Region Select",
                        tint = if (isRegionMode)
                            MaterialTheme.colorScheme.onTertiary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Done - end session and save
                SmallFloatingActionButton(
                    onClick = onDone,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "End Session",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }

                // Delete last item - removes latest scribble/image from session
                SmallFloatingActionButton(
                    onClick = onDiscard,
                    containerColor = MaterialTheme.colorScheme.error
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete last item",
                        tint = MaterialTheme.colorScheme.onError
                    )
                }
            }
        }

        // Captured text preview chip
        var showPreview by remember(capturedTextPreview) {
            mutableStateOf(capturedTextPreview != null)
        }

        if (capturedTextPreview != null) {
            LaunchedEffect(capturedTextPreview) {
                kotlinx.coroutines.delay(2500)
                showPreview = false
                onClearPreview?.invoke()
            }
        }

        AnimatedVisibility(
            visible = showPreview && capturedTextPreview != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .background(
                        color = Color.Black.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "\u2713 Selected: ${capturedTextPreview ?: ""}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
            }
        }
    }
}
