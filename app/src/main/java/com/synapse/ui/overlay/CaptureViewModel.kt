package com.synapse.ui.overlay

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Represents a captured chunk of handwriting.
 *
 * @param bitmap The rendered bitmap of the strokes
 * @param timestamp The time when the chunk was created
 * @param index The index of this chunk in the session
 *
 * Not a data class to avoid Bitmap equality comparison. Consumer must call bitmap.recycle() after persisting.
 */
class CapturedChunk(
    val bitmap: Bitmap,
    val timestamp: Long,
    val index: Int
)

/**
 * UI state for the capture canvas.
 */
data class CaptureUiState(
    val isDrawing: Boolean = false,
    val strokeCount: Int = 0,
    val sessionDurationMs: Long = 0L,
    val isFading: Boolean = false,
    val fadeProgress: Float = 1f,
    val isSessionActive: Boolean = false,
    val chunkCount: Int = 0
)

/**
 * Events emitted by the capture view model.
 */
sealed class CaptureEvent {
    data class ChunkCaptured(val chunk: CapturedChunk) : CaptureEvent()
    data object SessionEnded : CaptureEvent()
    data object SessionTimeout : CaptureEvent()
    data class Error(val message: String) : CaptureEvent()
}

/**
 * ViewModel for managing capture canvas state.
 *
 * Handles stroke management, chunk creation based on timeouts,
 * session timing, and fade animations.
 */
class CaptureViewModel : ViewModel() {

    companion object {
        /** Default timeout after pen lift before creating a chunk (in milliseconds) */
        const val DEFAULT_CHUNK_TIMEOUT_MS = 1000L

        /** Default session timeout for inactivity (in milliseconds) - 15 minutes */
        const val DEFAULT_SESSION_TIMEOUT_MS = 15 * 60 * 1000L

        /** Duration of the fade animation after chunk creation (in milliseconds) */
        const val FADE_ANIMATION_DURATION_MS = 200L

        /** Interval for session timer updates (in milliseconds) */
        private const val SESSION_TIMER_INTERVAL_MS = 1000L
    }

    private val strokeManager = StrokeManager()

    // UI State
    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    // Events
    private val _events = MutableSharedFlow<CaptureEvent>()
    val events = _events.asSharedFlow()

    // Strokes for canvas rendering
    private val _strokes = MutableStateFlow<List<Stroke>>(emptyList())
    val strokes: StateFlow<List<Stroke>> = _strokes.asStateFlow()

    // Current stroke being drawn
    private val _currentStroke = MutableStateFlow<List<Offset>>(emptyList())
    val currentStroke: StateFlow<List<Offset>> = _currentStroke.asStateFlow()

    // Configuration
    var chunkTimeoutMs: Long = DEFAULT_CHUNK_TIMEOUT_MS
        private set
    var sessionTimeoutMs: Long = DEFAULT_SESSION_TIMEOUT_MS
        private set

    // Timing state
    private var chunkTimeoutJob: Job? = null
    private var sessionTimeoutJob: Job? = null
    private var sessionTimerJob: Job? = null
    private var fadeAnimationJob: Job? = null
    private var sessionStartTime: Long = 0L
    private var chunkIndex: Int = 0

    // Canvas dimensions for bitmap generation
    private var canvasWidth: Int = 0
    private var canvasHeight: Int = 0

    /**
     * Sets the canvas dimensions for bitmap generation.
     *
     * @param width The canvas width in pixels
     * @param height The canvas height in pixels
     */
    fun setCanvasSize(width: Int, height: Int) {
        canvasWidth = width
        canvasHeight = height
    }

    /**
     * Configures the chunk timeout duration.
     *
     * @param timeoutMs The timeout in milliseconds
     */
    fun setChunkTimeout(timeoutMs: Long) {
        chunkTimeoutMs = timeoutMs
    }

    /**
     * Configures the session timeout duration.
     *
     * @param timeoutMs The timeout in milliseconds
     */
    fun setSessionTimeout(timeoutMs: Long) {
        sessionTimeoutMs = timeoutMs
    }

    /**
     * Starts a new capture session.
     */
    fun startSession() {
        if (_uiState.value.isSessionActive) return

        sessionStartTime = System.currentTimeMillis()
        chunkIndex = 0

        _uiState.update { CaptureUiState(isSessionActive = true) }
        strokeManager.clear()
        _strokes.value = emptyList()

        startSessionTimer()
        resetSessionTimeout()
    }

    /**
     * Ends the current capture session.
     * Any pending strokes will be captured as a final chunk.
     */
    fun endSession() {
        if (!_uiState.value.isSessionActive) return

        // Cancel all timers and animations
        cancelAllTimers()
        fadeAnimationJob?.cancel()
        fadeAnimationJob = null

        // Capture any remaining strokes without fade (view will be removed)
        if (!strokeManager.isEmpty() && canvasWidth > 0 && canvasHeight > 0) {
            captureChunkWithoutFade()
        }

        _uiState.update { it.copy(isSessionActive = false) }

        viewModelScope.launch {
            _events.emit(CaptureEvent.SessionEnded)
        }
    }

    /**
     * Called when the user starts drawing (pen/finger down).
     *
     * @param offset The starting position of the stroke
     */
    fun onDrawStart(offset: Offset) {
        if (!_uiState.value.isSessionActive) {
            startSession()
        }

        // Cancel chunk timeout when user starts drawing
        chunkTimeoutJob?.cancel()
        chunkTimeoutJob = null

        // Reset session timeout
        resetSessionTimeout()

        // Cancel any ongoing fade animation
        cancelFadeAnimation()

        // Start new stroke
        _currentStroke.value = listOf(offset)
        _uiState.update { it.copy(isDrawing = true) }
    }

    /**
     * Called when the user continues drawing (pen/finger move).
     *
     * @param offset The current position of the stroke
     */
    fun onDrawMove(offset: Offset) {
        if (!_uiState.value.isDrawing) return

        _currentStroke.value = _currentStroke.value + offset
    }

    /**
     * Called when the user stops drawing (pen/finger up).
     *
     * @param strokeWidth The width of the completed stroke
     */
    fun onDrawEnd(strokeWidth: Float = 4f) {
        if (!_uiState.value.isDrawing) return

        val points = _currentStroke.value
        if (points.size >= 2) {
            val stroke = Stroke(points = points, strokeWidth = strokeWidth)
            strokeManager.addStroke(stroke)
            _strokes.value = strokeManager.getStrokes()
        }

        _currentStroke.value = emptyList()
        _uiState.update { it.copy(
            isDrawing = false,
            strokeCount = strokeManager.strokeCount()
        ) }

        // Start chunk timeout timer
        startChunkTimeout()
    }

    /**
     * Cancels the current stroke without adding it.
     */
    fun cancelCurrentStroke() {
        _currentStroke.value = emptyList()
        _uiState.update { it.copy(isDrawing = false) }
    }

    /**
     * Undoes the last stroke.
     *
     * @return true if a stroke was undone, false if there were no strokes
     */
    fun undoLastStroke(): Boolean {
        val result = strokeManager.undoLastStroke()
        if (result) {
            _strokes.value = strokeManager.getStrokes()
            _uiState.update { it.copy(
                strokeCount = strokeManager.strokeCount()
            ) }

            // Reset chunk timeout since we modified strokes
            if (!strokeManager.isEmpty()) {
                startChunkTimeout()
            } else {
                chunkTimeoutJob?.cancel()
                chunkTimeoutJob = null
            }
        }
        return result
    }

    /**
     * Clears all strokes without creating a chunk.
     */
    fun clearStrokes() {
        strokeManager.clear()
        _strokes.value = emptyList()
        _currentStroke.value = emptyList()
        _uiState.update { it.copy(
            strokeCount = 0,
            isDrawing = false
        ) }

        chunkTimeoutJob?.cancel()
        chunkTimeoutJob = null
    }

    /**
     * Forces immediate chunk capture.
     */
    fun captureNow() {
        if (strokeManager.isEmpty() || canvasWidth <= 0 || canvasHeight <= 0) return

        chunkTimeoutJob?.cancel()
        chunkTimeoutJob = null

        captureChunk()
    }

    /**
     * Checks if there are any strokes to capture.
     */
    fun hasStrokes(): Boolean = !strokeManager.isEmpty()

    private fun startChunkTimeout() {
        chunkTimeoutJob?.cancel()
        chunkTimeoutJob = viewModelScope.launch {
            delay(chunkTimeoutMs)
            if (!strokeManager.isEmpty() && canvasWidth > 0 && canvasHeight > 0) {
                captureChunk()
            }
        }
    }

    private fun captureChunk() {
        captureChunkInternal(withFade = true)
    }

    private fun captureChunkWithoutFade() {
        captureChunkInternal(withFade = false)
    }

    private fun captureChunkInternal(withFade: Boolean) {
        if (strokeManager.isEmpty()) return

        val bitmap = strokeManager.toBitmapForOcr(canvasWidth, canvasHeight)
        val chunk = CapturedChunk(
            bitmap = bitmap,
            timestamp = System.currentTimeMillis(),
            index = chunkIndex++
        )

        _uiState.update { it.copy(
            chunkCount = chunkIndex
        ) }

        // Emit the chunk
        viewModelScope.launch {
            _events.emit(CaptureEvent.ChunkCaptured(chunk))
        }

        // Start fade animation only if requested
        if (withFade) {
            startFadeAnimation()
        } else {
            // Just clear strokes without animation
            strokeManager.clear()
            _strokes.value = emptyList()
        }
    }

    private fun startFadeAnimation() {
        fadeAnimationJob?.cancel()
        fadeAnimationJob = viewModelScope.launch {
            _uiState.update { it.copy(isFading = true, fadeProgress = 1f) }

            val startTime = System.currentTimeMillis()
            val duration = FADE_ANIMATION_DURATION_MS

            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = 1f - (elapsed.toFloat() / duration).coerceIn(0f, 1f)

                _uiState.update { it.copy(fadeProgress = progress) }

                if (elapsed >= duration) break
                delay(16) // ~60fps
            }

            // Clear strokes after fade
            strokeManager.clear()
            _strokes.value = emptyList()
            _uiState.update { it.copy(
                isFading = false,
                fadeProgress = 1f,
                strokeCount = 0
            ) }
        }
    }

    private fun cancelFadeAnimation() {
        fadeAnimationJob?.cancel()
        fadeAnimationJob = null

        if (_uiState.value.isFading) {
            // If we were fading, clear immediately
            strokeManager.clear()
            _strokes.value = emptyList()
            _uiState.update { it.copy(
                isFading = false,
                fadeProgress = 1f,
                strokeCount = 0
            ) }
        }
    }

    private fun resetSessionTimeout() {
        sessionTimeoutJob?.cancel()
        sessionTimeoutJob = viewModelScope.launch {
            delay(sessionTimeoutMs)
            // Session timeout reached
            viewModelScope.launch {
                _events.emit(CaptureEvent.SessionTimeout)
            }
            endSession()
        }
    }

    private fun startSessionTimer() {
        sessionTimerJob?.cancel()
        sessionTimerJob = viewModelScope.launch {
            while (_uiState.value.isSessionActive) {
                val elapsed = System.currentTimeMillis() - sessionStartTime
                _uiState.update { it.copy(sessionDurationMs = elapsed) }
                delay(SESSION_TIMER_INTERVAL_MS)
            }
        }
    }

    private fun cancelAllTimers() {
        chunkTimeoutJob?.cancel()
        chunkTimeoutJob = null
        sessionTimeoutJob?.cancel()
        sessionTimeoutJob = null
        sessionTimerJob?.cancel()
        sessionTimerJob = null
        fadeAnimationJob?.cancel()
        fadeAnimationJob = null
    }

    override fun onCleared() {
        super.onCleared()
        cancelAllTimers()
    }
}
