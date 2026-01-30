package com.synapse.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Manages overlay view creation, showing, and hiding for the OverlayService.
 *
 * Encapsulates the WindowManager interaction for the floating bubble and
 * capture overlay views.
 */
class OverlayUiHost(
    private val context: Context,
    private val windowManager: WindowManager,
    private val lifecycleOwner: LifecycleOwner,
    private val savedStateRegistryOwner: SavedStateRegistryOwner
) {
    companion object {
        private const val TAG = "OverlayUiHost"
    }

    var floatingBubbleView: ComposeView? = null
        private set

    var captureOverlayView: View? = null
        private set

    /**
     * Creates and adds a floating bubble ComposeView to the window manager.
     */
    fun showFloatingBubble(
        content: @Composable () -> Unit,
        onPositionChanged: (WindowManager.LayoutParams) -> Unit
    ): WindowManager.LayoutParams? {
        if (floatingBubbleView != null) return null

        return try {
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

            val view = ComposeView(context).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
                setContent { content() }
            }
            floatingBubbleView = view
            windowManager.addView(view, params)
            Log.d(TAG, "Floating bubble added")
            params
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating bubble", e)
            null
        }
    }

    fun hideFloatingBubble() {
        floatingBubbleView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove floating bubble view", e)
            }
            floatingBubbleView = null
        }
    }

    /**
     * Creates and adds a fullscreen capture overlay ComposeView.
     */
    fun showCaptureOverlay(content: @Composable () -> Unit) {
        if (captureOverlayView != null) return

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

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
            setContent { content() }
        }
        captureOverlayView = view
        windowManager.addView(view, params)
    }

    fun hideCaptureOverlay() {
        captureOverlayView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove capture overlay view", e)
            }
            captureOverlayView = null
        }
    }

    fun refreshCaptureOverlay(content: @Composable () -> Unit) {
        hideCaptureOverlay()
        showCaptureOverlay(content)
    }
}
