package com.synapse.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors permission health and exposes a reactive [StateFlow] for the UI to observe.
 *
 * Instead of polling (battery-intensive), this uses:
 * - [SynapseAccessibilityService] broadcasts on connect/disconnect
 * - Explicit refresh calls from lifecycle events
 *
 * Health states:
 * - HEALTHY: All permissions granted (overlay + accessibility)
 * - DEGRADED: Overlay granted but accessibility off (region text extraction unavailable)
 * - CRITICAL: Overlay permission lost (core functionality broken)
 */
class PermissionHealthMonitor(private val context: Context) {

    companion object {
        private const val TAG = "PermissionHealthMonitor"

        /** Broadcast action sent by SynapseAccessibilityService on state changes. */
        const val ACTION_ACCESSIBILITY_STATE_CHANGED =
            "com.synapse.action.ACCESSIBILITY_STATE_CHANGED"
        const val EXTRA_IS_CONNECTED = "is_connected"
    }

    enum class PermissionHealth {
        HEALTHY,
        DEGRADED,
        CRITICAL
    }

    private val _health = MutableStateFlow(PermissionHealth.HEALTHY)
    val health: StateFlow<PermissionHealth> = _health.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    /**
     * Starts monitoring. Call from service onCreate.
     */
    fun startMonitoring() {
        refresh()

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_ACCESSIBILITY_STATE_CHANGED) {
                    val connected = intent.getBooleanExtra(EXTRA_IS_CONNECTED, false)
                    Log.d(TAG, "Accessibility state broadcast: connected=$connected")
                    refresh()
                }
            }
        }

        val filter = IntentFilter(ACTION_ACCESSIBILITY_STATE_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * Stops monitoring. Call from service onDestroy.
     */
    fun stopMonitoring() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver not registered", e)
            }
        }
        receiver = null
    }

    /**
     * Refreshes the health state by checking current permissions.
     * Can be called explicitly after returning from settings.
     */
    fun refresh() {
        val hasOverlay = Settings.canDrawOverlays(context)
        val hasAccessibility = SynapseAccessibilityService.isEnabled(context)

        val newHealth = when {
            hasOverlay && hasAccessibility -> PermissionHealth.HEALTHY
            hasOverlay -> PermissionHealth.DEGRADED
            else -> PermissionHealth.CRITICAL
        }

        if (_health.value != newHealth) {
            Log.d(TAG, "Health changed: ${_health.value} → $newHealth")
            _health.value = newHealth
        }
    }
}
