package com.synapse.ui.overlay

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PalmRejectionFilterTest {

    private lateinit var filter: PalmRejectionFilter

    @Before
    fun setup() {
        filter = PalmRejectionFilter(
            palmMajorThreshold = 100f,
            palmMinorThreshold = 60f
        )
    }

    @Test
    fun `stylus events are always accepted`() {
        val event = createMotionEvent(
            action = MotionEvent.ACTION_DOWN,
            toolType = MotionEvent.TOOL_TYPE_STYLUS,
            touchMajor = 5f,
            touchMinor = 3f
        )
        assertEquals(PalmRejectionFilter.FilterResult.ACCEPT, filter.filterEvent(event))
        event.recycle()
    }

    @Test
    fun `finger events accepted when no stylus active and not palm`() {
        val event = createMotionEvent(
            action = MotionEvent.ACTION_DOWN,
            toolType = MotionEvent.TOOL_TYPE_FINGER,
            touchMajor = 30f,
            touchMinor = 20f
        )
        assertEquals(PalmRejectionFilter.FilterResult.ACCEPT, filter.filterEvent(event))
        event.recycle()
    }

    @Test
    fun `large touch area is rejected as palm`() {
        val event = createMotionEvent(
            action = MotionEvent.ACTION_DOWN,
            toolType = MotionEvent.TOOL_TYPE_FINGER,
            touchMajor = 150f, // > 100f threshold
            touchMinor = 80f   // > 60f threshold
        )
        assertEquals(PalmRejectionFilter.FilterResult.REJECT, filter.filterEvent(event))
        event.recycle()
    }

    @Test
    fun `finger rejected when stylus is active`() {
        // First, activate stylus
        val stylusDown = createMotionEvent(
            action = MotionEvent.ACTION_DOWN,
            toolType = MotionEvent.TOOL_TYPE_STYLUS,
            touchMajor = 5f,
            touchMinor = 3f
        )
        filter.filterEvent(stylusDown)
        stylusDown.recycle()

        // Then try finger - should be rejected
        val fingerDown = createMotionEvent(
            action = MotionEvent.ACTION_DOWN,
            toolType = MotionEvent.TOOL_TYPE_FINGER,
            touchMajor = 30f,
            touchMinor = 20f
        )
        assertEquals(PalmRejectionFilter.FilterResult.REJECT, filter.filterEvent(fingerDown))
        fingerDown.recycle()
    }

    @Test
    fun `reset clears stylus active state`() {
        // Activate stylus
        val stylusDown = createMotionEvent(
            action = MotionEvent.ACTION_DOWN,
            toolType = MotionEvent.TOOL_TYPE_STYLUS,
            touchMajor = 5f,
            touchMinor = 3f
        )
        filter.filterEvent(stylusDown)
        stylusDown.recycle()

        // Reset
        filter.reset()

        // Now finger should be accepted
        val fingerDown = createMotionEvent(
            action = MotionEvent.ACTION_DOWN,
            toolType = MotionEvent.TOOL_TYPE_FINGER,
            touchMajor = 30f,
            touchMinor = 20f
        )
        assertEquals(PalmRejectionFilter.FilterResult.ACCEPT, filter.filterEvent(fingerDown))
        fingerDown.recycle()
    }

    @Test
    fun `custom thresholds are respected`() {
        val strictFilter = PalmRejectionFilter(
            palmMajorThreshold = 50f,
            palmMinorThreshold = 30f
        )

        val event = createMotionEvent(
            action = MotionEvent.ACTION_DOWN,
            toolType = MotionEvent.TOOL_TYPE_FINGER,
            touchMajor = 60f, // > 50f threshold
            touchMinor = 20f
        )
        assertEquals(PalmRejectionFilter.FilterResult.REJECT, strictFilter.filterEvent(event))
        event.recycle()
    }

    private fun createMotionEvent(
        action: Int,
        toolType: Int,
        touchMajor: Float = 10f,
        touchMinor: Float = 10f,
        x: Float = 100f,
        y: Float = 100f
    ): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            this.toolType = toolType
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.touchMajor = touchMajor
            this.touchMinor = touchMinor
            pressure = 0.5f
        }
        return MotionEvent.obtain(
            0L, // downTime
            0L, // eventTime
            action,
            1, // pointerCount
            arrayOf(properties),
            arrayOf(coords),
            0, // metaState
            0, // buttonState
            1f, // xPrecision
            1f, // yPrecision
            0, // deviceId
            0, // edgeFlags
            android.view.InputDevice.SOURCE_TOUCHSCREEN,
            0  // flags
        )
    }
}
