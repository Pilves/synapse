package com.synapse.service

import android.graphics.Rect
import com.synapse.model.CapturedContext

class RegionCaptureManager(
    private val accessibilityServiceProvider: () -> SynapseAccessibilityService?
) {
    suspend fun captureRegion(screenBounds: Rect): CapturedContext? {
        // Try text extraction first via accessibility
        val textContext = accessibilityServiceProvider()?.getTextInRegion(screenBounds)

        if (textContext != null) {
            return textContext
        }

        // No text found in region - return null
        // MediaProjection screenshot fallback would go here in the future
        return null
    }
}
