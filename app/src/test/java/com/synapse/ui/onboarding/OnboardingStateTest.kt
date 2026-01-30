package com.synapse.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateTest {

    @Test
    fun `Initial state has all flags false`() {
        val state = OnboardingState.Initial
        assertFalse(state.hasOverlayPermission)
        assertFalse(state.hasAccessibilityPermission)
        assertFalse(state.hasVaultConfigured)
        assertFalse(state.hasApiKey)
        assertFalse(state.isOnboardingComplete)
        assertEquals(0, state.currentPage)
    }

    @Test
    fun `Complete state has all required flags true`() {
        val state = OnboardingState.Complete
        assertTrue(state.hasOverlayPermission)
        assertTrue(state.hasVaultConfigured)
        assertTrue(state.hasApiKey)
        assertTrue(state.isOnboardingComplete)
        assertTrue(state.hasNotificationPermission)
    }

    @Test
    fun `isFullyConfigured requires overlay, vault, and apiKey`() {
        val partial = OnboardingState(
            hasOverlayPermission = true,
            hasVaultConfigured = true,
            hasApiKey = false
        )
        assertFalse(partial.isFullyConfigured)

        val full = partial.copy(hasApiKey = true)
        assertTrue(full.isFullyConfigured)
    }

    @Test
    fun `isFullyConfigured does not require accessibility or notification`() {
        val state = OnboardingState(
            hasOverlayPermission = true,
            hasAccessibilityPermission = false,
            hasVaultConfigured = true,
            hasApiKey = true,
            hasNotificationPermission = false
        )
        assertTrue(state.isFullyConfigured)
    }

    @Test
    fun `remainingSetupSteps counts missing required steps`() {
        assertEquals(3, OnboardingState.Initial.remainingSetupSteps)
        assertEquals(0, OnboardingState.Complete.remainingSetupSteps)

        val oneLeft = OnboardingState(
            hasOverlayPermission = true,
            hasVaultConfigured = true,
            hasApiKey = false
        )
        assertEquals(1, oneLeft.remainingSetupSteps)

        val twoLeft = OnboardingState(
            hasOverlayPermission = false,
            hasVaultConfigured = true,
            hasApiKey = false
        )
        assertEquals(2, twoLeft.remainingSetupSteps)
    }

    @Test
    fun `totalPages is always 5`() {
        assertEquals(5, OnboardingState.Initial.totalPages)
        assertEquals(5, OnboardingState.Complete.totalPages)
        assertEquals(5, OnboardingState(currentPage = 3).totalPages)
    }

    @Test
    fun `errorMessage is null by default`() {
        val state = OnboardingState.Initial
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun `isValidatingApiKey is false by default`() {
        assertFalse(OnboardingState.Initial.isValidatingApiKey)
    }

    @Test
    fun `vaultPath is null by default`() {
        assertEquals(null, OnboardingState.Initial.vaultPath)
    }

    @Test
    fun `copy preserves all fields`() {
        val state = OnboardingState(
            hasOverlayPermission = true,
            hasVaultConfigured = true,
            hasApiKey = true,
            currentPage = 2,
            vaultPath = "/vault",
            errorMessage = "err"
        )
        val copied = state.copy(currentPage = 3)
        assertEquals(3, copied.currentPage)
        assertTrue(copied.hasOverlayPermission)
        assertEquals("/vault", copied.vaultPath)
        assertEquals("err", copied.errorMessage)
    }
}
