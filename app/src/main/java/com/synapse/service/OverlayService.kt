package com.synapse.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.synapse.ui.MainActivity
import com.synapse.ui.overlay.CaptureCanvas
import com.synapse.ui.overlay.CaptureViewModel
import com.synapse.ui.overlay.InputMode
import com.synapse.ui.theme.SynapseTheme
import kotlin.math.roundToInt

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

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        captureViewModel = CaptureViewModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startOverlay()
            ACTION_STOP -> stopOverlay()
            ACTION_SHOW_CAPTURE -> showCaptureOverlay()
            ACTION_HIDE_CAPTURE -> hideCaptureOverlay()
            ACTION_UPDATE_BADGE -> {
                pendingChunkCount = intent.getIntExtra(EXTRA_CHUNK_COUNT, 0)
                updateBubble()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        hideCaptureOverlay()
        hideFloatingBubble()
        super.onDestroy()
    }

    private fun startOverlay() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        showFloatingBubble()
    }

    private fun stopOverlay() {
        hideCaptureOverlay()
        hideFloatingBubble()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showFloatingBubble() {
        if (floatingBubbleView != null) return

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
                        onClick = { showCaptureOverlay() },
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
    }

    private fun hideFloatingBubble() {
        floatingBubbleView?.let {
            windowManager.removeView(it)
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

        // Hide bubble while capturing
        hideFloatingBubble()

        // Create the capture overlay with special touch handling
        // FLAG_NOT_TOUCH_MODAL allows touches to pass through to windows below
        // We handle stylus input in Compose and let finger touches pass through
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            // KEY FLAGS for stylus/finger differentiation:
            // - FLAG_NOT_FOCUSABLE: Don't take focus from underlying app
            // - FLAG_LAYOUT_IN_SCREEN: Full screen including status bar
            // - FLAG_NOT_TOUCH_MODAL: Allow some touches to pass through
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )

        // Create a custom view that handles touch differentiation at the View level
        val overlayView = TouchDifferentiatingOverlayView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)

            setContent {
                SynapseTheme {
                    CaptureOverlayContent(
                        viewModel = captureViewModel!!,
                        onDone = {
                            captureViewModel?.endSession()
                            hideCaptureOverlay()
                        },
                        onDiscard = {
                            captureViewModel?.discardSession()
                            hideCaptureOverlay()
                        }
                    )
                }
            }
        }

        captureOverlayView = overlayView
        windowManager.addView(overlayView, params)
    }

    private fun hideCaptureOverlay() {
        captureOverlayView?.let {
            windowManager.removeView(it)
            captureOverlayView = null
        }
        isCaptureActive = false
        showFloatingBubble()
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
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.synapse.action.START_OVERLAY"
        const val ACTION_STOP = "com.synapse.action.STOP_OVERLAY"
        const val ACTION_SHOW_CAPTURE = "com.synapse.action.SHOW_CAPTURE"
        const val ACTION_HIDE_CAPTURE = "com.synapse.action.HIDE_CAPTURE"
        const val ACTION_UPDATE_BADGE = "com.synapse.action.UPDATE_BADGE"
        const val EXTRA_CHUNK_COUNT = "chunk_count"
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
    }
}

/**
 * Custom ComposeView that differentiates between stylus and finger input at the View level.
 * Finger touches are passed through to the window below, stylus input is captured.
 */
@SuppressLint("ViewConstructor")
class TouchDifferentiatingOverlayView(context: Context) : ComposeView(context) {

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Check if this is stylus input
        val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                       event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER

        return if (isStylus) {
            // Stylus input - handle it (draw)
            super.dispatchTouchEvent(event)
        } else {
            // Finger/other input - don't handle, let it pass through
            // Return false to indicate we didn't handle it
            false
        }
    }
}

/**
 * Floating bubble composable with drag support and badge.
 */
@Composable
private fun FloatingBubble(
    pendingCount: Int,
    onClick: () -> Unit,
    onPositionChanged: (Float, Float) -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.TopEnd
    ) {
        FloatingActionButton(
            onClick = { if (!isDragging) onClick() },
            modifier = Modifier
                .size(56.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = { isDragging = false },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                            onPositionChanged(dragAmount.x, dragAmount.y)
                        }
                    )
                },
            containerColor = MaterialTheme.colorScheme.primary,
            elevation = FloatingActionButtonDefaults.elevation(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Start capture",
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }

        // Badge showing pending chunk count
        if (pendingCount > 0) {
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
    onDone: () -> Unit,
    onDiscard: () -> Unit
) {
    var toolbarOffsetX by remember { mutableFloatStateOf(0f) }
    var toolbarOffsetY by remember { mutableFloatStateOf(100f) }

    Box(
        modifier = Modifier
            .background(Color.Transparent)
    ) {
        // Transparent capture canvas
        // Stylus writes, finger passes through to app below
        CaptureCanvas(
            viewModel = viewModel,
            inputMode = InputMode.STYLUS_WRITE_FINGER_SCROLL,
            onInputTypeChanged = { inputType ->
                // Could show indicator for input type
            },
            onFingerTouchPassThrough = {
                // Finger touch is passing through to app below
            }
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
                // Done button
                SmallFloatingActionButton(
                    onClick = onDone,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Done",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }

                // Undo button
                SmallFloatingActionButton(
                    onClick = { viewModel.undoLastStroke() },
                    containerColor = MaterialTheme.colorScheme.secondary
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo",
                        tint = MaterialTheme.colorScheme.onSecondary
                    )
                }

                // Discard button
                SmallFloatingActionButton(
                    onClick = onDiscard,
                    containerColor = MaterialTheme.colorScheme.error
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Discard",
                        tint = MaterialTheme.colorScheme.onError
                    )
                }
            }
        }
    }
}
