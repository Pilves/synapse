package com.synapse.model

import android.graphics.Rect

/**
 * Represents context captured from the user's screen via accessibility services
 * or text selection processing.
 */
sealed class CapturedContext {

    /** Automatically captured context about the current app/page */
    data class AutoContext(
        val sourceApp: String,
        val sourceUrl: String? = null,
        val pageTitle: String? = null
    ) : CapturedContext()

    /** Text that was explicitly selected by the user */
    data class SelectedText(
        val text: String,
        val sourceApp: String? = null,
        val sourceUrl: String? = null
    ) : CapturedContext()

    /** Text captured from a specific screen region */
    data class RegionText(
        val text: String,
        val bounds: Rect
    ) : CapturedContext()
}
