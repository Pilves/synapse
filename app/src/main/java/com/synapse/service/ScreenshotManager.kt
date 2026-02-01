package com.synapse.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages screen capture via MediaProjection API.
 *
 * Keeps a persistent VirtualDisplay alive for the lifetime of the MediaProjection
 * so that multiple screenshots can be taken without re-creating the projection
 * (required on Android 14+ where createVirtualDisplay is single-use per projection).
 */
class ScreenshotManager(private val context: Context) {

    companion object {
        private const val TAG = "ScreenshotManager"

        /**
         * Deletes all temporary screenshot files from internal storage.
         * Call after session completion to free disk space.
         *
         * Note: This performs blocking I/O. Callers must invoke from a background thread
         * (e.g., withContext(Dispatchers.IO)).
         */
        fun cleanupScreenshots(context: Context) {
            val dir = File(context.filesDir, "screenshots")
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles() ?: return
                var deleted = 0
                for (file in files) {
                    if (file.delete()) deleted++
                }
                Log.d(TAG, "Cleaned up $deleted temporary screenshot(s)")
            }
        }
    }

    private val lock = Any()
    private var mediaProjection: MediaProjection? = null
    private var permissionGranted = false
    private var callbackRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Persistent virtual display + image reader kept alive between captures
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped via callback")
            synchronized(lock) {
                tearDownVirtualDisplay()
                mediaProjection = null
                permissionGranted = false
                callbackRegistered = false
            }
        }
    }

    fun hasPermission(): Boolean = synchronized(lock) { permissionGranted } || MediaProjectionHolder.hasResult()

    fun setMediaProjection(projection: MediaProjection) {
        synchronized(lock) {
            mediaProjection = projection
            permissionGranted = true
            registerCallback(projection)
        }
        // Don't create virtual display eagerly — it will be created on first capture
        Log.d(TAG, "MediaProjection set directly")
    }

    private fun registerCallback(projection: MediaProjection) {
        if (!callbackRegistered) {
            try {
                projection.registerCallback(projectionCallback, mainHandler)
                callbackRegistered = true
                Log.d(TAG, "MediaProjection callback registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register MediaProjection callback", e)
            }
        }
    }

    /**
     * Creates the persistent VirtualDisplay and ImageReader for this projection.
     */
    private fun setupVirtualDisplay(projection: MediaProjection) {
        tearDownVirtualDisplay()

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val width: Int
        val height: Int
        val density: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = wm.currentWindowMetrics
            width = windowMetrics.bounds.width()
            height = windowMetrics.bounds.height()
            density = context.resources.configuration.densityDpi
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
            density = metrics.densityDpi
        }
        Log.d(TAG, "Screen metrics for VirtualDisplay: ${width}x${height} @ ${density}dpi")

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "SynapseCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null, null
            )
            Log.d(TAG, "Persistent VirtualDisplay created (${width}x${height})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VirtualDisplay", e)
            reader.close()
            imageReader = null
        }
    }

    private fun tearDownVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    fun tryRestoreProjection(): Boolean {
        synchronized(lock) {
            if (mediaProjection != null && virtualDisplay != null && imageReader != null) return true
        }
        if (!MediaProjectionHolder.hasResult()) return false

        val resultCode = MediaProjectionHolder.getResultCode() ?: return false
        val resultData = MediaProjectionHolder.getResultData() ?: return false

        return try {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection != null) {
                synchronized(lock) {
                    mediaProjection = projection
                    permissionGranted = true
                    // Reset flag — this is a new projection, previous callback is stale
                    callbackRegistered = false
                    registerCallback(projection)
                    setupVirtualDisplay(projection)
                }
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

    /**
     * Pauses screen mirroring to save resources when not actively capturing.
     * Sets the virtual display surface to null so no frames are rendered.
     * The virtual display and projection stay alive for reuse.
     */
    fun pauseCapture() {
        synchronized(lock) {
            virtualDisplay?.surface = null
        }
        Log.d(TAG, "Capture paused — surface detached")
    }

    /**
     * Resumes screen mirroring by reattaching the ImageReader surface.
     * Call before captureRegion() if pauseCapture() was called.
     */
    fun resumeCapture() {
        synchronized(lock) {
            val reader = imageReader ?: return
            if (virtualDisplay?.surface != null) return
            virtualDisplay?.surface = reader.surface
        }
        Log.d(TAG, "Capture resumed — surface reattached")
    }

    fun releaseProjection() {
        synchronized(lock) {
            if (callbackRegistered) {
                try {
                    mediaProjection?.unregisterCallback(projectionCallback)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unregister projection callback", e)
                }
                callbackRegistered = false
            }
            tearDownVirtualDisplay()
            mediaProjection?.stop()
            mediaProjection = null
            permissionGranted = false
        }
        MediaProjectionHolder.clear()
        Log.d(TAG, "MediaProjection released")
    }

    /**
     * Clears stale projection state when the projection is detected as dead.
     * After this, hasPermission() will return false, triggering a re-request.
     */
    fun invalidateProjection() {
        Log.w(TAG, "Invalidating stale MediaProjection state")
        synchronized(lock) {
            tearDownVirtualDisplay()
            mediaProjection = null
            permissionGranted = false
            callbackRegistered = false
        }
    }

    suspend fun captureRegion(bounds: Rect): Bitmap? = withContext(Dispatchers.IO) {
        // Capture local references inside the synchronized block so that another thread
        // calling releaseProjection() / invalidateProjection() after the delay cannot
        // null out the references we depend on.
        val localReader: ImageReader
        synchronized(lock) {
            val projection = mediaProjection
            if (projection == null) {
                Log.w(TAG, "No MediaProjection for capture — invalidating stale state")
                tearDownVirtualDisplay()
                mediaProjection = null
                permissionGranted = false
                callbackRegistered = false
                return@withContext null
            }
            if (virtualDisplay == null) {
                setupVirtualDisplay(projection)
            }

            val reader = imageReader
            if (reader == null || virtualDisplay == null) {
                Log.w(TAG, "No active VirtualDisplay — projection is likely dead")
                tearDownVirtualDisplay()
                mediaProjection = null
                permissionGranted = false
                callbackRegistered = false
                return@withContext null
            }

            // Ensure surface is attached (may have been paused)
            if (virtualDisplay?.surface == null) {
                virtualDisplay?.surface = reader.surface
            }

            localReader = reader
        }

        // Wait for a fresh frame after reattaching surface.
        // The lock is NOT held during this delay to avoid blocking other threads.
        delay(200)

        // Retry acquiring the latest image up to 3 times with increasing delays.
        // localReader is a local reference captured before the delay, so it remains
        // valid even if another thread clears the class-level imageReader field.
        var fullBitmap: Bitmap? = null
        for (attempt in 1..3) {
            val image = try {
                localReader.acquireLatestImage()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "ImageReader closed during capture (attempt $attempt/3)", e)
                return@withContext null
            }
            if (image != null) {
                try {
                    fullBitmap = imageToBitmap(image)
                } finally {
                    image.close()
                }
                break
            }
            Log.w(TAG, "acquireLatestImage returned null (attempt $attempt/3)")
            delay(150L * attempt)
        }

        if (fullBitmap == null) {
            Log.w(TAG, "Failed to acquire image after 3 attempts")
            return@withContext null
        }

        val width = fullBitmap.width
        val height = fullBitmap.height

        // Crop to region bounds (clamp to screen size)
        val left = bounds.left.coerceIn(0, width - 1)
        val top = bounds.top.coerceIn(0, height - 1)
        val right = bounds.right.coerceIn(left + 1, width)
        val bottom = bounds.bottom.coerceIn(top + 1, height)

        val croppedBitmap = Bitmap.createBitmap(fullBitmap, left, top, right - left, bottom - top)
        if (fullBitmap !== croppedBitmap) fullBitmap.recycle()
        croppedBitmap
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

        val resultBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        if (bitmap !== resultBitmap) bitmap.recycle()
        return resultBitmap
    }
}
