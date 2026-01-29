package com.synapse.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.synapse.R
import com.synapse.SynapseApplication
import com.synapse.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Service for processing handwriting captures.
 *
 * Handles image processing and API calls for OCR/transcription
 * in the background with progress notifications.
 *
 * Note: This service is designed for on-demand single-image processing.
 * The main capture workflow uses OverlayService and SyncRepository for batch processing.
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        const val ACTION_PROCESS_CAPTURE = "com.synapse.action.PROCESS_CAPTURE"
        const val ACTION_CANCEL = "com.synapse.action.CANCEL_PROCESSING"
        const val EXTRA_IMAGE_PATH = "extra_image_path"
        const val EXTRA_SESSION_ID = "extra_session_id"
        private const val NOTIFICATION_ID = 1002
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentProcessingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CaptureService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROCESS_CAPTURE -> {
                val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                if (imagePath != null) {
                    processCapture(imagePath, sessionId)
                } else {
                    Log.w(TAG, "No image path provided")
                    stopSelf()
                }
            }
            ACTION_CANCEL -> cancelProcessing()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "CaptureService destroyed")
    }

    private fun processCapture(imagePath: String, sessionId: String?) {
        val notification = createNotification(0)
        startForeground(NOTIFICATION_ID, notification)

        currentProcessingJob = serviceScope.launch {
            try {
                Log.d(TAG, "Processing capture: $imagePath")

                // 1. Validate image file exists
                val imageFile = File(imagePath)
                if (!imageFile.exists()) {
                    Log.e(TAG, "Image file not found: $imagePath")
                    showErrorNotification("Image file not found")
                    stopSelf()
                    return@launch
                }

                // 2. Update progress - loading image
                updateNotificationProgress(25)

                // 3. Load image bytes
                val imageBytes = imageFile.readBytes()
                if (imageBytes.isEmpty()) {
                    Log.e(TAG, "Image file is empty: $imagePath")
                    showErrorNotification("Image file is empty")
                    stopSelf()
                    return@launch
                }

                // 4. Update progress - processing
                updateNotificationProgress(50)

                // Note: Direct transcription is handled by SyncRepository.
                // This service validates the image exists and is readable.
                // For full transcription, use the Review screen sync flow.

                // 5. Update progress - complete
                updateNotificationProgress(100)
                Log.d(TAG, "Capture validated successfully: ${imageBytes.size} bytes")

                showSuccessNotification()
            } catch (e: Exception) {
                Log.e(TAG, "Error processing capture", e)
                showErrorNotification(e.message ?: "Unknown error")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cancelProcessing() {
        Log.d(TAG, "Cancelling processing")
        currentProcessingJob?.cancel()
        currentProcessingJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(progress: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SynapseApplication.CAPTURE_CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()
    }

    private fun updateNotificationProgress(progress: Int) {
        val notification = createNotification(progress)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showSuccessNotification() {
        val notification = NotificationCompat.Builder(this, SynapseApplication.CAPTURE_CHANNEL_ID)
            .setContentTitle("Capture Ready")
            .setContentText("Image ready for sync")
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showErrorNotification(message: String) {
        val notification = NotificationCompat.Builder(this, SynapseApplication.CAPTURE_CHANNEL_ID)
            .setContentTitle("Capture Error")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID + 2, notification)
    }
}
