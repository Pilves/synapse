package com.synapse.data.storage

import android.graphics.Bitmap
import android.os.Build

/**
 * Image processing utilities for Synapse.
 *
 * Handles bitmap conversion and format constants.
 */
class ImageProcessor {

    companion object {
        /** WebP compress format compatible with API 26+ */
        val WEBP_FORMAT: Bitmap.CompressFormat =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
    }
}
