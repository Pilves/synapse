package com.synapse.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Helper class for creating notification channels for the Synapse overlay service.
 */
class NotificationHelper(private val context: Context) {

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        // Channel IDs
        const val CHANNEL_ID_FOREGROUND = "synapse_foreground_service"
        const val CHANNEL_ID_SYNC_STATUS = "synapse_sync_status"

        // Channel names
        private const val CHANNEL_NAME_FOREGROUND = "Synapse Service"
        private const val CHANNEL_NAME_SYNC_STATUS = "Sync Status"

        // Channel descriptions
        private const val CHANNEL_DESC_FOREGROUND = "Shows when Synapse overlay is active"
        private const val CHANNEL_DESC_SYNC_STATUS = "Shows sync progress and results"
    }

    /**
     * Creates all required notification channels.
     * Should be called once during app initialization or service creation.
     */
    fun createNotificationChannels() {
        // Foreground service channel - IMPORTANCE_MIN for minimal visibility
        val foregroundChannel = NotificationChannel(
            CHANNEL_ID_FOREGROUND,
            CHANNEL_NAME_FOREGROUND,
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = CHANNEL_DESC_FOREGROUND
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }

        // Sync status channel - IMPORTANCE_DEFAULT for user-visible updates
        val syncStatusChannel = NotificationChannel(
            CHANNEL_ID_SYNC_STATUS,
            CHANNEL_NAME_SYNC_STATUS,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = CHANNEL_DESC_SYNC_STATUS
            setShowBadge(true)
        }

        notificationManager.createNotificationChannel(foregroundChannel)
        notificationManager.createNotificationChannel(syncStatusChannel)
    }
}
