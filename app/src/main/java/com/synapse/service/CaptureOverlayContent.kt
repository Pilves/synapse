package com.synapse.service

import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.synapse.R
import com.synapse.ui.overlay.CaptureCanvas
import com.synapse.ui.overlay.CaptureViewModel
import com.synapse.ui.overlay.InputMode
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Fullscreen capture overlay content with canvas and toolbar.
 */
@Composable
internal fun CaptureOverlayContent(
    viewModel: CaptureViewModel,
    chunkTimeoutMs: Long,
    isRegionMode: Boolean = false,
    initialToolbarX: Float = 0f,
    initialToolbarY: Float = 100f,
    capturedTextPreview: String? = null,
    onClearPreview: (() -> Unit)? = null,
    onMinimize: () -> Unit,
    onDone: () -> Unit,
    onDiscard: () -> Unit,
    onToggleRegionMode: (() -> Unit)? = null,
    onRegionSelected: ((Rect) -> Unit)? = null,
    onVibrate: (() -> Unit)? = null
) {
    val density = LocalDensity.current
    var toolbarWidth by remember { mutableFloatStateOf(0f) }
    var toolbarHeight by remember { mutableFloatStateOf(0f) }

    // Fade-in animation for overlay appearance
    var overlayVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { overlayVisible = true }
    val overlayAlpha by animateFloatAsState(
        targetValue = if (overlayVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "overlayFadeIn"
    )

    // Chunk timeout countdown progress
    val captureState by viewModel.uiState.collectAsState()
    var chunkTimeoutProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(captureState.isDrawing, captureState.strokeCount) {
        // Reset and start countdown when user stops drawing (strokeCount changes and not drawing)
        if (!captureState.isDrawing && captureState.strokeCount > 0) {
            chunkTimeoutProgress = 0f
            val steps = (chunkTimeoutMs / 100).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                delay(100)
                chunkTimeoutProgress = i.toFloat() / steps
            }
        } else {
            chunkTimeoutProgress = 0f
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .alpha(overlayAlpha)
            .background(Color.Transparent)
    ) {
        val screenWidthPx = constraints.maxWidth.toFloat()
        val screenHeightPx = constraints.maxHeight.toFloat()

        // Clamp initial position: if toolbar would go off-screen, flip to opposite side
        val clampedX = remember(initialToolbarX, toolbarWidth, screenWidthPx) {
            if (toolbarWidth > 0f && initialToolbarX + toolbarWidth > screenWidthPx) {
                // Flip to left side of bubble position
                (initialToolbarX - toolbarWidth).coerceAtLeast(0f)
            } else {
                initialToolbarX.coerceAtLeast(0f)
            }
        }
        val clampedY = remember(initialToolbarY, toolbarHeight, screenHeightPx) {
            if (toolbarHeight > 0f && initialToolbarY + toolbarHeight > screenHeightPx) {
                (screenHeightPx - toolbarHeight).coerceAtLeast(0f)
            } else {
                initialToolbarY.coerceAtLeast(0f)
            }
        }

        var toolbarOffsetX by remember(clampedX) { mutableFloatStateOf(clampedX) }
        var toolbarOffsetY by remember(clampedY) { mutableFloatStateOf(clampedY) }
        // Apply chunk timeout setting to viewModel
        LaunchedEffect(chunkTimeoutMs) {
            viewModel.setChunkTimeout(chunkTimeoutMs)
        }

        // Transparent capture canvas - both stylus and finger can write
        CaptureCanvas(
            viewModel = viewModel,
            inputMode = InputMode.BOTH_WRITE,
            onInputTypeChanged = { inputType ->
                // Could show indicator for input type
            },
            onFingerTouchPassThrough = {
                // Finger touch is passing through to app below
            },
            regionSelectionEnabled = isRegionMode,
            onRegionSelected = onRegionSelected,
            onVibrate = onVibrate
        )

        // Floating toolbar (draggable)
        Box(
            modifier = Modifier
                .offset { IntOffset(toolbarOffsetX.roundToInt(), toolbarOffsetY.roundToInt()) }
                .onGloballyPositioned { coords ->
                    toolbarWidth = coords.size.width.toFloat()
                    toolbarHeight = coords.size.height.toFloat()
                }
                .padding(16.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.medium
                )
                .padding(8.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        toolbarOffsetX = (toolbarOffsetX + dragAmount.x)
                            .coerceIn(0f, (screenWidthPx - toolbarWidth).coerceAtLeast(0f))
                        toolbarOffsetY = (toolbarOffsetY + dragAmount.y)
                            .coerceIn(0f, (screenHeightPx - toolbarHeight).coerceAtLeast(0f))
                    }
                }
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Minimize - hide overlay, keep session (tap bubble to continue)
                SmallFloatingActionButton(
                    onClick = onMinimize,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.cd_minimize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Undo last stroke
                SmallFloatingActionButton(
                    onClick = { viewModel.undoLastStroke() },
                    containerColor = MaterialTheme.colorScheme.secondary
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = stringResource(R.string.cd_undo),
                        tint = MaterialTheme.colorScheme.onSecondary
                    )
                }

                // Toggle region capture mode
                SmallFloatingActionButton(
                    onClick = { onToggleRegionMode?.invoke() },
                    containerColor = if (isRegionMode)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Icon(
                        imageVector = Icons.Default.Crop,
                        contentDescription = if (isRegionMode) stringResource(R.string.cd_exit_region_mode) else stringResource(R.string.cd_region_select),
                        tint = if (isRegionMode)
                            MaterialTheme.colorScheme.onTertiary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Done - end session and save
                SmallFloatingActionButton(
                    onClick = onDone,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_end_session),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }

                // Delete last item - removes latest scribble/image from session
                SmallFloatingActionButton(
                    onClick = onDiscard,
                    containerColor = MaterialTheme.colorScheme.error
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.cd_delete_last_item),
                        tint = MaterialTheme.colorScheme.onError
                    )
                }

                // Chunk timeout countdown indicator
                if (chunkTimeoutProgress > 0f && chunkTimeoutProgress < 1f) {
                    CircularProgressIndicator(
                        progress = { chunkTimeoutProgress },
                        modifier = Modifier
                            .size(20.dp)
                            .align(Alignment.CenterVertically),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                }
            }
        }

        // Captured text preview chip - persistent until user dismisses or next chunk
        AnimatedVisibility(
            visible = capturedTextPreview != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .background(
                        color = Color.Black.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (capturedTextPreview?.startsWith("Draw") == true || capturedTextPreview?.startsWith("[") == true) {
                        capturedTextPreview
                    } else {
                        "\u2713 Selected: ${capturedTextPreview ?: ""}"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { onClearPreview?.invoke() },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_dismiss_preview),
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
