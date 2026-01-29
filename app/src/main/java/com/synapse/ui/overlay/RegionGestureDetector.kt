package com.synapse.ui.overlay

import android.graphics.PointF
import android.graphics.Rect
import android.view.MotionEvent
import kotlin.math.max
import kotlin.math.min

class RegionGestureDetector(
    private val onRegionSelected: (Rect) -> Unit,
    private val onVibrate: () -> Unit = {}
) {
    private var isHolding = false
    private var holdStartPoint: PointF? = null
    private var currentRect: Rect? = null

    fun onTouchEvent(event: MotionEvent): RegionGestureResult {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                holdStartPoint = PointF(event.x, event.y)
                isHolding = true
                RegionGestureResult.Pending
            }

            MotionEvent.ACTION_MOVE -> {
                if (isHolding) {
                    val start = holdStartPoint!!
                    currentRect = Rect(
                        min(start.x, event.x).toInt(),
                        min(start.y, event.y).toInt(),
                        max(start.x, event.x).toInt(),
                        max(start.y, event.y).toInt()
                    )
                    RegionGestureResult.SelectionInProgress(currentRect!!)
                } else {
                    RegionGestureResult.Ignored
                }
            }

            MotionEvent.ACTION_UP -> {
                if (isHolding && currentRect != null) {
                    val rect = currentRect!!
                    isHolding = false
                    currentRect = null
                    holdStartPoint = null

                    if (rect.width() > 50 && rect.height() > 50) {
                        onVibrate()
                        onRegionSelected(rect)
                        RegionGestureResult.SelectionComplete(rect)
                    } else {
                        RegionGestureResult.SelectionCancelled
                    }
                } else {
                    isHolding = false
                    holdStartPoint = null
                    RegionGestureResult.SelectionCancelled
                }
            }

            else -> RegionGestureResult.Ignored
        }
    }

    fun reset() {
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
