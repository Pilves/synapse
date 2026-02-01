package com.synapse.service

import android.content.Context
import android.media.projection.MediaProjection
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ScreenshotManagerTest {

    private lateinit var context: Context
    private lateinit var manager: ScreenshotManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        MediaProjectionHolder.clear()
        manager = ScreenshotManager(context)
    }

    @Test
    fun `hasPermission returns false initially`() {
        assertFalse(manager.hasPermission())
    }

    @Test
    fun `setMediaProjection grants permission`() {
        val projection = mockk<MediaProjection>(relaxed = true)
        manager.setMediaProjection(projection)
        assertTrue(manager.hasPermission())
    }

    @Test
    fun `releaseProjection clears permission`() {
        val projection = mockk<MediaProjection>(relaxed = true)
        manager.setMediaProjection(projection)
        assertTrue(manager.hasPermission())

        manager.releaseProjection()
        assertFalse(manager.hasPermission())
    }

    @Test
    fun `releaseProjection stops and unregisters projection`() {
        val projection = mockk<MediaProjection>(relaxed = true)
        manager.setMediaProjection(projection)

        manager.releaseProjection()

        verify { projection.stop() }
    }

    @Test
    fun `invalidateProjection clears state without stopping`() {
        val projection = mockk<MediaProjection>(relaxed = true)
        manager.setMediaProjection(projection)

        manager.invalidateProjection()

        assertFalse(manager.hasPermission())
        // invalidateProjection should NOT call stop
        verify(exactly = 0) { projection.stop() }
    }

    @Test
    fun `tryRestoreProjection returns false with no holder result`() {
        assertFalse(manager.tryRestoreProjection())
    }

    @Test
    fun `cleanupScreenshots handles missing directory gracefully`() {
        // Should not throw when screenshots directory does not exist
        ScreenshotManager.cleanupScreenshots(context)
    }

    @Test
    fun `cleanupScreenshots deletes files in screenshots directory`() {
        val dir = java.io.File(context.filesDir, "screenshots")
        dir.mkdirs()
        java.io.File(dir, "test1.png").writeText("data1")
        java.io.File(dir, "test2.png").writeText("data2")

        ScreenshotManager.cleanupScreenshots(context)

        val remaining = dir.listFiles()?.size ?: 0
        assertTrue("Expected all files deleted, but $remaining remain", remaining == 0)
    }
}
