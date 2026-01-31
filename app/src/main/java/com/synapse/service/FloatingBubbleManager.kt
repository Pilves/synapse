package com.synapse.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.synapse.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Manages the floating bubble overlay that serves as the entry point for capture.
 *
 * The bubble can be tapped to open the capture overlay, dragged to reposition,
 * or dragged to the bottom of the screen to dismiss the overlay service.
 * It also displays a badge for pending chunk count and a warning dot when
 * permissions are degraded.
 */
class FloatingBubbleManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val onTap: () -> Unit,
    private val onDismiss: () -> Unit,
    private val onWarningTap: () -> Unit
) {
    private var floatingBubbleView: View? = null
    private var bubbleBadgeView: android.widget.TextView? = null
    private var bubbleWarningView: View? = null
    private var bubbleIconView: android.widget.ImageView? = null

    /** Last known bubble X position, used for toolbar anchoring. */
    var lastBubbleX: Int
        get() = cachedBubbleX.get()
        private set(value) { cachedBubbleX.set(value) }

    /** Last known bubble Y position, used for toolbar anchoring. */
    var lastBubbleY: Int
        get() = cachedBubbleY.get()
        private set(value) { cachedBubbleY.set(value) }

    /** Number of pending chunks to display on the badge. */
    var pendingChunkCount: Int = 0

    /** Whether to show the health warning dot (permission degraded). */
    var showHealthWarning: Boolean = false

    private val bubbleXKey = intPreferencesKey("bubble_position_x")
    private val bubbleYKey = intPreferencesKey("bubble_position_y")

    // In-memory cache of bubble position, loaded async from DataStore
    private val cachedBubbleX = AtomicInteger(100)
    private val cachedBubbleY = AtomicInteger(300)

    init {
        scope.launch(Dispatchers.IO) {
            try {
                val prefs = dataStore.data.first()
                cachedBubbleX.set(prefs[bubbleXKey] ?: 100)
                cachedBubbleY.set(prefs[bubbleYKey] ?: 300)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load bubble position, using defaults", e)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showFloatingBubble() {
        Log.d(TAG, "showFloatingBubble called, existing view: ${floatingBubbleView != null}")
        if (floatingBubbleView != null) return

        // Use cached bubble position (loaded async from DataStore in init)
        val savedX = cachedBubbleX.get()
        val savedY = cachedBubbleY.get()

        try {
        // Get screen dimensions
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density

        // Resolve colors based on current dark/light mode
        val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
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
        val container = android.widget.FrameLayout(context).apply {
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

        val iconView = android.widget.ImageView(context).apply {
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
        val badgeView = android.widget.TextView(context).apply {
            textSize = 10f
            setTextColor(onErrorColor)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(errorColor)
            }
            visibility = if (pendingChunkCount > 0) View.VISIBLE else View.GONE
            text = if (pendingChunkCount > 9) "9+" else pendingChunkCount.toString()
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
        val warningView = android.widget.TextView(context).apply {
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
        val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
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
                        vibrate()
                        onTap()
                    } else {
                        // Drag ended
                        if (currentY > dismissZoneThreshold) {
                            onDismiss()
                        } else {
                            // Track position for toolbar anchoring
                            lastBubbleX = params.x
                            lastBubbleY = params.y
                            // Persist bubble position
                            scope.launch(Dispatchers.IO) {
                                try {
                                    dataStore.edit { prefs ->
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
            onWarningTap()
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

    fun updateBubbleBadge(count: Int) {
        bubbleBadgeView?.let { badge ->
            if (count > 0) {
                badge.text = if (count > 9) "9+" else count.toString()
                badge.visibility = View.VISIBLE
            } else {
                badge.visibility = View.GONE
            }
        }
    }

    fun updateBubbleWarning(show: Boolean) {
        bubbleWarningView?.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun hideFloatingBubble() {
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

    private fun vibrate(durationMs: Long = 50) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Vibration permission denied", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Vibrator not available", e)
        }
    }

    companion object {
        private const val TAG = "FloatingBubbleManager"
    }
}
