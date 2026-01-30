package com.synapse.ui.overlay

import android.os.Build
import android.view.MotionEvent

/**
 * Filters palm touches from stylus input on tablets.
 *
 * Detection strategy:
 * 1. Touch area geometry: palm touches have large contact area (touch_major/touch_minor)
 * 2. Concurrent stylus: reject finger touches that arrive while stylus is active
 * 3. Configurable thresholds for different device form factors
 *
 * Thresholds are defaults for common tablets. They can be adjusted for specific
 * device families (e.g., Samsung S Pen, Huawei M-Pencil) if needed.
 */
class PalmRejectionFilter(
    /** Touch major axis threshold above which a touch is considered a palm. */
    var palmMajorThreshold: Float = 100f,

    /** Touch minor axis threshold above which a touch is considered a palm. */
    var palmMinorThreshold: Float = 60f
) {
    /** Whether a stylus pointer is currently active (down and not yet up). */
    private var stylusActive = false

    /** Set of pointer IDs that have been classified as palm and should be rejected. */
    private val rejectedPointers = mutableSetOf<Int>()

    /**
     * Result of filtering a [MotionEvent].
     */
    enum class FilterResult {
        /** Event should be consumed (is a valid stylus/finger input). */
        ACCEPT,
        /** Event should be rejected (palm or finger during stylus). */
        REJECT
    }

    /**
     * Filters a [MotionEvent] and returns whether it should be accepted or rejected.
     *
     * Call this before dispatching the event to the drawing canvas.
     */
    fun filterEvent(event: MotionEvent): FilterResult {
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)
        val toolType = event.getToolType(actionIndex)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
                       toolType == MotionEvent.TOOL_TYPE_ERASER

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (isStylus) {
                    stylusActive = true
                    return FilterResult.ACCEPT
                }

                // Check if this finger touch looks like a palm
                if (isPalmTouch(event, actionIndex)) {
                    rejectedPointers.add(pointerId)
                    return FilterResult.REJECT
                }

                // Reject finger touches while stylus is active
                if (stylusActive) {
                    rejectedPointers.add(pointerId)
                    return FilterResult.REJECT
                }

                return FilterResult.ACCEPT
            }

            MotionEvent.ACTION_MOVE -> {
                // If any pointer in this event batch is rejected, check each
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    if (pid in rejectedPointers) continue

                    // Re-check for palm during move (palm can land flat after initial touch)
                    if (!isToolStylus(event, i) && isPalmTouch(event, i)) {
                        rejectedPointers.add(pid)
                    }
                }

                // Accept if any non-rejected pointer exists
                val hasValidPointer = (0 until event.pointerCount).any {
                    event.getPointerId(it) !in rejectedPointers
                }
                return if (hasValidPointer) FilterResult.ACCEPT else FilterResult.REJECT
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                rejectedPointers.remove(pointerId)

                if (isStylus) {
                    // Check if any stylus pointer is still down
                    var otherStylusDown = false
                    for (i in 0 until event.pointerCount) {
                        if (i != actionIndex && isToolStylus(event, i)) {
                            otherStylusDown = true
                            break
                        }
                    }
                    if (!otherStylusDown) {
                        stylusActive = false
                    }
                    return FilterResult.ACCEPT
                }

                return if (pointerId in rejectedPointers) FilterResult.REJECT else FilterResult.ACCEPT
            }

            MotionEvent.ACTION_CANCEL -> {
                reset()
                return FilterResult.REJECT
            }
        }

        return FilterResult.ACCEPT
    }

    /**
     * Resets the filter state. Call when the overlay is hidden or a new session starts.
     */
    fun reset() {
        stylusActive = false
        rejectedPointers.clear()
    }

    private fun isPalmTouch(event: MotionEvent, pointerIndex: Int): Boolean {
        val major = event.getTouchMajor(pointerIndex)
        val minor = event.getTouchMinor(pointerIndex)

        // Large contact area → likely a palm
        return major > palmMajorThreshold || minor > palmMinorThreshold
    }

    private fun isToolStylus(event: MotionEvent, pointerIndex: Int): Boolean {
        val toolType = event.getToolType(pointerIndex)
        return toolType == MotionEvent.TOOL_TYPE_STYLUS ||
               toolType == MotionEvent.TOOL_TYPE_ERASER
    }
}
