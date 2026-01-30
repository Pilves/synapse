package com.synapse.service

import android.content.Context
import android.provider.Settings

/**
 * Detects available capabilities based on granted permissions.
 *
 * Capability modes:
 * - FULL: Overlay + Accessibility → handwriting capture + region text extraction + context
 * - BASIC: Overlay only → handwriting capture + screenshot-based region capture (no text extraction)
 * - MINIMAL: No overlay → app is unusable for its core purpose (onboarding not complete)
 */
class SynapseCapabilities(private val context: Context) {

    enum class Mode {
        /** All permissions granted: overlay + accessibility */
        FULL,
        /** Overlay granted but accessibility is off: capture works, no text extraction */
        BASIC,
        /** Overlay not granted: core functionality unavailable */
        MINIMAL
    }

    /**
     * Returns the current capability mode based on permission state.
     */
    fun currentMode(): Mode {
        val hasOverlay = Settings.canDrawOverlays(context)
        val hasAccessibility = SynapseAccessibilityService.isEnabled(context)

        return when {
            hasOverlay && hasAccessibility -> Mode.FULL
            hasOverlay -> Mode.BASIC
            else -> Mode.MINIMAL
        }
    }

    /** Whether region text extraction (accessibility-based) is available. */
    val canExtractText: Boolean
        get() = currentMode() == Mode.FULL

    /** Whether the capture overlay can be shown. */
    val canShowOverlay: Boolean
        get() = currentMode() != Mode.MINIMAL

    /** Whether screenshot-based region capture is available (needs overlay at minimum). */
    val canCaptureScreenshot: Boolean
        get() = canShowOverlay
}
