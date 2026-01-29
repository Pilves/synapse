package com.synapse.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Manages screen capture via MediaProjection API.
 *
 * Requires a MediaProjection token obtained through user consent in an Activity.
 * The token is passed either directly via [setMediaProjection] or can be
 * recreated from stored result data in [MediaProjectionHolder].
 */
class ScreenshotManager(private val context: Context) {

    companion object {
        private const val TAG = "ScreenshotManager"
    }

    private var mediaProjection: MediaProjection? = null
    private var permissionGranted = false
    private var callbackRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped via callback")
            mediaProjection = null
            permissionGranted = false
            callbackRegistered = false
        }
    }

    /**
     * Whether screen capture permission has been granted.
     * Also checks [MediaProjectionHolder] for stored results that haven't
     * been turned into a projection yet.
     */
    fun hasPermission(): Boolean = permissionGranted || MediaProjectionHolder.hasResult()

    fun setMediaProjection(projection: MediaProjection) {
        mediaProjection = projection
        permissionGranted = true
        registerCallback(projection)
        Log.d(TAG, "MediaProjection set directly")
    }

    private fun registerCallback(projection: MediaProjection) {
        if (!callbackRegistered) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    projection.registerCallback(projectionCallback, mainHandler)
                } else {
                    @Suppress("DEPRECATION")
                    projection.registerCallback(projectionCallback, mainHandler)
                }
                callbackRegistered = true
                Log.d(TAG, "MediaProjection callback registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register MediaProjection callback", e)
            }
        }
    }

    /**
     * Attempts to obtain a MediaProjection from the stored holder result.
     * Returns true if successful, false if no stored result or creation failed.
     */
    fun tryRestoreProjection(): Boolean {
        if (mediaProjection != null) return true
        if (!MediaProjectionHolder.hasResult()) return false

        val resultCode = MediaProjectionHolder.getResultCode() ?: return false
        val resultData = MediaProjectionHolder.getResultData() ?: return false

        return try {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection != null) {
                mediaProjection = projection
                permissionGranted = true
                registerCallback(projection)
                Log.d(TAG, "MediaProjection restored from holder")
                true
            } else {
                Log.w(TAG, "Failed to create MediaProjection from stored result")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring MediaProjection", e)
            false
        }
    }

    fun releaseProjection() {
        if (callbackRegistered) {
            try {
                mediaProjection?.unregisterCallback(projectionCallback)
            } catch (_: Exception) {}
            callbackRegistered = false
        }
        mediaProjection?.stop()
        mediaProjection = null
        permissionGranted = false
        MediaProjectionHolder.clear()
        Log.d(TAG, "MediaProjection released")
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
