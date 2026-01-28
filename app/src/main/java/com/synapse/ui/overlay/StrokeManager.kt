package com.synapse.ui.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.geometry.Offset

/**
 * Represents a single stroke drawn by the user.
 *
 * @param points The list of points that make up the stroke
 * @param strokeWidth The width of the stroke in pixels
 */
data class Stroke(
    val points: List<Offset>,
    val strokeWidth: Float = 4f
)

/**
 * Manages stroke data for the capture canvas.
 *
 * Provides functionality to add, undo, and convert strokes to bitmap.
 * Thread-safe for use from coroutines and UI callbacks.
 */
class StrokeManager {

    private val strokes = mutableListOf<Stroke>()
    private val lock = Any()

    /**
     * Adds a new stroke to the collection.
     *
     * @param stroke The stroke to add
     */
    fun addStroke(stroke: Stroke) {
        synchronized(lock) {
            if (stroke.points.isNotEmpty()) {
                strokes.add(stroke)
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
            color = android.graphics.Color.argb(200, 40, 40, 40) // Dark gray with slight transparency
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

                val path = Path()
                path.moveTo(stroke.points[0].x, stroke.points[0].y)

                // Use quadratic bezier curves for smooth lines
                for (i in 1 until stroke.points.size) {
                    val prev = stroke.points[i - 1]
                    val current = stroke.points[i]
                    val midX = (prev.x + current.x) / 2
                    val midY = (prev.y + current.y) / 2
                    path.quadTo(prev.x, prev.y, midX, midY)
                }

                // Draw outline first (thicker)
                outlinePaint.strokeWidth = stroke.strokeWidth + 2f
                canvas.drawPath(path, outlinePaint)

                // Draw white stroke on top
                strokePaint.strokeWidth = stroke.strokeWidth
                canvas.drawPath(path, strokePaint)
            }
        }

        return bitmap
    }

    /**
     * Converts all strokes to a Bitmap with only the stroke content (no outline).
     * Useful for clean image output for OCR processing.
     *
     * @param width The width of the output bitmap
     * @param height The height of the output bitmap
     * @param backgroundColor The background color (default: white)
     * @param strokeColor The stroke color (default: black)
     * @return A Bitmap containing all strokes
     */
    fun toBitmapForOcr(
        width: Int,
        height: Int,
        backgroundColor: Int = android.graphics.Color.WHITE,
        strokeColor: Int = android.graphics.Color.BLACK
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Fill with background color
        canvas.drawColor(backgroundColor)

        val strokePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = strokeColor
        }

        synchronized(lock) {
            for (stroke in strokes) {
                if (stroke.points.size < 2) continue

                val path = Path()
                path.moveTo(stroke.points[0].x, stroke.points[0].y)

                // Use quadratic bezier curves for smooth lines
                for (i in 1 until stroke.points.size) {
                    val prev = stroke.points[i - 1]
                    val current = stroke.points[i]
                    val midX = (prev.x + current.x) / 2
                    val midY = (prev.y + current.y) / 2
                    path.quadTo(prev.x, prev.y, midX, midY)
                }

                strokePaint.strokeWidth = stroke.strokeWidth
                canvas.drawPath(path, strokePaint)
            }
        }

        return bitmap
    }
}
