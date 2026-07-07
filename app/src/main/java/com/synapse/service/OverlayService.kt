package com.synapse.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
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
import com.synapse.ui.overlay.CaptureEvent
import com.synapse.ui.overlay.CaptureViewModel
import com.synapse.ui.settings.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

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

    // Guard against double cleanup (onTaskRemoved → stopSelf → onDestroy)
    private var isDestroyed = false

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

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved — delegating cleanup to onDestroy via stopSelf")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (isDestroyed) {
            Log.d(TAG, "onDestroy called again — skipping duplicate cleanup")
            super.onDestroy()
            return
        }
        isDestroyed = true
        Log.d(TAG, "onDestroy — performing cleanup")
        permissionHealthMonitor.stopMonitoring()
        serviceScope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        overlayManager?.destroy()
        bubbleManager?.hideFloatingBubble()
        screenshotManager.releaseProjection()
        super.onDestroy()
    }

    private fun startOverlay(userInitiated: Boolean = false) {
        Log.d(TAG, "startOverlay called (userInitiated=$userInitiated)")

        // Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission not granted!")
            return
        }
        Log.d(TAG, "Overlay permission OK")

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Started foreground service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            stopSelf()
            return
        }
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
            context.startForegroundService(intent)
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
