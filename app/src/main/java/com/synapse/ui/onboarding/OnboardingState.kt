package com.synapse.ui.onboarding

/**
 * Represents the current state of onboarding progress and what setup steps are needed.
 *
 * This data class tracks which permissions and configurations have been completed,
 * allowing the onboarding flow to skip already-configured steps or show the user
 * what still needs to be set up.
 */
data class OnboardingState(
    /**
     * Whether the SYSTEM_ALERT_WINDOW permission has been granted.
     * Required for the overlay capture functionality.
     */
    val hasOverlayPermission: Boolean = false,

    /**
     * Whether an Obsidian vault folder has been selected and configured.
     */
    val hasVaultConfigured: Boolean = false,

    /**
     * Whether an API key (Gemini or other transcription service) has been entered.
     */
    val hasApiKey: Boolean = false,

    /**
     * Whether the user has completed or skipped the entire onboarding flow.
     */
    val isOnboardingComplete: Boolean = false,

    /**
     * The current page index in the onboarding flow (0-based).
     */
    val currentPage: Int = 0,

    /**
     * Whether the notification permission has been granted (Android 13+).
     */
    val hasNotificationPermission: Boolean = false,

    /**
     * The currently configured vault path, if any.
     */
    val vaultPath: String? = null,

    /**
     * Whether the API key is currently being validated.
     */
    val isValidatingApiKey: Boolean = false,

    /**
     * Error message to display, if any.
     */
    val errorMessage: String? = null
) {
    /**
     * Returns true if all required setup steps are complete.
     */
    val isFullyConfigured: Boolean
        get() = hasOverlayPermission && hasVaultConfigured && hasApiKey

    /**
     * Returns the number of setup steps that still need to be completed.
     */
    val remainingSetupSteps: Int
        get() = listOf(
            !hasOverlayPermission,
            !hasVaultConfigured,
            !hasApiKey
        ).count { it }

    /**
     * Returns the total number of pages in the onboarding flow.
     */
    val totalPages: Int = 7

    companion object {
        /**
         * Initial state for a fresh installation.
         */
        val Initial = OnboardingState()

        /**
         * State indicating all setup is complete.
         */
        val Complete = OnboardingState(
            hasOverlayPermission = true,
            hasVaultConfigured = true,
            hasApiKey = true,
            isOnboardingComplete = true,
            hasNotificationPermission = true
        )
    }
}
