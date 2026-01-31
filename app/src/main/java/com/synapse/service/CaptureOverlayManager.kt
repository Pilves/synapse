package com.synapse.service

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.platform.ComposeView
import com.synapse.ui.overlay.CaptureViewModel
import com.synapse.ui.theme.SynapseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Manages the fullscreen capture overlay (transparent canvas + floating toolbar).
 *
 * Extracted from [OverlayService] to isolate capture overlay lifecycle from
 * the bubble and service plumbing. The overlay is shown when the user taps
 * the floating bubble and hidden on minimize, done, or navigation events
 * detected via the accessibility service.
 */
class CaptureOverlayManager(
    private val service: OverlayService,
    private val windowManager: WindowManager,
    private val captureViewModel: CaptureViewModel,
    private val screenshotManager: ScreenshotManager,
    private val scope: CoroutineScope,
    private val dataStore: DataStore<Preferences>,
    private val onMinimize: () -> Unit,
    private val onDone: () -> Unit,
    private val onDiscard: () -> Unit,
    private val onRegionSelected: (Rect) -> Unit,
    private val onVibrate: () -> Unit,
    private val onRequestPermission: () -> Unit,
    private val getBubblePosition: () -> Pair<Int, Int>,
    private val showBubble: () -> Unit,
    private val hideBubble: () -> Unit
) {
    companion object {
        private const val TAG = "CaptureOverlayManager"
    }

    private var captureOverlayView: View? = null

    /** Whether the capture overlay is currently visible. */
    var isActive: Boolean = false
        private set

    /** Whether region-select mode is enabled (vs. normal write mode). */
    var isRegionMode: Boolean = false

    /** Brief preview of the last captured text, shown as a toast-like chip on the overlay. */
    val capturedTextPreview: MutableState<String?> = mutableStateOf(null)

    // Settings keys & cached values
    private val chunkTimeoutKey = floatPreferencesKey("chunk_timeout_seconds")
    private var chunkTimeoutMs: Long = 1000L

    init {
        // Eagerly load settings so the first show() uses real values
        scope.launch(Dispatchers.IO) {
            try {
                val prefs = dataStore.data.first()
                val chunkTimeoutSeconds = prefs[chunkTimeoutKey] ?: 1f
                chunkTimeoutMs = (chunkTimeoutSeconds * 1000).toLong()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pre-load settings", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Shows the fullscreen capture overlay.
     *
     * If screenshot permission is missing the overlay is not shown and the
     * permission request flow is triggered instead.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (captureOverlayView != null || isActive) return
        isActive = true

        // If screenshot permission is missing, close the overlay so the user
        // doesn't scribble on the permission dialog, then request permission.
        if (!screenshotManager.hasPermission()) {
            Log.d(TAG, "No screenshot permission on capture show, closing overlay and requesting")
            isActive = false
            hide()
            onRequestPermission()
            return
        }

        // Read settings on IO thread (use cached default until loaded)
        scope.launch(Dispatchers.IO) {
            try {
                val prefs = dataStore.data.first()
                val chunkTimeoutSeconds = prefs[chunkTimeoutKey] ?: 1f
                chunkTimeoutMs = (chunkTimeoutSeconds * 1000).toLong()
            } catch (e: IOException) {
                Log.e(TAG, "IO error reading settings", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Invalid state reading settings", e)
            }
        }

        // Hide bubble while capturing
        hideBubble()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val (bubbleX, bubbleY) = getBubblePosition()

        val overlayView = ComposeView(service).apply {
            setViewTreeLifecycleOwner(service)
            setViewTreeSavedStateRegistryOwner(service)
            setContent {
                SynapseTheme {
                    CaptureOverlayContent(
                        viewModel = captureViewModel,
                        chunkTimeoutMs = chunkTimeoutMs,
                        isRegionMode = isRegionMode,
                        initialToolbarX = bubbleX.toFloat(),
                        initialToolbarY = bubbleY.toFloat(),
                        capturedTextPreview = capturedTextPreview.value,
                        onClearPreview = { capturedTextPreview.value = null },
                        onMinimize = {
                            hide()
                        },
                        onDone = onDone,
                        onDiscard = onDiscard,
                        onToggleRegionMode = {
                            if (!isRegionMode) {
                                capturedTextPreview.value = "Draw a rectangle around text"
                            }
                            isRegionMode = !isRegionMode
                            Log.d(TAG, "Region mode toggled: $isRegionMode")
                            refresh()
                        },
                        onRegionSelected = { rect ->
                            onRegionSelected(rect)
                        },
                        onVibrate = onVibrate
                    )
                }
            }
        }

        captureOverlayView = overlayView
        windowManager.addView(overlayView, params)

        // Auto-minimize when user navigates away (home/recents/back)
        SynapseAccessibilityService.getInstance()?.let { a11y ->
            a11y.onWindowChanged = {
                if (isActive) {
                    scope.launch(Dispatchers.Main) {
                        hide()
                    }
                }
            }
            a11y.onBackPressed = {
                if (isActive) {
                    scope.launch(Dispatchers.Main) {
                        hide()
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
    fun refresh() {
        captureOverlayView?.let {
            windowManager.removeView(it)
            captureOverlayView = null
        }
        // Don't reset isActive - we're just refreshing
        isActive = false
        show()
    }

    /**
     * Hides the capture overlay and restores the floating bubble.
     * Clears accessibility-service navigation listeners and pauses
     * screen mirroring to conserve resources.
     */
    fun hide() {
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
        isActive = false
        isRegionMode = false
        // Pause screen mirroring to save resources while not capturing
        screenshotManager.pauseCapture()
        showBubble()
    }
}
