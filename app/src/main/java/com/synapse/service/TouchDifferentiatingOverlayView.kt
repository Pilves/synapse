package com.synapse.service

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView
import com.synapse.ui.overlay.PalmRejectionFilter

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

    override fun onDetachedFromWindow() {
        cancelPendingPassThrough()
        super.onDetachedFromWindow()
    }
}
