package com.synapse.data.storage

import android.graphics.Bitmap
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageProcessorTest {

    @Test
    fun `WEBP_FORMAT is not null`() {
        assertNotNull(ImageProcessor.WEBP_FORMAT)
    }

    @Test
    fun `WEBP_FORMAT is a valid compress format`() {
        val format = ImageProcessor.WEBP_FORMAT
        // Should be either WEBP_LOSSY (API 30+) or WEBP (deprecated)
        @Suppress("DEPRECATION")
        assertTrue(
            format == Bitmap.CompressFormat.WEBP_LOSSY ||
            format == Bitmap.CompressFormat.WEBP
        )
    }

    @Test
    fun `bitmap can be compressed with WEBP_FORMAT`() {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val stream = java.io.ByteArrayOutputStream()

        val result = bitmap.compress(ImageProcessor.WEBP_FORMAT, 80, stream)
        assertTrue(result)
        assertTrue(stream.size() > 0)

        bitmap.recycle()
    }
}
