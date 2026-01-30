package com.synapse.ui.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path

/**
 * A point in a stroke with pressure and timestamp metadata.
 *
 * Preserves Offset compatibility via [position] and adds pressure/timestamp
 * for variable-width rendering and velocity calculations.
 */
data class StrokePoint(
    val position: Offset,
    val pressure: Float = 0.5f,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** Convenience accessors for Offset compatibility */
    val x: Float get() = position.x
    val y: Float get() = position.y
}

/**
 * Smooths strokes using Catmull-Rom spline interpolation and provides
 * velocity-based width variation (fast strokes = thin, slow strokes = thick).
 */
object StrokeSmoother {

    /** Minimum stroke width multiplier */
    private const val MIN_WIDTH_FACTOR = 0.4f

    /** Maximum stroke width multiplier */
    private const val MAX_WIDTH_FACTOR = 1.6f

    /** Smoothing tension for Catmull-Rom splines (0.0 = sharp, 1.0 = loose) */
    private const val TENSION = 0.5f

    /**
     * Generates width values for each point based on velocity between consecutive points.
     * Fast movement → thin line; slow movement → thick line.
     *
     * @param points The stroke points with timestamps
     * @param baseWidth The base stroke width
     * @return List of width values, one per point
     */
    fun calculateWidths(points: List<StrokePoint>, baseWidth: Float): List<Float> {
        if (points.size < 2) return points.map { it.pressure * baseWidth }

        val widths = mutableListOf<Float>()

        for (i in points.indices) {
            val velocity = if (i == 0) {
                velocityBetween(points[0], points[1])
            } else {
                velocityBetween(points[i - 1], points[i])
            }

            // Map velocity to width: high velocity → low width factor
            // Typical handwriting velocity range: 100-2000 px/s
            val velocityFactor = (velocity / 1500f).coerceIn(0f, 1f)
            val widthFactor = MAX_WIDTH_FACTOR - velocityFactor * (MAX_WIDTH_FACTOR - MIN_WIDTH_FACTOR)

            // Blend with pressure
            val pressureFactor = points[i].pressure.coerceIn(0.1f, 1f)
            widths.add(baseWidth * widthFactor * pressureFactor)
        }

        return widths
    }

    /**
     * Creates a smooth path from stroke points using Catmull-Rom spline interpolation.
     *
     * @param points The stroke points
     * @return A smooth [Path]
     */
    fun createSmoothPath(points: List<StrokePoint>): Path {
        val path = Path()
        if (points.isEmpty()) return path

        val positions = points.map { it.position }
        path.moveTo(positions[0].x, positions[0].y)

        if (positions.size == 1) {
            path.lineTo(positions[0].x + 0.1f, positions[0].y + 0.1f)
            return path
        }

        if (positions.size == 2) {
            path.lineTo(positions[1].x, positions[1].y)
            return path
        }

        // Catmull-Rom spline: for each segment, use the surrounding 4 points
        for (i in 0 until positions.size - 1) {
            val p0 = positions[maxOf(0, i - 1)]
            val p1 = positions[i]
            val p2 = positions[minOf(positions.size - 1, i + 1)]
            val p3 = positions[minOf(positions.size - 1, i + 2)]

            // Convert Catmull-Rom to cubic Bezier control points
            val cp1x = p1.x + (p2.x - p0.x) * TENSION / 3f
            val cp1y = p1.y + (p2.y - p0.y) * TENSION / 3f
            val cp2x = p2.x - (p3.x - p1.x) * TENSION / 3f
            val cp2y = p2.y - (p3.y - p1.y) * TENSION / 3f

            path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
        }

        return path
    }

    /**
     * Creates a smooth path from simple Offset points (backward-compatible).
     * Uses quadratic bezier (same as the original CaptureCanvas logic).
     */
    fun createSmoothPath(points: List<Offset>): Path {
        val path = Path()
        if (points.isEmpty()) return path

        path.moveTo(points[0].x, points[0].y)

        if (points.size == 1) {
            path.lineTo(points[0].x + 0.1f, points[0].y + 0.1f)
            return path
        }

        if (points.size == 2) {
            path.lineTo(points[1].x, points[1].y)
            return path
        }

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val current = points[i]
            val midX = (prev.x + current.x) / 2
            val midY = (prev.y + current.y) / 2
            path.quadraticBezierTo(prev.x, prev.y, midX, midY)
        }

        val lastPoint = points.last()
        path.lineTo(lastPoint.x, lastPoint.y)

        return path
    }

    private fun velocityBetween(a: StrokePoint, b: StrokePoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        val dt = (b.timestamp - a.timestamp).coerceAtLeast(1)
        return dist / dt * 1000f // pixels per second
    }
}
