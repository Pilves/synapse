package com.synapse.ui.overlay

import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.hypot
import kotlin.math.maxOf
import kotlin.math.minOf

class RegionGestureDetector(
    private val onRegionSelected: (Rect) -> Unit,
    private val onVibrate: () -> Unit = {}
) {
    private var isHolding = false
    private var holdStartPoint: PointF? = null
    private var currentRect: Rect? = null

    private val holdThresholdMs = 500L
    private val movementThreshold = 10f

    private val handler = Handler(Looper.getMainLooper())
    private val holdRunnable = Runnable {
        isHolding = true
        onVibrate()
    }

    fun onTouchEvent(event: MotionEvent): RegionGestureResult {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                holdStartPoint = PointF(event.x, event.y)
                handler.postDelayed(holdRunnable, holdThresholdMs)
                RegionGestureResult.Pending
            }

            MotionEvent.ACTION_MOVE -> {
                if (isHolding) {
                    val start = holdStartPoint!!
                    currentRect = Rect(
                        minOf(start.x, event.x).toInt(),
                        minOf(start.y, event.y).toInt(),
                        maxOf(start.x, event.x).toInt(),
                        maxOf(start.y, event.y).toInt()
                    )
                    RegionGestureResult.SelectionInProgress(currentRect!!)
                } else {
                    val distance = hypot(
                        event.x - (holdStartPoint?.x ?: event.x),
                        event.y - (holdStartPoint?.y ?: event.y)
                    )
                    if (distance > movementThreshold) {
                        handler.removeCallbacks(holdRunnable)
                        RegionGestureResult.Stroke
                    } else {
                        RegionGestureResult.Pending
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(holdRunnable)

                if (isHolding && currentRect != null) {
                    val rect = currentRect!!
                    isHolding = false
                    currentRect = null
                    holdStartPoint = null

                    if (rect.width() > 50 && rect.height() > 50) {
                        onRegionSelected(rect)
                        RegionGestureResult.SelectionComplete(rect)
                    } else {
                        RegionGestureResult.SelectionCancelled
                    }
                } else {
                    isHolding = false
                    holdStartPoint = null
                    RegionGestureResult.Stroke
                }
            }

            else -> RegionGestureResult.Ignored
        }
    }

    fun reset() {
        handler.removeCallbacks(holdRunnable)
        isHolding = false
        holdStartPoint = null
        currentRect = null
    }
}

sealed class RegionGestureResult {
    object Pending : RegionGestureResult()
    object Stroke : RegionGestureResult()
    data class SelectionInProgress(val rect: Rect) : RegionGestureResult()
    data class SelectionComplete(val rect: Rect) : RegionGestureResult()
    object SelectionCancelled : RegionGestureResult()
    object Ignored : RegionGestureResult()
}
