package com.synapse.service

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SynapseCapabilitiesTest {

    private lateinit var context: Context
    private lateinit var capabilities: SynapseCapabilities

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        capabilities = SynapseCapabilities(context)
    }

    @Test
    fun `currentMode returns MINIMAL when overlay not granted`() {
        // In Robolectric, canDrawOverlays returns false by default
        assertEquals(SynapseCapabilities.Mode.MINIMAL, capabilities.currentMode())
    }

    @Test
    fun `canExtractText is false when overlay not granted`() {
        assertFalse(capabilities.canExtractText)
    }

    @Test
    fun `canShowOverlay is false in MINIMAL mode`() {
        assertFalse(capabilities.canShowOverlay)
    }

    @Test
    fun `canCaptureScreenshot matches canShowOverlay`() {
        assertEquals(capabilities.canShowOverlay, capabilities.canCaptureScreenshot)
    }

    @Test
    fun `Mode enum has three values`() {
        assertEquals(3, SynapseCapabilities.Mode.entries.size)
        assertEquals(
            setOf("FULL", "BASIC", "MINIMAL"),
            SynapseCapabilities.Mode.entries.map { it.name }.toSet()
        )
    }
}
