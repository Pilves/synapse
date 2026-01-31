package com.synapse.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.synapse.R
import com.synapse.SynapseApplication
import com.synapse.data.repository.ChunkRepository
import com.synapse.data.repository.SessionRepository
import com.synapse.ui.MainActivity
import com.synapse.ui.overlay.CaptureCanvas
import com.synapse.ui.overlay.CaptureEvent
import com.synapse.ui.overlay.CaptureViewModel
import com.synapse.ui.overlay.InputMode
import com.synapse.ui.overlay.PalmRejectionFilter
import com.synapse.ui.settings.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import kotlin.math.roundToInt

/**
 * Foreground service that manages the floating overlay capture system.
 *
 * Delegates to:
 * - [FloatingBubbleManager] for the floating bubble UI
 * - [CaptureOverlayManager] for the fullscreen capture overlay
 * - [OverlaySessionManager] for session/chunk persistence
 */
class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    private var captureViewModel: CaptureViewModel? = null

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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Managers
    private var bubbleManager: FloatingBubbleManager? = null
    private var overlayManager: CaptureOverlayManager? = null
    private var sessionManager: OverlaySessionManager? = null

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

        initManagers()

        // Start permission health monitoring
        permissionHealthMonitor.startMonitoring()
        serviceScope.launch {
            permissionHealthMonitor.health.collect { health ->
                val warning = health != PermissionHealthMonitor.PermissionHealth.HEALTHY
                bubbleManager?.showHealthWarning = warning
                bubbleManager?.updateBubbleWarning(warning)
            }
        }

        // Collect capture events and save chunks
        serviceScope.launch {
            captureViewModel?.events?.collect { event ->
                when (event) {
                    is CaptureEvent.ChunkCaptured -> {
                        Log.d(TAG, "Chunk captured: index=${event.chunk.index}")
                        sessionManager?.saveChunk(event.chunk)
                    }
                    is CaptureEvent.SessionEnded -> {
                        Log.d(TAG, "Session ended")
                        sessionManager?.endCurrentSession()
                    }
                    is CaptureEvent.SessionTimeout -> {
                        Log.d(TAG, "Session timeout — ending session and closing overlay")
                        sessionManager?.endCurrentSession()
                        withContext(Dispatchers.Main) { overlayManager?.hide() }
                    }
                    is CaptureEvent.Error -> {
                        Log.e(TAG, "Capture error: ${event.message}")
                    }
                }
            }
        }
    }

    private fun initManagers() {
        val vm = captureViewModel ?: return

        sessionManager = OverlaySessionManager(
            context = this,
            sessionRepository = sessionRepository,
            chunkRepository = chunkRepository,
            screenshotManager = screenshotManager,
            captureViewModel = vm,
            scope = serviceScope,
            onBadgeUpdate = { count -> bubbleManager?.updateBubbleBadge(count) },
            onOpenReview = { openReviewScreen() },
            onHideOverlay = { overlayManager?.hide() },
            onRefreshOverlay = {
                overlayManager?.isRegionMode = false
                overlayManager?.refresh()
            },
            onRequestPermission = { requestScreenCapturePermission() }
        )

        bubbleManager = FloatingBubbleManager(
            context = this,
            windowManager = windowManager,
            dataStore = settingsDataStore,
            scope = serviceScope,
            onTap = { showCaptureOverlay() },
            onDismiss = { stopOverlay() },
            onWarningTap = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
        )

        overlayManager = CaptureOverlayManager(
            service = this,
            windowManager = windowManager,
            captureViewModel = vm,
            screenshotManager = screenshotManager,
            scope = serviceScope,
            dataStore = settingsDataStore,
            onMinimize = { showFloatingBubble() },
            onDone = { sessionManager?.finishSessionAndOpenReview() },
            onDiscard = { sessionManager?.deleteLastSessionItem() },
            onRegionSelected = { rect -> sessionManager?.handleRegionSelected(rect) },
            onVibrate = { sessionManager?.vibrateForRegionSelection() },
            onRequestPermission = { requestScreenCapturePermission() },
            getBubblePosition = {
                val bm = bubbleManager
                Pair(bm?.lastBubbleX ?: 100, bm?.lastBubbleY ?: 300)
            },
            showBubble = { showFloatingBubble() },
            hideBubble = { hideFloatingBubble() }
        )

        // Share mutable state between overlay and session managers
        overlayManager?.let { om ->
            sessionManager?.let { sm ->
                // The capturedTextPreview is shared: session manager writes it, overlay displays it
                // We wire them together by making overlay read from session manager's state
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
            ACTION_HIDE_CAPTURE -> overlayManager?.hide()
            ACTION_UPDATE_BADGE -> {
                val count = intent.getIntExtra(EXTRA_CHUNK_COUNT, 0)
                sessionManager?.pendingChunkCount = count
                bubbleManager?.updateBubbleBadge(count)
            }
            ACTION_TOGGLE_REGION_MODE -> {
                val om = overlayManager ?: return START_STICKY
                // Check if accessibility is available for text extraction
                if (!om.isRegionMode && !capabilities.canExtractText) {
                    Log.w(TAG, "Region mode toggled without accessibility — text extraction unavailable, screenshot fallback only")
                }
                om.isRegionMode = !om.isRegionMode
                Log.d(TAG, "Region capture mode: ${om.isRegionMode}")
                // Request screen capture permission if entering region mode without it
                if (om.isRegionMode && !screenshotManager.hasPermission()) {
                    Log.d(TAG, "Requesting screen capture permission for region mode")
                    requestScreenCapturePermission()
                }
                // Re-show overlay to apply the mode change
                if (om.isActive) {
                    om.refresh()
                }
            }
            ACTION_SET_MEDIA_PROJECTION -> {
                handleMediaProjectionResult(intent)
            }
            ACTION_SHOW_WITH_CONTEXT -> {
                sessionManager?.handlePendingContext()
                showCaptureOverlay()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        permissionHealthMonitor.stopMonitoring()
        serviceScope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        overlayManager?.hide()
        bubbleManager?.hideFloatingBubble()
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
            delay(100)
            overlayManager?.hide()
            bubbleManager?.hideFloatingBubble()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun showFloatingBubble() {
        bubbleManager?.pendingChunkCount = sessionManager?.pendingChunkCount ?: 0
        bubbleManager?.showFloatingBubble()
    }

    private fun hideFloatingBubble() {
        bubbleManager?.hideFloatingBubble()
    }

    private fun showCaptureOverlay() {
        val om = overlayManager ?: return
        // Sync shared state from session manager
        sessionManager?.let { sm ->
            om.capturedTextPreview.value = sm.capturedTextPreview.value
        }
        om.show()
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
internal fun CaptureOverlayContent(
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
    onRegionSelected: ((Rect) -> Unit)? = null,
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
        LaunchedEffect(chunkTimeoutMs) {
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                delay(2500)
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
