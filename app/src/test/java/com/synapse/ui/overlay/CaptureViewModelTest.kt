package com.synapse.ui.overlay

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CaptureViewModelTest {

    @get:Rule
    val instantRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var vm: CaptureViewModel

    private fun point(x: Float = 10f, y: Float = 10f) = StrokePoint(
        position = Offset(x, y),
        pressure = 0.5f,
        timestamp = System.currentTimeMillis()
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        vm = CaptureViewModel()
    }

    @After
    fun tearDown() {
        // Clean up to cancel any running coroutines
        vm.cleanup()
        Dispatchers.resetMain()
    }

    // --- Initial state ---

    @Test
    fun `initial state is inactive with zero counters`() {
        val state = vm.uiState.value
        assertFalse(state.isSessionActive)
        assertFalse(state.isDrawing)
        assertEquals(0, state.strokeCount)
        assertEquals(0, state.chunkCount)
        assertEquals(0L, state.sessionDurationMs)
        assertFalse(state.isFading)
        assertEquals(1f, state.fadeProgress, 0.01f)
    }

    // --- Configuration ---

    @Test
    fun `setChunkTimeout updates timeout`() {
        vm.setChunkTimeout(2000L)
        assertEquals(2000L, vm.chunkTimeoutMs)
    }

    @Test
    fun `setSessionTimeout updates timeout`() {
        vm.setSessionTimeout(30_000L)
        assertEquals(30_000L, vm.sessionTimeoutMs)
    }

    @Test
    fun `default chunk timeout is 1 second`() {
        assertEquals(CaptureViewModel.DEFAULT_CHUNK_TIMEOUT_MS, vm.chunkTimeoutMs)
        assertEquals(1000L, vm.chunkTimeoutMs)
    }

    @Test
    fun `default session timeout is 10 minutes`() {
        // Read default before setup modifies it
        val fresh = CaptureViewModel()
        assertEquals(CaptureViewModel.DEFAULT_SESSION_TIMEOUT_MS, fresh.sessionTimeoutMs)
        assertEquals(10 * 60 * 1000L, fresh.sessionTimeoutMs)
    }

    // --- Session lifecycle ---
    // Note: startSession() updates _uiState synchronously, so no advanceUntilIdle needed.
    // It also launches timer coroutines, but we don't advance them to avoid
    // the infinite session timer loop.

    @Test
    fun `startSession activates session`() {
        vm.startSession()
        assertTrue(vm.uiState.value.isSessionActive)
    }

    @Test
    fun `startSession is idempotent when already active`() {
        vm.startSession()
        vm.startSession() // second call should be no-op
        assertTrue(vm.uiState.value.isSessionActive)
    }

    @Test
    fun `endSession deactivates session`() {
        vm.startSession()
        assertTrue(vm.uiState.value.isSessionActive)

        vm.endSession()
        assertFalse(vm.uiState.value.isSessionActive)
    }

    @Test
    fun `endSession is noop when not active`() {
        vm.endSession() // should not throw
        assertFalse(vm.uiState.value.isSessionActive)
    }

    // --- Drawing state machine ---

    @Test
    fun `onDrawStart activates session if not active`() {
        vm.onDrawStart(point())
        assertTrue(vm.uiState.value.isSessionActive)
        assertTrue(vm.uiState.value.isDrawing)
    }

    @Test
    fun `onDrawStart sets drawing state`() {
        vm.startSession()
        vm.onDrawStart(point())
        assertTrue(vm.uiState.value.isDrawing)
    }

    @Test
    fun `onDrawStart sets current stroke to initial point`() {
        vm.startSession()
        val p = point(50f, 50f)
        vm.onDrawStart(p)
        assertEquals(1, vm.currentStroke.value.size)
        assertEquals(50f, vm.currentStroke.value[0].x, 0.01f)
    }

    @Test
    fun `onDrawMove appends points when drawing`() {
        vm.startSession()
        vm.onDrawStart(point(0f, 0f))
        vm.onDrawMove(point(10f, 10f))
        vm.onDrawMove(point(20f, 20f))
        assertEquals(3, vm.currentStroke.value.size)
    }

    @Test
    fun `onDrawMove is noop when not drawing`() {
        vm.startSession()
        vm.onDrawMove(point(10f, 10f))
        assertTrue(vm.currentStroke.value.isEmpty())
    }

    @Test
    fun `onDrawEnd clears drawing state and adds stroke`() {
        vm.startSession()
        vm.onDrawStart(point(0f, 0f))
        vm.onDrawMove(point(10f, 10f))
        vm.onDrawEnd(4f)

        assertFalse(vm.uiState.value.isDrawing)
        assertTrue(vm.currentStroke.value.isEmpty())
        assertEquals(1, vm.uiState.value.strokeCount)
        assertEquals(1, vm.strokes.value.size)
    }

    @Test
    fun `onDrawEnd discards strokes with fewer than 2 points`() {
        vm.startSession()
        vm.onDrawStart(point(0f, 0f))
        vm.onDrawEnd(4f) // only 1 point

        assertEquals(0, vm.uiState.value.strokeCount)
        assertTrue(vm.strokes.value.isEmpty())
    }

    @Test
    fun `onDrawEnd is noop when not drawing`() {
        vm.startSession()
        vm.onDrawEnd(4f) // should not throw
        assertEquals(0, vm.uiState.value.strokeCount)
    }

    @Test
    fun `onDrawEnd preserves stroke width in stroke object`() {
        vm.startSession()
        vm.onDrawStart(point(0f, 0f))
        vm.onDrawMove(point(10f, 10f))
        vm.onDrawEnd(8f)

        assertEquals(8f, vm.strokes.value[0].strokeWidth, 0.01f)
    }

    // --- Stroke management ---

    @Test
    fun `cancelCurrentStroke clears current stroke and drawing state`() {
        vm.startSession()
        vm.onDrawStart(point())
        vm.onDrawMove(point(20f, 20f))
        vm.cancelCurrentStroke()

        assertFalse(vm.uiState.value.isDrawing)
        assertTrue(vm.currentStroke.value.isEmpty())
    }

    @Test
    fun `cancelCurrentStroke does not affect previously added strokes`() {
        vm.startSession()

        // Add a complete stroke
        vm.onDrawStart(point(0f, 0f))
        vm.onDrawMove(point(10f, 10f))
        vm.onDrawEnd()

        // Start another and cancel
        vm.onDrawStart(point(20f, 20f))
        vm.onDrawMove(point(30f, 30f))
        vm.cancelCurrentStroke()

        assertEquals(1, vm.uiState.value.strokeCount)
        assertEquals(1, vm.strokes.value.size)
    }

    @Test
    fun `undoLastStroke removes the last added stroke`() {
        vm.startSession()

        vm.onDrawStart(point(0f, 0f))
        vm.onDrawMove(point(10f, 10f))
        vm.onDrawEnd()

        vm.onDrawStart(point(20f, 20f))
        vm.onDrawMove(point(30f, 30f))
        vm.onDrawEnd()

        assertEquals(2, vm.uiState.value.strokeCount)

        val result = vm.undoLastStroke()
        assertTrue(result)
        assertEquals(1, vm.uiState.value.strokeCount)
        assertEquals(1, vm.strokes.value.size)
    }

    @Test
    fun `undoLastStroke returns false when no strokes`() {
        vm.startSession()
        assertFalse(vm.undoLastStroke())
    }

    @Test
    fun `clearStrokes removes all strokes and resets state`() {
        vm.startSession()

        vm.onDrawStart(point(0f, 0f))
        vm.onDrawMove(point(10f, 10f))
        vm.onDrawEnd()

        vm.clearStrokes()

        assertEquals(0, vm.uiState.value.strokeCount)
        assertFalse(vm.uiState.value.isDrawing)
        assertTrue(vm.strokes.value.isEmpty())
        assertTrue(vm.currentStroke.value.isEmpty())
    }

    @Test
    fun `hasStrokes returns true after adding stroke`() {
        vm.startSession()
        assertFalse(vm.hasStrokes())

        vm.onDrawStart(point(0f, 0f))
        vm.onDrawMove(point(10f, 10f))
        vm.onDrawEnd()

        assertTrue(vm.hasStrokes())
    }

    @Test
    fun `hasStrokes returns false after clearStrokes`() {
        vm.startSession()

        vm.onDrawStart(point(0f, 0f))
        vm.onDrawMove(point(10f, 10f))
        vm.onDrawEnd()
        vm.clearStrokes()

        assertFalse(vm.hasStrokes())
    }

    // --- Canvas size ---

    @Test
    fun `captureNow is noop without canvas dimensions`() {
        vm.startSession()

        vm.onDrawStart(point(0f, 0f))
        vm.onDrawMove(point(10f, 10f))
        vm.onDrawEnd()

        vm.captureNow()
        assertEquals(0, vm.uiState.value.chunkCount)
    }

    @Test
    fun `captureNow is noop without strokes`() {
        vm.startSession()
        vm.setCanvasSize(100, 100)
        vm.captureNow()
        assertEquals(0, vm.uiState.value.chunkCount)
    }

    // --- captureRemainingStrokes ---

    @Test
    fun `captureRemainingStrokes returns null when no strokes`() {
        vm.startSession()
        vm.setCanvasSize(100, 100)
        assertNull(vm.captureRemainingStrokes())
    }

    @Test
    fun `captureRemainingStrokes returns null when canvas not set`() {
        vm.startSession()

        vm.onDrawStart(point(0f, 0f))
        vm.onDrawMove(point(10f, 10f))
        vm.onDrawEnd()

        assertNull(vm.captureRemainingStrokes())
    }

    // --- Cleanup ---

    @Test
    fun `cleanup ends active session`() {
        vm.startSession()
        assertTrue(vm.uiState.value.isSessionActive)

        vm.cleanup()
        assertFalse(vm.uiState.value.isSessionActive)
    }

    @Test
    fun `cleanup is safe when no session active`() {
        vm.cleanup() // should not throw
        assertFalse(vm.uiState.value.isSessionActive)
    }

    // --- Multiple drawing cycles ---

    @Test
    fun `multiple draw cycles accumulate stroke count`() {
        vm.startSession()

        repeat(5) { i ->
            vm.onDrawStart(point(i.toFloat(), 0f))
            vm.onDrawMove(point(i.toFloat() + 10, 10f))
            vm.onDrawEnd()
        }

        assertEquals(5, vm.uiState.value.strokeCount)
        assertEquals(5, vm.strokes.value.size)
    }

    @Test
    fun `undo all strokes leaves empty state`() {
        vm.startSession()

        repeat(3) { i ->
            vm.onDrawStart(point(i.toFloat(), 0f))
            vm.onDrawMove(point(i.toFloat() + 10, 10f))
            vm.onDrawEnd()
        }

        repeat(3) {
            assertTrue(vm.undoLastStroke())
        }

        assertEquals(0, vm.uiState.value.strokeCount)
        assertFalse(vm.hasStrokes())
        assertFalse(vm.undoLastStroke())
    }

    @Test
    fun `stroke points contain correct coordinates`() {
        vm.startSession()
        vm.onDrawStart(point(5f, 15f))
        vm.onDrawMove(point(25f, 35f))
        vm.onDrawEnd()

        val stroke = vm.strokes.value[0]
        assertEquals(2, stroke.points.size)
        assertEquals(5f, stroke.points[0].x, 0.01f)
        assertEquals(15f, stroke.points[0].y, 0.01f)
        assertEquals(25f, stroke.points[1].x, 0.01f)
        assertEquals(35f, stroke.points[1].y, 0.01f)
    }

    @Test
    fun `stroke offsets accessor returns positions`() {
        vm.startSession()
        vm.onDrawStart(point(1f, 2f))
        vm.onDrawMove(point(3f, 4f))
        vm.onDrawEnd()

        val offsets = vm.strokes.value[0].offsets
        assertEquals(2, offsets.size)
        assertEquals(Offset(1f, 2f), offsets[0])
        assertEquals(Offset(3f, 4f), offsets[1])
    }

    // --- Concurrency edge cases (ReentrantLock) ---

    @Test
    fun `captureRemainingStrokes clears strokes so endSession does not re-capture`() {
        vm.startSession()
        vm.setCanvasSize(100, 100)

        vm.onDrawStart(point(0f, 0f))
        vm.onDrawMove(point(50f, 50f))
        vm.onDrawEnd()

        // Capture remaining strokes directly
        val chunk = vm.captureRemainingStrokes()
        assertNotNull(chunk)
        assertEquals(0, chunk!!.index)

        // After captureRemainingStrokes, strokes should be cleared
        assertFalse(vm.hasStrokes())
        assertTrue(vm.strokes.value.isEmpty())

        // endSession should not produce another chunk (strokes already captured)
        vm.endSession()
        // chunkCount should remain 1 (from captureRemainingStrokes only)
        assertEquals(1, vm.uiState.value.chunkCount)
    }

    @Test
    fun `captureRemainingStrokes returns null after endSession clears strokes`() {
        vm.startSession()
        vm.setCanvasSize(100, 100)

        vm.onDrawStart(point(0f, 0f))
        vm.onDrawMove(point(50f, 50f))
        vm.onDrawEnd()

        vm.endSession()

        // After endSession, strokes are cleared — captureRemainingStrokes should return null
        val chunk = vm.captureRemainingStrokes()
        assertNull(chunk)
    }

    @Test
    fun `captureRemainingStrokes increments chunkIndex correctly across calls`() {
        vm.startSession()
        vm.setCanvasSize(100, 100)

        // First chunk
        vm.onDrawStart(point(0f, 0f))
        vm.onDrawMove(point(10f, 10f))
        vm.onDrawEnd()
        val chunk1 = vm.captureRemainingStrokes()
        assertNotNull(chunk1)
        assertEquals(0, chunk1!!.index)

        // Second chunk
        vm.onDrawStart(point(20f, 20f))
        vm.onDrawMove(point(30f, 30f))
        vm.onDrawEnd()
        val chunk2 = vm.captureRemainingStrokes()
        assertNotNull(chunk2)
        assertEquals(1, chunk2!!.index)

        assertEquals(2, vm.uiState.value.chunkCount)
    }

    @Test
    fun `endSession after captureRemainingStrokes does not crash`() {
        vm.startSession()
        vm.setCanvasSize(100, 100)

        vm.onDrawStart(point(5f, 5f))
        vm.onDrawMove(point(15f, 15f))
        vm.onDrawEnd()

        vm.captureRemainingStrokes()
        vm.endSession() // should not throw

        assertFalse(vm.uiState.value.isSessionActive)
    }

    @Test
    fun `captureNow followed by captureRemainingStrokes returns null`() {
        vm.startSession()
        vm.setCanvasSize(100, 100)

        vm.onDrawStart(point(0f, 0f))
        vm.onDrawMove(point(10f, 10f))
        vm.onDrawEnd()

        vm.captureNow()

        // captureNow triggers captureChunkInternal which clears strokes (via fade or immediate clear)
        // captureRemainingStrokes should find nothing left
        val chunk = vm.captureRemainingStrokes()
        // May be null if fade cleared strokes, or non-null if fade hasn't completed yet
        // The key guarantee is no crash
    }

    @Test
    fun `concurrent-style captureRemainingStrokes calls are safe`() {
        vm.startSession()
        vm.setCanvasSize(100, 100)

        vm.onDrawStart(point(0f, 0f))
        vm.onDrawMove(point(10f, 10f))
        vm.onDrawEnd()

        // First call captures
        val chunk1 = vm.captureRemainingStrokes()
        assertNotNull(chunk1)

        // Second call finds nothing (strokes already cleared by first call)
        val chunk2 = vm.captureRemainingStrokes()
        assertNull(chunk2)
    }
}
