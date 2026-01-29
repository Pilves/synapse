package com.synapse.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class ScreenshotManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var permissionGranted = false

    fun hasPermission(): Boolean = permissionGranted

    fun setMediaProjection(projection: MediaProjection) {
        mediaProjection = projection
        permissionGranted = true
    }

    fun releaseProjection() {
        mediaProjection?.stop()
        mediaProjection = null
        permissionGranted = false
    }

    suspend fun captureRegion(bounds: Rect): Bitmap? = withContext(Dispatchers.IO) {
        val projection = mediaProjection ?: return@withContext null

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        val virtualDisplay = projection.createVirtualDisplay(
            "SynapseCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null, null
        )

        delay(100) // Wait for capture

        val image = imageReader.acquireLatestImage()
        val fullBitmap = image?.let { imageToBitmap(it) }
        image?.close()

        virtualDisplay.release()
        imageReader.close()

        fullBitmap?.let {
            // Crop to region bounds (clamp to screen size)
            val left = bounds.left.coerceIn(0, width - 1)
            val top = bounds.top.coerceIn(0, height - 1)
            val right = bounds.right.coerceIn(left + 1, width)
            val bottom = bounds.bottom.coerceIn(top + 1, height)

            Bitmap.createBitmap(it, left, top, right - left, bottom - top)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }
}
