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
 * Service for processing handwriting captures.
 *
 * Handles image processing and API calls for OCR/transcription
 * in the background with progress notifications.
 */
class CaptureService : Service() {

    override fun onCreate() {
        super.onCreate()
        // TODO: Initialize capture processing
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROCESS_CAPTURE -> {
                val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
                if (imagePath != null) {
                    processCapture(imagePath)
                }
            }
            ACTION_CANCEL -> cancelProcessing()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // TODO: Clean up resources
    }

    private fun processCapture(imagePath: String) {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        // TODO: Implement capture processing
        // 1. Load and preprocess image
        // 2. Send to API for transcription
        // 3. Store result
        // 4. Show result notification
    }

    private fun cancelProcessing() {
        // TODO: Cancel ongoing processing
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

        return NotificationCompat.Builder(this, SynapseApplication.CAPTURE_CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(pendingIntent)
            .setProgress(100, 0, true)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_PROCESS_CAPTURE = "com.synapse.action.PROCESS_CAPTURE"
        const val ACTION_CANCEL = "com.synapse.action.CANCEL_PROCESSING"
        const val EXTRA_IMAGE_PATH = "extra_image_path"
        private const val NOTIFICATION_ID = 1002
    }
}
