package com.synapse.model

import android.graphics.Rect
import java.util.UUID

sealed class CapturedContext {
    abstract val id: String
    abstract val timestamp: Long

    data class SelectedText(
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis(),
        val text: String,
        val sourceApp: String?,
        val sourceUrl: String?
    ) : CapturedContext()

    data class RegionText(
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis(),
        val text: String,
        val bounds: Rect
    ) : CapturedContext()

    data class RegionImage(
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis(),
        val imagePath: String,
        val bounds: Rect,
        val description: String? = null
    ) : CapturedContext()

    data class AutoContext(
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis(),
        val sourceApp: String,
        val sourceUrl: String?,
        val pageTitle: String?
    ) : CapturedContext()
}
