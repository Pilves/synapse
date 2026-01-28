package com.synapse.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
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
 * - Stylus preference over finger input
 *
 * @param viewModel The CaptureViewModel managing state
 * @param modifier Modifier for the canvas
 * @param strokeConfig Configuration for stroke appearance
 * @param preferStylus If true, ignores finger input when stylus has been used
 * @param onInputTypeChanged Callback when input type changes
 */
@Composable
fun CaptureCanvas(
    viewModel: CaptureViewModel,
    modifier: Modifier = Modifier,
    strokeConfig: StrokeConfig = StrokeConfig(),
    preferStylus: Boolean = true,
    onInputTypeChanged: ((InputType) -> Unit)? = null
) {
    val strokes by viewModel.strokes.collectAsState()
    val currentStroke by viewModel.currentStroke.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    var lastInputType by remember { mutableStateOf(InputType.UNKNOWN) }
    var stylusUsedInSession by remember { mutableStateOf(false) }

    // Reset stylus tracking when session ends
    LaunchedEffect(uiState.isSessionActive) {
        if (!uiState.isSessionActive) {
            stylusUsedInSession = false
        }
    }

    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .onSizeChanged { size ->
                viewModel.setCanvasSize(size.width, size.height)
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Wait for first touch/pen down
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val inputType = getInputType(down)

                    // Track if stylus has been used
                    if (inputType == InputType.STYLUS) {
                        stylusUsedInSession = true
                    }

                    // If preferStylus is enabled and stylus has been used, ignore finger input
                    if (preferStylus && stylusUsedInSession && inputType == InputType.FINGER) {
                        // Consume but ignore finger input
                        return@awaitEachGesture
                    }

                    if (inputType != lastInputType) {
                        lastInputType = inputType
                        onInputTypeChanged?.invoke(inputType)
                    }

                    // Start drawing
                    viewModel.onDrawStart(down.position)
                    down.consume()

                    // Track pointer movement
                    var lastPosition = down.position

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }

                        if (change == null || !change.pressed) {
                            // Pointer lifted
                            viewModel.onDrawEnd(strokeConfig.strokeWidth)
                            break
                        }

                        // Only add point if it moved significantly (reduces jitter)
                        val currentPos = change.position
                        val distance = (currentPos - lastPosition).getDistance()

                        if (distance > 1f) {
                            viewModel.onDrawMove(currentPos)
                            lastPosition = currentPos
                        }

                        change.consume()
                    }
                }
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Apply fade animation
                    alpha = uiState.fadeProgress
                }
        ) {
            // Draw completed strokes
            for (stroke in strokes) {
                drawStrokeWithOutline(
                    points = stroke.points,
                    strokeWidth = stroke.strokeWidth,
                    strokeColor = strokeConfig.strokeColor,
                    outlineColor = strokeConfig.outlineColor,
                    outlineWidth = strokeConfig.outlineWidth
                )
            }

            // Draw current stroke being drawn
            if (currentStroke.isNotEmpty()) {
                drawStrokeWithOutline(
                    points = currentStroke,
                    strokeWidth = strokeConfig.strokeWidth,
                    strokeColor = strokeConfig.strokeColor,
                    outlineColor = strokeConfig.outlineColor,
                    outlineWidth = strokeConfig.outlineWidth
                )
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
        PointerType.Finger -> InputType.FINGER
        PointerType.Mouse -> InputType.MOUSE
        else -> InputType.UNKNOWN
    }
}

/**
 * Draws a stroke with an outline for visibility on any background.
 */
private fun DrawScope.drawStrokeWithOutline(
    points: List<Offset>,
    strokeWidth: Float,
    strokeColor: Color,
    outlineColor: Color,
    outlineWidth: Float
) {
    if (points.size < 2) return

    val path = createSmoothPath(points)

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

/**
 * Creates a smooth path from a list of points using quadratic bezier curves.
 */
private fun createSmoothPath(points: List<Offset>): Path {
    val path = Path()

    if (points.isEmpty()) return path

    path.moveTo(points[0].x, points[0].y)

    if (points.size == 1) {
        // Single point - draw a small line to make it visible
        path.lineTo(points[0].x + 0.1f, points[0].y + 0.1f)
        return path
    }

    if (points.size == 2) {
        // Two points - draw a straight line
        path.lineTo(points[1].x, points[1].y)
        return path
    }

    // Use quadratic bezier curves for smooth interpolation
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val current = points[i]

        // Calculate midpoint for smoother curves
        val midX = (prev.x + current.x) / 2
        val midY = (prev.y + current.y) / 2

        path.quadraticBezierTo(prev.x, prev.y, midX, midY)
    }

    // Ensure we end at the last point
    val lastPoint = points.last()
    path.lineTo(lastPoint.x, lastPoint.y)

    return path
}

/**
 * A simplified capture canvas that manages its own state internally.
 * Useful for standalone usage without external ViewModel.
 *
 * @param modifier Modifier for the canvas
 * @param strokeConfig Configuration for stroke appearance
 * @param onChunkCaptured Callback when a chunk is captured
 * @param onSessionEnded Callback when session ends
 */
@Composable
fun SimpleCaptureCanvas(
    modifier: Modifier = Modifier,
    strokeConfig: StrokeConfig = StrokeConfig(),
    chunkTimeoutMs: Long = CaptureViewModel.DEFAULT_CHUNK_TIMEOUT_MS,
    onChunkCaptured: (CapturedChunk) -> Unit = {},
    onSessionEnded: () -> Unit = {}
) {
    val viewModel = remember { CaptureViewModel() }

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
        strokeConfig = strokeConfig
    )
}
