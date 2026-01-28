package com.synapse.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Helper class for creating and managing notifications for the Synapse overlay service.
 *
 * Handles:
 * - Creating notification channels with appropriate importance levels
 * - Building foreground service notifications
 * - Showing sync status notifications
 */
class NotificationHelper(private val context: Context) {

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        // Channel IDs
        const val CHANNEL_ID_FOREGROUND = "synapse_foreground_service"
        const val CHANNEL_ID_SYNC_STATUS = "synapse_sync_status"

        // Notification IDs
        const val NOTIFICATION_ID_FOREGROUND = 1001
        const val NOTIFICATION_ID_SYNC_SUCCESS = 1002
        const val NOTIFICATION_ID_SYNC_FAILED = 1003
        const val NOTIFICATION_ID_SESSION_AUTO_END = 1004
        const val NOTIFICATION_ID_QUEUED_SYNC = 1005

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

    /**
     * Builds the foreground service notification.
     *
     * @param pendingChunkCount Number of chunks pending sync (shown in notification)
     * @param mainActivityIntent Intent to launch when notification is tapped
     * @return Notification for the foreground service
     */
    fun buildForegroundNotification(
        pendingChunkCount: Int = 0,
        mainActivityIntent: PendingIntent? = null
    ): Notification {
        val contentText = if (pendingChunkCount > 0) {
            "Synapse active - $pendingChunkCount chunk${if (pendingChunkCount > 1) "s" else ""} pending"
        } else {
            "Synapse active"
        }

        return NotificationCompat.Builder(context, CHANNEL_ID_FOREGROUND)
            .setContentTitle("Synapse")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_edit) // TODO: Replace with app icon
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .apply {
                mainActivityIntent?.let { setContentIntent(it) }
            }
            .build()
    }

    /**
     * Updates the foreground notification with new pending chunk count.
     */
    fun updateForegroundNotification(
        pendingChunkCount: Int,
        mainActivityIntent: PendingIntent? = null
    ) {
        val notification = buildForegroundNotification(pendingChunkCount, mainActivityIntent)
        notificationManager.notify(NOTIFICATION_ID_FOREGROUND, notification)
    }

    /**
     * Shows a notification when sync completes successfully.
     *
     * @param syncedCount Number of chunks that were synced
     * @param projectName Name of the project synced to
     */
    fun showSyncSuccessNotification(syncedCount: Int, projectName: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SYNC_STATUS)
            .setContentTitle("Sync Complete")
            .setContentText("$syncedCount chunk${if (syncedCount > 1) "s" else ""} synced to $projectName")
            .setSmallIcon(android.R.drawable.ic_menu_upload) // TODO: Replace with app icon
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_SYNC_SUCCESS, notification)
    }

    /**
     * Shows a notification when sync fails.
     *
     * @param failedCount Number of chunks that failed to sync
     * @param successCount Number of chunks that synced successfully
     * @param retryIntent PendingIntent to retry the sync when notification is tapped
     */
    fun showSyncFailedNotification(
        failedCount: Int,
        successCount: Int = 0,
        retryIntent: PendingIntent? = null
    ) {
        val contentText = if (successCount > 0) {
            "$successCount synced, $failedCount failed - Tap to retry"
        } else {
            "$failedCount chunk${if (failedCount > 1) "s" else ""} failed to sync - Tap to retry"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SYNC_STATUS)
            .setContentTitle("Sync Failed")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // TODO: Replace with app icon
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply {
                retryIntent?.let { setContentIntent(it) }
            }
            .build()

        notificationManager.notify(NOTIFICATION_ID_SYNC_FAILED, notification)
    }

    /**
     * Shows a notification when session auto-ends due to inactivity.
     *
     * @param chunkCount Number of chunks captured in the session
     * @param openAppIntent PendingIntent to open the app
     */
    fun showSessionAutoEndNotification(
        chunkCount: Int,
        openAppIntent: PendingIntent? = null
    ) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SYNC_STATUS)
            .setContentTitle("Session Ended")
            .setContentText("Captured $chunkCount chunk${if (chunkCount > 1) "s" else ""} - Tap to review")
            .setSmallIcon(android.R.drawable.ic_menu_edit) // TODO: Replace with app icon
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply {
                openAppIntent?.let { setContentIntent(it) }
            }
            .build()

        notificationManager.notify(NOTIFICATION_ID_SESSION_AUTO_END, notification)
    }

    /**
     * Shows a notification when sync is queued for later (offline mode).
     *
     * @param chunkCount Number of chunks queued for sync
     */
    fun showSyncQueuedNotification(chunkCount: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SYNC_STATUS)
            .setContentTitle("Sync Queued")
            .setContentText("$chunkCount chunk${if (chunkCount > 1) "s" else ""} will sync when online")
            .setSmallIcon(android.R.drawable.ic_menu_upload) // TODO: Replace with app icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_QUEUED_SYNC, notification)
    }

    /**
     * Shows a notification when queued sync completes after coming online.
     *
     * @param syncedCount Number of chunks that were synced
     */
    fun showQueuedSyncCompleteNotification(syncedCount: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SYNC_STATUS)
            .setContentTitle("Sync Complete")
            .setContentText("Queued sync finished - $syncedCount chunk${if (syncedCount > 1) "s" else ""} synced")
            .setSmallIcon(android.R.drawable.ic_menu_upload) // TODO: Replace with app icon
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_SYNC_SUCCESS, notification)
    }

    /**
     * Cancels a specific notification.
     */
    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    /**
     * Cancels all notifications from this app.
     */
    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }
}
