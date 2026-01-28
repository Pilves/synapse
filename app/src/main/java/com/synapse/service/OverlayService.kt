package com.synapse.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.synapse.R
import com.synapse.SynapseApplication
import com.synapse.ui.MainActivity

/**
 * Foreground service that manages the floating overlay capture button.
 *
 * This service runs in the background and displays a floating button
 * that allows users to capture handwriting from any screen.
 */
class OverlayService : Service() {

    override fun onCreate() {
        super.onCreate()
        // TODO: Initialize overlay view and window manager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startOverlay()
            ACTION_STOP -> stopOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // TODO: Clean up overlay view
    }

    private fun startOverlay() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        // TODO: Show overlay button
    }

    private fun stopOverlay() {
        // TODO: Hide overlay button
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SynapseApplication.OVERLAY_CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.synapse.action.START_OVERLAY"
        const val ACTION_STOP = "com.synapse.action.STOP_OVERLAY"
        private const val NOTIFICATION_ID = 1001
    }
}
