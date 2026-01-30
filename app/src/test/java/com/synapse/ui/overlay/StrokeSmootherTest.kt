package com.synapse.ui.overlay

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeSmootherTest {

    @Test
    fun `calculateWidths returns one width per point`() {
        val points = listOf(
            StrokePoint(Offset(0f, 0f), pressure = 0.5f, timestamp = 0),
            StrokePoint(Offset(10f, 0f), pressure = 0.5f, timestamp = 100),
            StrokePoint(Offset(20f, 0f), pressure = 0.5f, timestamp = 200)
        )
        val widths = StrokeSmoother.calculateWidths(points, baseWidth = 4f)
        assertEquals(3, widths.size)
    }

    @Test
    fun `fast stroke produces thinner width than slow stroke`() {
        val fastPoints = listOf(
            StrokePoint(Offset(0f, 0f), pressure = 0.5f, timestamp = 0),
            StrokePoint(Offset(500f, 0f), pressure = 0.5f, timestamp = 100) // 5000 px/s
        )
        val slowPoints = listOf(
            StrokePoint(Offset(0f, 0f), pressure = 0.5f, timestamp = 0),
            StrokePoint(Offset(5f, 0f), pressure = 0.5f, timestamp = 100) // 50 px/s
        )

        val fastWidths = StrokeSmoother.calculateWidths(fastPoints, baseWidth = 4f)
        val slowWidths = StrokeSmoother.calculateWidths(slowPoints, baseWidth = 4f)

        // Last point width should be thinner for fast strokes
        assertTrue(
            "Fast stroke width (${fastWidths[1]}) should be less than slow stroke width (${slowWidths[1]})",
            fastWidths[1] < slowWidths[1]
        )
    }

    @Test
    fun `high pressure produces wider stroke than low pressure`() {
        val highPressure = listOf(
            StrokePoint(Offset(0f, 0f), pressure = 1.0f, timestamp = 0),
            StrokePoint(Offset(10f, 0f), pressure = 1.0f, timestamp = 100)
        )
        val lowPressure = listOf(
            StrokePoint(Offset(0f, 0f), pressure = 0.2f, timestamp = 0),
            StrokePoint(Offset(10f, 0f), pressure = 0.2f, timestamp = 100)
        )

        val highWidths = StrokeSmoother.calculateWidths(highPressure, baseWidth = 4f)
        val lowWidths = StrokeSmoother.calculateWidths(lowPressure, baseWidth = 4f)

        assertTrue(
            "High pressure width (${highWidths[1]}) should be greater than low pressure width (${lowWidths[1]})",
            highWidths[1] > lowWidths[1]
        )
    }

    @Test
    fun `createSmoothPath handles single point`() {
        val points = listOf(StrokePoint(Offset(50f, 50f)))
        val path = StrokeSmoother.createSmoothPath(points)
        assertTrue(!path.isEmpty)
    }

    @Test
    fun `createSmoothPath handles two points`() {
        val points = listOf(
            StrokePoint(Offset(0f, 0f)),
            StrokePoint(Offset(100f, 100f))
        )
        val path = StrokeSmoother.createSmoothPath(points)
        assertTrue(!path.isEmpty)
    }

    @Test
    fun `createSmoothPath handles many points`() {
        val points = (0..20).map { i ->
            StrokePoint(
                Offset(i * 10f, i * 5f),
                pressure = 0.5f,
                timestamp = i * 50L
            )
        }
        val path = StrokeSmoother.createSmoothPath(points)
        assertTrue(!path.isEmpty)
    }

    @Test
    fun `createSmoothPath with Offset list produces non-empty path`() {
        val offsets = listOf(Offset(0f, 0f), Offset(50f, 50f), Offset(100f, 0f))
        val path = StrokeSmoother.createSmoothPath(offsets)
        assertTrue(!path.isEmpty)
    }

    @Test
    fun `calculateWidths handles single point`() {
        val points = listOf(StrokePoint(Offset(0f, 0f), pressure = 0.7f))
        val widths = StrokeSmoother.calculateWidths(points, baseWidth = 4f)
        assertEquals(1, widths.size)
        // Single point uses pressure * baseWidth
        assertEquals(0.7f * 4f, widths[0], 0.01f)
    }

    @Test
    fun `empty points list returns empty widths`() {
        val widths = StrokeSmoother.calculateWidths(emptyList(), baseWidth = 4f)
        assertTrue(widths.isEmpty())
    }

    @Test
    fun `empty points list creates empty path`() {
        val path = StrokeSmoother.createSmoothPath(emptyList<StrokePoint>())
        assertTrue(path.isEmpty)
    }
}
