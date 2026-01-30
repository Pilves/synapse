package com.synapse.ui.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.geometry.Offset

/**
 * Represents a single stroke drawn by the user.
 *
 * @param points The list of points that make up the stroke (with pressure and timestamp)
 * @param strokeWidth The base width of the stroke in pixels
 */
data class Stroke(
    val points: List<StrokePoint>,
    val strokeWidth: Float = 4f
) {
    /** Convenience accessor for Offset positions (backward-compatible). */
    val offsets: List<Offset> get() = points.map { it.position }
}

/**
 * Manages stroke data for the capture canvas.
 *
 * Provides functionality to add, undo, and convert strokes to bitmap.
 * Thread-safe for use from coroutines and UI callbacks.
 */
class StrokeManager {

    private val strokes = mutableListOf<Stroke>()
    private val lock = Any()

    // Cached bounding box, updated incrementally on addStroke
    private var boundsMinX = Float.MAX_VALUE
    private var boundsMinY = Float.MAX_VALUE
    private var boundsMaxX = Float.MIN_VALUE
    private var boundsMaxY = Float.MIN_VALUE

    /**
     * Adds a new stroke to the collection.
     *
     * @param stroke The stroke to add
     */
    fun addStroke(stroke: Stroke) {
        synchronized(lock) {
            if (stroke.points.isNotEmpty()) {
                strokes.add(stroke)
                for (point in stroke.points) {
                    boundsMinX = minOf(boundsMinX, point.position.x)
                    boundsMinY = minOf(boundsMinY, point.position.y)
                    boundsMaxX = maxOf(boundsMaxX, point.position.x)
                    boundsMaxY = maxOf(boundsMaxY, point.position.y)
                }
            }
        }
    }

    /**
     * Removes the last stroke from the collection.
     *
     * @return true if a stroke was removed, false if the collection was empty
     */
    fun undoLastStroke(): Boolean {
        synchronized(lock) {
            return if (strokes.isNotEmpty()) {
                strokes.removeAt(strokes.lastIndex)
                recalculateBounds()
                true
            } else {
                false
            }
        }
    }

    /**
     * Clears all strokes from the collection.
     */
    fun clear() {
        synchronized(lock) {
            strokes.clear()
            boundsMinX = Float.MAX_VALUE
            boundsMinY = Float.MAX_VALUE
            boundsMaxX = Float.MIN_VALUE
            boundsMaxY = Float.MIN_VALUE
        }
    }

    /**
     * Returns a copy of all strokes in the collection.
     *
     * @return A list of all strokes
     */
    fun getStrokes(): List<Stroke> {
        synchronized(lock) {
            return strokes.toList()
        }
    }

    /**
     * Checks if the stroke collection is empty.
     *
     * @return true if there are no strokes, false otherwise
     */
    fun isEmpty(): Boolean {
        synchronized(lock) {
            return strokes.isEmpty()
        }
    }

    /**
     * Returns the number of strokes in the collection.
     *
     * @return The stroke count
     */
    fun strokeCount(): Int {
        synchronized(lock) {
            return strokes.size
        }
    }

    private fun recalculateBounds() {
        boundsMinX = Float.MAX_VALUE
        boundsMinY = Float.MAX_VALUE
        boundsMaxX = Float.MIN_VALUE
        boundsMaxY = Float.MIN_VALUE
        for (stroke in strokes) {
            for (point in stroke.points) {
                boundsMinX = minOf(boundsMinX, point.position.x)
                boundsMinY = minOf(boundsMinY, point.position.y)
                boundsMaxX = maxOf(boundsMaxX, point.position.x)
                boundsMaxY = maxOf(boundsMaxY, point.position.y)
            }
        }
    }

    /**
     * Converts all strokes to a Bitmap with white strokes and dark outline
     * for visibility on any background.
     *
     * @param width The width of the output bitmap
     * @param height The height of the output bitmap
     * @return A Bitmap containing all strokes rendered with outline effect
     */
    fun toBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Paint for the dark outline (drawn first, slightly larger)
        val outlinePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = android.graphics.Color.argb(200, 40, 40, 40)
        }

        // Paint for the white stroke (drawn on top)
        val strokePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = android.graphics.Color.WHITE
        }

        synchronized(lock) {
            for (stroke in strokes) {
                if (stroke.points.size < 2) continue

                val path = buildAndroidPath(stroke.points)

                // Use average velocity-based width for bitmap rendering
                val widths = StrokeSmoother.calculateWidths(stroke.points, stroke.strokeWidth)
                val avgWidth = widths.average().toFloat()

                outlinePaint.strokeWidth = avgWidth + 2f
                canvas.drawPath(path, outlinePaint)

                strokePaint.strokeWidth = avgWidth
                canvas.drawPath(path, strokePaint)
            }
        }

        return bitmap
    }

    /**
     * Converts all strokes to a Bitmap cropped to stroke bounds and scaled for OCR.
     * Much smaller than full-screen capture - reduces API token usage.
     *
     * @param width The original canvas width
     * @param height The original canvas height
     * @param backgroundColor The background color (default: white)
     * @param strokeColor The stroke color (default: black)
     * @param maxDimension Maximum dimension for output (default: 800px)
     * @param padding Padding around strokes (default: 20px)
     * @return A Bitmap containing all strokes, cropped and scaled
     */
    fun toBitmapForOcr(
        width: Int,
        height: Int,
        backgroundColor: Int = android.graphics.Color.WHITE,
        strokeColor: Int = android.graphics.Color.BLACK,
        maxDimension: Int = 800,
        padding: Int = 20
    ): Bitmap {
        synchronized(lock) {
            // Use cached bounding box
            var minX = boundsMinX
            var minY = boundsMinY
            var maxX = boundsMaxX
            var maxY = boundsMaxY

            // Add padding and clamp to canvas bounds
            minX = maxOf(0f, minX - padding)
            minY = maxOf(0f, minY - padding)
            maxX = minOf(width.toFloat(), maxX + padding)
            maxY = minOf(height.toFloat(), maxY + padding)

            // Calculate cropped dimensions
            val cropWidth = (maxX - minX).toInt().coerceAtLeast(50)
            val cropHeight = (maxY - minY).toInt().coerceAtLeast(50)

            // Calculate scale to fit within maxDimension
            val scale = if (cropWidth > maxDimension || cropHeight > maxDimension) {
                maxDimension.toFloat() / maxOf(cropWidth, cropHeight)
            } else {
                1f
            }

            val outputWidth = (cropWidth * scale).toInt()
            val outputHeight = (cropHeight * scale).toInt()

            val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(backgroundColor)

            // Scale and translate to crop region
            canvas.scale(scale, scale)
            canvas.translate(-minX, -minY)

            val strokePaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = strokeColor
            }

            for (stroke in strokes) {
                if (stroke.points.size < 2) continue

                val path = buildAndroidPath(stroke.points)
                val widths = StrokeSmoother.calculateWidths(stroke.points, stroke.strokeWidth)
                strokePaint.strokeWidth = widths.average().toFloat()
                canvas.drawPath(path, strokePaint)
            }

            return bitmap
        }
    }

    /**
     * Builds an Android [Path] from [StrokePoint]s using Catmull-Rom spline interpolation.
     */
    private fun buildAndroidPath(points: List<StrokePoint>): Path {
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

        val tension = 0.5f
        for (i in 0 until points.size - 1) {
            val p0 = points[maxOf(0, i - 1)]
            val p1 = points[i]
            val p2 = points[minOf(points.size - 1, i + 1)]
            val p3 = points[minOf(points.size - 1, i + 2)]

            val cp1x = p1.x + (p2.x - p0.x) * tension / 3f
            val cp1y = p1.y + (p2.y - p0.y) * tension / 3f
            val cp2x = p2.x - (p3.x - p1.x) * tension / 3f
            val cp2y = p2.y - (p3.y - p1.y) * tension / 3f

            path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
        }

        return path
    }
}
