package com.synapse.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.synapse.model.CapturedContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Captures content from a screen region using multiple strategies:
 * 1. Text extraction via AccessibilityService (preferred, no extra permissions)
 * 2. Screenshot via MediaProjection (fallback when no text is found)
 */
class RegionCaptureManager(
    private val context: Context,
    private val accessibilityServiceProvider: () -> SynapseAccessibilityService?,
    private val screenshotManager: ScreenshotManager
) {
    companion object {
        private const val TAG = "RegionCaptureManager"
    }

    /**
     * Whether screenshot fallback is available (MediaProjection permission granted).
     */
    fun hasScreenshotPermission(): Boolean = screenshotManager.hasPermission()

    suspend fun captureRegion(screenBounds: Rect): CapturedContext? {
        // Try text extraction first via accessibility
        val textContext = accessibilityServiceProvider()?.getTextInRegion(screenBounds)

        if (textContext != null) {
            return textContext
        }

        // Fallback: capture screenshot via MediaProjection
        Log.d(TAG, "No text found in region, attempting screenshot fallback")
        if (screenshotManager.hasPermission()) {
            // Try to restore projection if needed
            screenshotManager.tryRestoreProjection()

            val bitmap = screenshotManager.captureRegion(screenBounds)
            if (bitmap != null) {
                Log.d(TAG, "Screenshot captured: ${bitmap.width}x${bitmap.height}")
                val imagePath = saveBitmapToFile(bitmap)
                if (imagePath != null) {
                    return CapturedContext.RegionImage(
                        imagePath = imagePath,
                        bounds = screenBounds,
                        description = null
                    )
                }
            } else {
                Log.w(TAG, "Screenshot capture returned null")
            }
        } else {
            Log.d(TAG, "No screenshot permission, skipping fallback")
        }

        return null
    }

    /**
     * Saves a bitmap to internal storage and returns the file path.
     */
    private fun saveBitmapToFile(bitmap: Bitmap): String? {
        return try {
            val dir = File(context.filesDir, "screenshots")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "region_${UUID.randomUUID()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            Log.d(TAG, "Screenshot saved to ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot", e)
            null
        }
    }
}
