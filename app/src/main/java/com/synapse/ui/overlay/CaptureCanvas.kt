package com.synapse.ui.overlay

import android.graphics.Rect
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity

/**
 * Input type for the capture canvas.
 */
enum class InputType {
    STYLUS,
    FINGER,
    MOUSE,
    UNKNOWN
}

/**
 * Input mode determining how finger and stylus inputs are handled.
 */
enum class InputMode {
    /**
     * Stylus writes, finger passes through to app below (for scrolling).
     * This is the recommended mode for note-taking over other apps.
     */
    STYLUS_WRITE_FINGER_SCROLL,

    /**
     * Both stylus and finger can write. No pass-through.
     */
    BOTH_WRITE,

    /**
     * Only stylus can write. Finger is ignored entirely.
     */
    STYLUS_ONLY
}

/**
 * Configuration for stroke appearance.
 */
data class StrokeConfig(
    val strokeWidth: Float = 4f,
    val strokeColor: Color = Color.White,
    val outlineColor: Color = Color(0xFF282828),
    val outlineWidth: Float = 2f
)

/**
 * A Jetpack Compose canvas for capturing handwriting input.
 *
 * Features:
 * - Transparent background allowing content behind to be visible
 * - Stylus/finger stroke capture with smooth rendering
 * - White strokes with dark outline for visibility on any background
 * - Fade animation support when chunks are captured
 * - Configurable input mode: stylus-only, both, or stylus-write/finger-scroll
 *
 * @param viewModel The CaptureViewModel managing state
 * @param modifier Modifier for the canvas
 * @param strokeConfig Configuration for stroke appearance
 * @param inputMode How to handle stylus vs finger input
 * @param onInputTypeChanged Callback when input type changes
 * @param onFingerTouchPassThrough Callback when finger touch should pass through (for scrolling)
 * @param regionSelectionEnabled Whether hold-and-drag region selection is enabled
 * @param onRegionSelected Callback when a screen region is selected via hold-and-drag
 * @param onVibrate Callback to trigger haptic feedback when region selection activates
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CaptureCanvas(
    viewModel: CaptureViewModel,
    modifier: Modifier = Modifier,
    strokeConfig: StrokeConfig = StrokeConfig(),
    inputMode: InputMode = InputMode.STYLUS_WRITE_FINGER_SCROLL,
    palmRejectionEnabled: Boolean = true,
    onInputTypeChanged: ((InputType) -> Unit)? = null,
    onFingerTouchPassThrough: (() -> Unit)? = null,
    regionSelectionEnabled: Boolean = false,
    onRegionSelected: ((Rect) -> Unit)? = null,
    onVibrate: (() -> Unit)? = null
) {
    val strokes by viewModel.strokes.collectAsState()
    val currentStroke by viewModel.currentStroke.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    var lastInputType by remember { mutableStateOf(InputType.UNKNOWN) }
    var selectionRect by remember { mutableStateOf<Rect?>(null) }

    // Region gesture detector for hold-and-drag selection
    val regionGestureDetector = remember(regionSelectionEnabled) {
        if (regionSelectionEnabled) {
            RegionGestureDetector(
                onRegionSelected = { rect ->
                    onRegionSelected?.invoke(rect)
                },
                onVibrate = { onVibrate?.invoke() }
            )
        } else null
    }

    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .onSizeChanged { size ->
                viewModel.setCanvasSize(size.width, size.height)
            }
            .then(
                if (regionSelectionEnabled && regionGestureDetector != null) {
                    // Region mode: only handle region selection, no stroke drawing
                    Modifier.pointerInteropFilter { motionEvent ->
                        val result = regionGestureDetector.onTouchEvent(motionEvent)
                        when (result) {
                            is RegionGestureResult.SelectionInProgress -> {
                                selectionRect = result.rect
                                true
                            }
                            is RegionGestureResult.SelectionComplete -> {
                                selectionRect = null
                                true
                            }
                            is RegionGestureResult.SelectionCancelled -> {
                                selectionRect = null
                                true
                            }
                            is RegionGestureResult.Pending -> {
                                true
                            }
                            is RegionGestureResult.Stroke -> {
                                selectionRect = null
                                true
                            }
                            is RegionGestureResult.Ignored -> false
                        }
                    }
                } else {
                    // Write mode: handle stroke drawing
                    Modifier.pointerInput(inputMode) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val inputType = getInputType(down)

                            val shouldHandle = when (inputMode) {
                                InputMode.STYLUS_WRITE_FINGER_SCROLL -> {
                                    inputType == InputType.STYLUS
                                }
                                InputMode.STYLUS_ONLY -> {
                                    inputType == InputType.STYLUS
                                }
                                InputMode.BOTH_WRITE -> {
                                    inputType == InputType.STYLUS || inputType == InputType.FINGER
                                }
                            }

                            if (!shouldHandle) {
                                if (inputMode == InputMode.STYLUS_WRITE_FINGER_SCROLL && inputType == InputType.FINGER) {
                                    onFingerTouchPassThrough?.invoke()
                                }
                                return@awaitEachGesture
                            }

                            if (inputType != lastInputType) {
                                lastInputType = inputType
                                onInputTypeChanged?.invoke(inputType)
                            }

                            val startPoint = StrokePoint(
                                position = down.position,
                                pressure = down.pressure,
                                timestamp = System.currentTimeMillis()
                            )
                            viewModel.onDrawStart(startPoint)
                            down.consume()

                            var lastPosition = down.position

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }

                                if (change == null || !change.pressed) {
                                    viewModel.onDrawEnd(strokeConfig.strokeWidth)
                                    break
                                }

                                val currentPos = change.position
                                val distance = (currentPos - lastPosition).getDistance()

                                if (distance > 1f) {
                                    val movePoint = StrokePoint(
                                        position = currentPos,
                                        pressure = change.pressure,
                                        timestamp = System.currentTimeMillis()
                                    )
                                    viewModel.onDrawMove(movePoint)
                                    lastPosition = currentPos
                                }

                                change.consume()
                            }
                        }
                    }
                }
            )
    ) {
        // Cache Path objects and widths for completed strokes to avoid recreating on every frame
        val cachedPaths = remember(strokes) {
            strokes.map { stroke ->
                if (stroke.points.size >= 2) {
                    StrokeSmoother.createSmoothPath(stroke.points) to
                        StrokeSmoother.calculateWidths(stroke.points, stroke.strokeWidth).average().toFloat()
                } else null
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Apply fade animation
                    alpha = uiState.fadeProgress
                }
        ) {
            // Draw completed strokes using cached paths
            for ((index, stroke) in strokes.withIndex()) {
                val (path, avgWidth) = cachedPaths.getOrNull(index) ?: continue
                drawPathWithOutline(
                    path = path,
                    strokeWidth = avgWidth,
                    strokeColor = strokeConfig.strokeColor,
                    outlineColor = strokeConfig.outlineColor,
                    outlineWidth = strokeConfig.outlineWidth
                )
            }

            // Draw current stroke being drawn (with smooth path)
            if (currentStroke.isNotEmpty()) {
                drawStrokePointsWithOutline(
                    points = currentStroke,
                    baseStrokeWidth = strokeConfig.strokeWidth,
                    strokeColor = strokeConfig.strokeColor,
                    outlineColor = strokeConfig.outlineColor,
                    outlineWidth = strokeConfig.outlineWidth
                )
            }

            // Draw region selection rectangle
            selectionRect?.let { rect ->
                drawRegionSelection(rect)
            }
        }
    }
}

/**
 * Determines the input type from a pointer input change.
 */
private fun getInputType(change: PointerInputChange): InputType {
    return when (change.type) {
        PointerType.Stylus -> InputType.STYLUS
        PointerType.Touch -> InputType.FINGER
        PointerType.Mouse -> InputType.MOUSE
        else -> InputType.UNKNOWN
    }
}

/**
 * Draws a stroke from StrokePoints with an outline, using smoothing and velocity-based width.
 */
private fun DrawScope.drawStrokePointsWithOutline(
    points: List<StrokePoint>,
    baseStrokeWidth: Float,
    strokeColor: Color,
    outlineColor: Color,
    outlineWidth: Float
) {
    if (points.size < 2) return

    val path = StrokeSmoother.createSmoothPath(points)
    val avgWidth = StrokeSmoother.calculateWidths(points, baseStrokeWidth).average().toFloat()

    drawPath(
        path = path,
        color = outlineColor,
        style = Stroke(
            width = avgWidth + outlineWidth * 2,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )

    drawPath(
        path = path,
        color = strokeColor,
        style = Stroke(
            width = avgWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

/**
 * Draws a pre-built path with an outline for visibility on any background.
 * Used for completed strokes with cached Path objects.
 */
private fun DrawScope.drawPathWithOutline(
    path: Path,
    strokeWidth: Float,
    strokeColor: Color,
    outlineColor: Color,
    outlineWidth: Float
) {
    // Draw outline first (thicker, behind the main stroke)
    drawPath(
        path = path,
        color = outlineColor,
        style = Stroke(
            width = strokeWidth + outlineWidth * 2,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )

    // Draw main stroke on top
    drawPath(
        path = path,
        color = strokeColor,
        style = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

// Smooth path creation is now handled by StrokeSmoother

/**
 * Draws a dashed selection rectangle with a semi-transparent fill
 * to indicate the region being selected.
 */
private fun DrawScope.drawRegionSelection(rect: Rect) {
    val topLeft = Offset(rect.left.toFloat(), rect.top.toFloat())
    val size = Size(rect.width().toFloat(), rect.height().toFloat())

    // Semi-transparent fill
    drawRect(
        color = Color(0x3300AAFF),
        topLeft = topLeft,
        size = size
    )

    // Dashed border
    drawRect(
        color = Color(0xFF00AAFF),
        topLeft = topLeft,
        size = size,
        style = Stroke(
            width = 3f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
        )
    )

    // Corner handles
    val handleSize = 16f
    val handleColor = Color(0xFF00AAFF)
    val corners = listOf(
        topLeft,
        Offset(rect.right.toFloat() - handleSize, rect.top.toFloat()),
        Offset(rect.left.toFloat(), rect.bottom.toFloat() - handleSize),
        Offset(rect.right.toFloat() - handleSize, rect.bottom.toFloat() - handleSize)
    )
    for (corner in corners) {
        drawRect(
            color = handleColor,
            topLeft = corner,
            size = Size(handleSize, handleSize)
        )
    }
}

/**
 * A simplified capture canvas that manages its own state internally.
 * Useful for standalone usage without external ViewModel.
 *
 * @param modifier Modifier for the canvas
 * @param strokeConfig Configuration for stroke appearance
 * @param inputMode How to handle stylus vs finger input (default: stylus writes, finger scrolls)
 * @param onChunkCaptured Callback when a chunk is captured
 * @param onSessionEnded Callback when session ends
 */
@Composable
fun SimpleCaptureCanvas(
    modifier: Modifier = Modifier,
    strokeConfig: StrokeConfig = StrokeConfig(),
    inputMode: InputMode = InputMode.STYLUS_WRITE_FINGER_SCROLL,
    chunkTimeoutMs: Long = CaptureViewModel.DEFAULT_CHUNK_TIMEOUT_MS,
    onChunkCaptured: (CapturedChunk) -> Unit = {},
    onSessionEnded: () -> Unit = {}
) {
    val viewModel = remember { CaptureViewModel() }

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.cleanup()
        }
    }

    LaunchedEffect(chunkTimeoutMs) {
        viewModel.setChunkTimeout(chunkTimeoutMs)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is CaptureEvent.ChunkCaptured -> onChunkCaptured(event.chunk)
                is CaptureEvent.SessionEnded -> onSessionEnded()
                is CaptureEvent.SessionTimeout -> onSessionEnded()
                is CaptureEvent.Error -> { /* Handle error if needed */ }
            }
        }
    }

    CaptureCanvas(
        viewModel = viewModel,
        modifier = modifier,
        strokeConfig = strokeConfig,
        inputMode = inputMode
    )
}
