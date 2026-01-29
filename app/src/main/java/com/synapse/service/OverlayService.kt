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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import com.synapse.model.Chunk
import com.synapse.ui.MainActivity
import com.synapse.ui.overlay.CaptureCanvas
import com.synapse.ui.overlay.CaptureEvent
import com.synapse.ui.overlay.CaptureViewModel
import com.synapse.ui.overlay.InputMode
import com.synapse.ui.theme.SynapseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import kotlin.math.roundToInt
import androidx.datastore.preferences.core.floatPreferencesKey
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
    private var floatingBubbleView: ComposeView? = null
    private var captureOverlayView: View? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    private var captureViewModel: CaptureViewModel? = null
    private var pendingChunkCount = 0
    private var isCaptureActive = false
    private var isRegionMode = false
    private var capturedTextPreview = mutableStateOf<String?>(null)

    // Repositories for saving sessions and chunks
    private val sessionRepository: SessionRepository by inject()
    private val chunkRepository: ChunkRepository by inject()

    // Screenshot manager for media projection handling
    private val screenshotManager: ScreenshotManager by inject()

    // Coroutine scope for the service
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Current active session ID
    private var currentSessionId: String? = null

    // Settings keys
    private val chunkTimeoutKey = floatPreferencesKey("chunk_timeout_seconds")

    // Cached settings values (read when overlay opens)
    private var chunkTimeoutMs: Long = 1000L

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
                        Log.d(TAG, "Session timeout")
                        endCurrentSession()
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
                // Create session if not exists
                if (currentSessionId == null) {
                    val session = sessionRepository.createSession()
                    currentSessionId = session.id
                    Log.d(TAG, "Created new session: ${session.id}")
                }

                val sessionId = currentSessionId ?: return@launch

                // Calculate timestamp in seconds from epoch
                val timestampSeconds = capturedChunk.timestamp / 1000f

                // Save chunk image and metadata
                val chunk = chunkRepository.saveChunk(
                    sessionId = sessionId,
                    bitmap = capturedChunk.bitmap,
                    timestampSeconds = timestampSeconds
                )
                Log.d(TAG, "Saved chunk: ${chunk.id} to session $sessionId")

                // Update badge count
                pendingChunkCount++
                launch(Dispatchers.Main) {
                    updateBubble()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save chunk", e)
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
                // Create session if needed
                if (currentSessionId == null) {
                    val session = sessionRepository.createSession()
                    currentSessionId = session.id
                    Log.d(TAG, "Created new session for region select: ${session.id}")
                }
                val sessionId = currentSessionId ?: return@launch

                // Try text extraction via accessibility service first
                var regionText: CapturedContext.RegionText? = null
                try {
                    regionText = SynapseAccessibilityService.getInstance()
                        ?.getTextInRegion(region)
                } catch (e: Exception) {
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
                            Log.w(TAG, "Screenshot capture returned null")
                            capturedTextPreview.value = "[Screenshot failed]"
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to capture region", e)
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot", e)
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
                if (currentSessionId == null) {
                    val session = sessionRepository.createSession()
                    currentSessionId = session.id
                    Log.d(TAG, "Created new session for pending context: ${session.id}")
                }
                val sessionId = currentSessionId ?: return@launch
                sessionRepository.addContext(sessionId, context)
                Log.d(TAG, "Added pending context to session $sessionId")

                if (context is com.synapse.model.CapturedContext.SelectedText) {
                    val preview = context.text.take(60).let {
                        if (context.text.length > 60) "$it..." else it
                    }
                    capturedTextPreview.value = preview
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add pending context to session", e)
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
        } catch (e: Exception) {
            Log.w(TAG, "Failed to vibrate", e)
        }
    }

    private fun endCurrentSession() {
        val sessionId = currentSessionId ?: return
        serviceScope.launch(Dispatchers.IO) {
            try {
                sessionRepository.endSession(sessionId)
                Log.d(TAG, "Ended session: $sessionId")
                currentSessionId = null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to end session", e)
            }
        }
    }

    /**
     * Ends the session and opens Review screen after session is saved.
     */
    private fun finishSessionAndOpenReview() {
        val sessionId = currentSessionId
        captureViewModel?.endSession()

        // Reset badge count since user is going to review
        pendingChunkCount = 0

        serviceScope.launch(Dispatchers.IO) {
            try {
                if (sessionId != null) {
                    sessionRepository.endSession(sessionId)
                    Log.d(TAG, "Ended session: $sessionId")
                    currentSessionId = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to end session", e)
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
            ACTION_START -> startOverlay()
            null -> {
                // Service restarted by system (START_STICKY) — re-initialize
                Log.d(TAG, "Service restarted by system, re-initializing overlay")
                startOverlay()
            }
            ACTION_STOP -> stopOverlay()
            ACTION_SHOW_CAPTURE -> showCaptureOverlay()
            ACTION_HIDE_CAPTURE -> hideCaptureOverlay()
            ACTION_UPDATE_BADGE -> {
                pendingChunkCount = intent.getIntExtra(EXTRA_CHUNK_COUNT, 0)
                updateBubble()
            }
            ACTION_TOGGLE_REGION_MODE -> {
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
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceScope.cancel()
        hideCaptureOverlay()
        hideFloatingBubble()
        super.onDestroy()
    }

    private fun startOverlay() {
        Log.d(TAG, "startOverlay called")

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

        // Auto-request screen capture permission so user doesn't get prompted mid-workflow
        if (!screenshotManager.hasPermission()) {
            Log.d(TAG, "No screen capture permission, requesting upfront")
            requestScreenCapturePermission()
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set media projection", e)
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upgrade foreground service type", e)
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

    private fun showFloatingBubble() {
        Log.d(TAG, "showFloatingBubble called, existing view: ${floatingBubbleView != null}")
        if (floatingBubbleView != null) return

        try {
        // Get screen height
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels

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
            x = 100
            y = 300
        }

        floatingBubbleView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)

            setContent {
                SynapseTheme {
                    FloatingBubble(
                        pendingCount = pendingChunkCount,
                        screenHeight = screenHeight,
                        initialY = params.y,
                        onClick = { showCaptureOverlay() },
                        onDismiss = { stopOverlay() },
                        onPositionChanged = { dx, dy ->
                            params.x += dx.roundToInt()
                            params.y += dy.roundToInt()
                            windowManager.updateViewLayout(this, params)
                        }
                    )
                }
            }
        }

        windowManager.addView(floatingBubbleView, params)
            Log.d(TAG, "Floating bubble added to window manager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating bubble", e)
        }
    }

    private fun hideFloatingBubble() {
        floatingBubbleView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove floating bubble view", e)
            }
            floatingBubbleView = null
        }
    }

    private fun updateBubble() {
        // Force recomposition by removing and re-adding
        val view = floatingBubbleView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return

        hideFloatingBubble()
        showFloatingBubble()

        // Restore position
        floatingBubbleView?.let {
            (it.layoutParams as? WindowManager.LayoutParams)?.let { newParams ->
                newParams.x = params.x
                newParams.y = params.y
                windowManager.updateViewLayout(it, newParams)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCaptureOverlay() {
        if (captureOverlayView != null || isCaptureActive) return
        isCaptureActive = true

        // Ensure screenshot permission is available
        if (!screenshotManager.hasPermission()) {
            Log.d(TAG, "No screenshot permission on capture show, requesting")
            requestScreenCapturePermission()
        }

        // Read settings
        try {
            runBlocking {
                val prefs = settingsDataStore.data.first()
                val chunkTimeoutSeconds = prefs[chunkTimeoutKey] ?: 1f
                chunkTimeoutMs = (chunkTimeoutSeconds * 1000).toLong()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read settings", e)
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
                        viewModel = captureViewModel!!,
                        chunkTimeoutMs = chunkTimeoutMs,
                        isRegionMode = isRegionMode,
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
                            captureViewModel?.clearStrokes()
                            // Discard current session without ending
                            currentSessionId = null
                            hideCaptureOverlay()
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
        captureOverlayView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove capture overlay view", e)
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable touch capture", e)
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable pass-through", e)
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
 * Floating bubble composable with drag support and badge.
 * Tap to open capture, drag to bottom X to close.
 */
@Composable
private fun FloatingBubble(
    pendingCount: Int,
    screenHeight: Int,
    initialY: Int,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    onPositionChanged: (Float, Float) -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var currentY by remember { mutableFloatStateOf(initialY.toFloat()) }

    // Check if bubble is in dismiss zone (bottom 15% of screen)
    val dismissZoneThreshold = screenHeight * 0.85f
    val isInDismissZone = currentY > dismissZoneThreshold && isDragging

    Box(
        contentAlignment = Alignment.TopEnd
    ) {
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                        },
                        onDragEnd = {
                            val shouldDismiss = currentY > dismissZoneThreshold
                            isDragging = false
                            if (shouldDismiss) {
                                onDismiss()
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            currentY += dragAmount.y
                            onPositionChanged(dragAmount.x, dragAmount.y)
                        }
                    )
                },
            containerColor = if (isInDismissZone)
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.primary,
            elevation = FloatingActionButtonDefaults.elevation(8.dp)
        ) {
            Icon(
                imageVector = if (isInDismissZone) Icons.Default.Close else Icons.Default.Edit,
                contentDescription = "Start capture (drag to bottom to close)",
                tint = if (isInDismissZone)
                    MaterialTheme.colorScheme.onError
                else
                    MaterialTheme.colorScheme.onPrimary
            )
        }

        // Badge showing pending chunk count
        if (pendingCount > 0 && !isInDismissZone) {
            Box(
                modifier = Modifier
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(20.dp)
                    .background(
                        color = MaterialTheme.colorScheme.error,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (pendingCount > 99) "99+" else pendingCount.toString(),
                    color = MaterialTheme.colorScheme.onError,
                    style = MaterialTheme.typography.labelSmall
                )
            }
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
    capturedTextPreview: String? = null,
    onClearPreview: (() -> Unit)? = null,
    onMinimize: () -> Unit,
    onDone: () -> Unit,
    onDiscard: () -> Unit,
    onToggleRegionMode: (() -> Unit)? = null,
    onRegionSelected: ((android.graphics.Rect) -> Unit)? = null,
    onVibrate: (() -> Unit)? = null
) {
    var toolbarOffsetX by remember { mutableFloatStateOf(0f) }
    var toolbarOffsetY by remember { mutableFloatStateOf(100f) }

    Box(
        modifier = Modifier
            .background(Color.Transparent)
    ) {
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
                .padding(16.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.medium
                )
                .padding(8.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        toolbarOffsetX += dragAmount.x
                        toolbarOffsetY += dragAmount.y
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

                // Discard - clear all and close
                SmallFloatingActionButton(
                    onClick = onDiscard,
                    containerColor = MaterialTheme.colorScheme.error
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Discard",
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
