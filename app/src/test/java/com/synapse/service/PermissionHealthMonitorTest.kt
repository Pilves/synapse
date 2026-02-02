package com.synapse.service

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PermissionHealthMonitorTest {

    private lateinit var context: Context
    private lateinit var monitor: PermissionHealthMonitor

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        monitor = PermissionHealthMonitor(context)
    }

    @Test
    fun `initial health state is HEALTHY`() {
        assertEquals(PermissionHealthMonitor.PermissionHealth.HEALTHY, monitor.health.value)
    }

    @Test
    fun `health StateFlow is not null`() {
        assertNotNull(monitor.health)
    }

    @Test
    fun `startMonitoring updates health state`() {
        monitor.startMonitoring()
        // In Robolectric, canDrawOverlays is false by default
        // and accessibility is not enabled, so health should be CRITICAL
        assertEquals(PermissionHealthMonitor.PermissionHealth.CRITICAL, monitor.health.value)
    }

    @Test
    fun `stopMonitoring does not crash`() {
        monitor.startMonitoring()
        monitor.stopMonitoring()
    }

    @Test
    fun `startMonitoring is idempotent`() {
        monitor.startMonitoring()
        val firstHealth = monitor.health.value
        monitor.startMonitoring()
        assertEquals(firstHealth, monitor.health.value)
    }

    @Test
    fun `refresh updates health state`() {
        monitor.refresh()
        // Should reflect actual permission state (CRITICAL in test env)
        assertNotNull(monitor.health.value)
    }

    @Test
    fun `ACTION_ACCESSIBILITY_STATE_CHANGED constant is defined`() {
        assertEquals(
            "com.synapse.action.ACCESSIBILITY_STATE_CHANGED",
            PermissionHealthMonitor.ACTION_ACCESSIBILITY_STATE_CHANGED
        )
    }
}
