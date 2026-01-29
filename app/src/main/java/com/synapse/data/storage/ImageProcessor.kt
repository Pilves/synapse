package com.synapse.data.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.ceil

/**
 * Image processing utilities for Synapse.
 *
 * Handles bitmap conversion, thumbnail generation, image validation,
 * and chunk stitching operations.
 */
class ImageProcessor {

    companion object {
        /** Default WebP compression quality (0-100) */
        const val WEBP_QUALITY = 85

        /** Default thumbnail width */
        const val THUMBNAIL_WIDTH = 200

        /** Default thumbnail height */
        const val THUMBNAIL_HEIGHT = 300

        /** Maximum stitched image dimension to prevent OOM */
        const val MAX_STITCHED_DIMENSION = 4096

        /** WebP file header magic bytes (RIFF....WEBP) */
        private val WEBP_HEADER_RIFF = byteArrayOf(0x52, 0x49, 0x46, 0x46) // "RIFF"
        private val WEBP_HEADER_WEBP = byteArrayOf(0x57, 0x45, 0x42, 0x50) // "WEBP"

        /** Minimum valid image size in bytes */
        private const val MIN_VALID_IMAGE_SIZE = 100
    }

    /**
     * Result of an image processing operation.
     */
    sealed class ImageResult<out T> {
        data class Success<T>(val data: T) : ImageResult<T>()
        data class Error(val message: String, val exception: Exception? = null) : ImageResult<Nothing>()
    }

    /**
     * Image size information.
     */
    data class ImageSize(
        val width: Int,
        val height: Int,
        val fileSize: Long
    )

    /**
     * Convert a Bitmap to WebP format at specified quality.
     *
     * @param bitmap The bitmap to convert
     * @param quality Compression quality (0-100, default 85)
     * @return ByteArray containing WebP data, or null on failure
     */
    suspend fun bitmapToWebP(
        bitmap: Bitmap,
        quality: Int = WEBP_QUALITY
    ): ImageResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val outputStream = ByteArrayOutputStream()
            val compressFormat = Bitmap.CompressFormat.WEBP_LOSSY

            val success = bitmap.compress(compressFormat, quality, outputStream)

            if (success) {
                ImageResult.Success(outputStream.toByteArray())
            } else {
                ImageResult.Error("Failed to compress bitmap to WebP")
            }
        } catch (e: Exception) {
            ImageResult.Error("WebP conversion failed: ${e.message}", e)
        }
    }

    /**
     * Save a Bitmap as WebP to a file.
     *
     * @param bitmap The bitmap to save
     * @param outputFile The destination file
     * @param quality Compression quality (0-100, default 85)
     * @return true if successful
     */
    suspend fun saveBitmapAsWebP(
        bitmap: Bitmap,
        outputFile: File,
        quality: Int = WEBP_QUALITY
    ): ImageResult<File> = withContext(Dispatchers.IO) {
        try {
            FileOutputStream(outputFile).use { fos ->
                val compressFormat = Bitmap.CompressFormat.WEBP_LOSSY
                val success = bitmap.compress(compressFormat, quality, fos)

                if (success) {
                    fos.flush()
                    ImageResult.Success(outputFile)
                } else {
                    ImageResult.Error("Failed to save bitmap as WebP")
                }
            }
        } catch (e: IOException) {
            ImageResult.Error("Failed to write WebP file: ${e.message}", e)
        } catch (e: Exception) {
            ImageResult.Error("Unexpected error saving WebP: ${e.message}", e)
        }
    }

    /**
     * Generate a thumbnail from an image file.
     *
     * @param sourceFile The source image file
     * @param maxWidth Maximum thumbnail width (default 200)
     * @param maxHeight Maximum thumbnail height (default 300)
     * @return Scaled bitmap thumbnail, or null on failure
     */
    suspend fun generateThumbnail(
        sourceFile: File,
        maxWidth: Int = THUMBNAIL_WIDTH,
        maxHeight: Int = THUMBNAIL_HEIGHT
    ): ImageResult<Bitmap> = withContext(Dispatchers.IO) {
        try {
            if (!sourceFile.exists()) {
                return@withContext ImageResult.Error("Source file does not exist: ${sourceFile.path}")
            }

            // First, decode bounds only to calculate sample size
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(sourceFile.path, options)

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return@withContext ImageResult.Error("Invalid image dimensions")
            }

            // Calculate sample size for efficient loading
            val sampleSize = calculateInSampleSize(
                options.outWidth,
                options.outHeight,
                maxWidth,
                maxHeight
            )

            // Decode with sample size
            val decodedOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // Lower memory for thumbnails
            }

            val sampledBitmap = BitmapFactory.decodeFile(sourceFile.path, decodedOptions)
                ?: return@withContext ImageResult.Error("Failed to decode image")

            // Scale to exact dimensions if needed
            val scaledBitmap = scaleBitmapToFit(sampledBitmap, maxWidth, maxHeight)

            if (scaledBitmap !== sampledBitmap) {
                sampledBitmap.recycle()
            }

            ImageResult.Success(scaledBitmap)
        } catch (e: OutOfMemoryError) {
            ImageResult.Error("Out of memory generating thumbnail", RuntimeException(e))
        } catch (e: Exception) {
            ImageResult.Error("Failed to generate thumbnail: ${e.message}", e)
        }
    }

    /**
     * Generate a thumbnail and return as WebP bytes.
     *
     * @param sourceFile The source image file
     * @param maxWidth Maximum thumbnail width
     * @param maxHeight Maximum thumbnail height
     * @param quality WebP quality
     * @return WebP bytes of the thumbnail
     */
    suspend fun generateThumbnailAsWebP(
        sourceFile: File,
        maxWidth: Int = THUMBNAIL_WIDTH,
        maxHeight: Int = THUMBNAIL_HEIGHT,
        quality: Int = WEBP_QUALITY
    ): ImageResult<ByteArray> {
        val thumbnailResult = generateThumbnail(sourceFile, maxWidth, maxHeight)

        return when (thumbnailResult) {
            is ImageResult.Success -> {
                val webpResult = bitmapToWebP(thumbnailResult.data, quality)
                thumbnailResult.data.recycle()
                webpResult
            }
            is ImageResult.Error -> thumbnailResult
        }
    }

    /**
     * Validate image integrity by checking the file header.
     *
     * @param file The image file to validate
     * @return true if the image appears valid
     */
    suspend fun validateImageIntegrity(file: File): ImageResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                return@withContext ImageResult.Error("File does not exist")
            }

            if (file.length() < MIN_VALID_IMAGE_SIZE) {
                return@withContext ImageResult.Success(false)
            }

            // Check WebP header
            if (file.name.endsWith(".webp", ignoreCase = true)) {
                val isValidWebP = validateWebPHeader(file)
                return@withContext ImageResult.Success(isValidWebP)
            }

            // For other formats, try to decode bounds
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.path, options)

            val isValid = options.outWidth > 0 && options.outHeight > 0
            ImageResult.Success(isValid)
        } catch (e: Exception) {
            ImageResult.Error("Failed to validate image: ${e.message}", e)
        }
    }

    /**
     * Validate WebP file header.
     */
    private fun validateWebPHeader(file: File): Boolean {
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(12)
                val bytesRead = fis.read(header)

                if (bytesRead < 12) return false

                // Check RIFF header
                val hasRiff = header.slice(0..3).toByteArray().contentEquals(WEBP_HEADER_RIFF)
                // Check WEBP signature
                val hasWebp = header.slice(8..11).toByteArray().contentEquals(WEBP_HEADER_WEBP)

                hasRiff && hasWebp
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Calculate image size information.
     *
     * @param file The image file
     * @return ImageSize with dimensions and file size
     */
    suspend fun calculateImageSize(file: File): ImageResult<ImageSize> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                return@withContext ImageResult.Error("File does not exist")
            }

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.path, options)

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return@withContext ImageResult.Error("Invalid image dimensions")
            }

            ImageResult.Success(
                ImageSize(
                    width = options.outWidth,
                    height = options.outHeight,
                    fileSize = file.length()
                )
            )
        } catch (e: Exception) {
            ImageResult.Error("Failed to calculate image size: ${e.message}", e)
        }
    }

    /**
     * Stitch multiple chunk images into a single vertical image.
     *
     * @param chunkFiles List of chunk files in order
     * @param spacing Vertical spacing between chunks (default 0)
     * @return Stitched bitmap, or null on failure
     */
    suspend fun stitchChunks(
        chunkFiles: List<File>,
        spacing: Int = 0
    ): ImageResult<Bitmap> = withContext(Dispatchers.IO) {
        if (chunkFiles.isEmpty()) {
            return@withContext ImageResult.Error("No chunks to stitch")
        }

        try {
            // First pass: calculate dimensions and validate all files
            val bitmapInfos = mutableListOf<Pair<File, BitmapFactory.Options>>()
            var maxWidth = 0
            var totalHeight = 0

            for (file in chunkFiles) {
                if (!file.exists()) {
                    return@withContext ImageResult.Error("Chunk file missing: ${file.path}")
                }

                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(file.path, options)

                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    return@withContext ImageResult.Error("Invalid chunk: ${file.path}")
                }

                bitmapInfos.add(file to options)
                maxWidth = maxOf(maxWidth, options.outWidth)
                totalHeight += options.outHeight
            }

            // Add spacing
            totalHeight += spacing * (chunkFiles.size - 1)

            // Check dimensions
            if (maxWidth > MAX_STITCHED_DIMENSION || totalHeight > MAX_STITCHED_DIMENSION) {
                // Calculate scale factor
                val scaleFactor = minOf(
                    MAX_STITCHED_DIMENSION.toFloat() / maxWidth,
                    MAX_STITCHED_DIMENSION.toFloat() / totalHeight
                )
                maxWidth = (maxWidth * scaleFactor).toInt()
                totalHeight = ceil(totalHeight * scaleFactor).toInt()
            }

            // Create output bitmap
            val stitchedBitmap = Bitmap.createBitmap(
                maxWidth,
                totalHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(stitchedBitmap)
            canvas.drawColor(Color.WHITE)

            // Second pass: draw each chunk
            var currentY = 0
            for ((file, info) in bitmapInfos) {
                // Calculate sample size for memory efficiency
                val sampleSize = calculateInSampleSize(
                    info.outWidth,
                    info.outHeight,
                    maxWidth,
                    info.outHeight * maxWidth / info.outWidth
                )

                val decodedOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }

                val chunkBitmap = BitmapFactory.decodeFile(file.path, decodedOptions)
                    ?: return@withContext ImageResult.Error("Failed to decode chunk: ${file.path}")

                // Scale chunk to match width if needed
                val scaledChunk = if (chunkBitmap.width != maxWidth) {
                    val scaleFactor = maxWidth.toFloat() / chunkBitmap.width
                    val scaledHeight = ceil(chunkBitmap.height * scaleFactor).toInt()
                    Bitmap.createScaledBitmap(chunkBitmap, maxWidth, scaledHeight, true).also {
                        if (it !== chunkBitmap) chunkBitmap.recycle()
                    }
                } else {
                    chunkBitmap
                }

                // Draw chunk
                canvas.drawBitmap(scaledChunk, 0f, currentY.toFloat(), null)
                currentY += scaledChunk.height + spacing
                scaledChunk.recycle()
            }

            ImageResult.Success(stitchedBitmap)
        } catch (e: OutOfMemoryError) {
            ImageResult.Error("Out of memory stitching chunks", RuntimeException(e))
        } catch (e: Exception) {
            ImageResult.Error("Failed to stitch chunks: ${e.message}", e)
        }
    }

    /**
     * Stitch chunks and save as WebP.
     *
     * @param chunkFiles List of chunk files in order
     * @param outputFile The output file
     * @param quality WebP quality
     * @param spacing Vertical spacing between chunks
     * @return The output file on success
     */
    suspend fun stitchChunksToWebP(
        chunkFiles: List<File>,
        outputFile: File,
        quality: Int = WEBP_QUALITY,
        spacing: Int = 0
    ): ImageResult<File> {
        val stitchResult = stitchChunks(chunkFiles, spacing)

        return when (stitchResult) {
            is ImageResult.Success -> {
                val saveResult = saveBitmapAsWebP(stitchResult.data, outputFile, quality)
                stitchResult.data.recycle()
                saveResult
            }
            is ImageResult.Error -> stitchResult
        }
    }

    /**
     * Load a bitmap from file.
     *
     * @param file The image file
     * @param config Bitmap config (default ARGB_8888)
     * @return The loaded bitmap
     */
    suspend fun loadBitmap(
        file: File,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888
    ): ImageResult<Bitmap> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                return@withContext ImageResult.Error("File does not exist: ${file.path}")
            }

            val options = BitmapFactory.Options().apply {
                inPreferredConfig = config
            }

            val bitmap = BitmapFactory.decodeFile(file.path, options)
                ?: return@withContext ImageResult.Error("Failed to decode image")

            ImageResult.Success(bitmap)
        } catch (e: OutOfMemoryError) {
            ImageResult.Error("Out of memory loading image", RuntimeException(e))
        } catch (e: Exception) {
            ImageResult.Error("Failed to load image: ${e.message}", e)
        }
    }

    /**
     * Calculate the optimal inSampleSize for loading a scaled bitmap.
     */
    private fun calculateInSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1

        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * Scale a bitmap to fit within max dimensions while maintaining aspect ratio.
     */
    private fun scaleBitmapToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        val scaleFactor = minOf(
            maxWidth.toFloat() / width,
            maxHeight.toFloat() / height
        )

        val scaledWidth = (width * scaleFactor).toInt()
        val scaledHeight = ceil(height * scaleFactor).toInt()

        return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
    }
}
