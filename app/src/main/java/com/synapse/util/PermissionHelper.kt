package com.synapse.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Helper class for managing Android permissions required by Synapse.
 *
 * Handles:
 * - SYSTEM_ALERT_WINDOW (overlay) permission for drawing over other apps
 * - POST_NOTIFICATIONS permission for foreground service notifications (Android 13+)
 * - Checking permission status and requesting permissions
 */
class PermissionHelper(private val context: Context) {

    companion object {
        // Request codes for permission results
        const val REQUEST_CODE_OVERLAY_PERMISSION = 1001
        const val REQUEST_CODE_NOTIFICATION_PERMISSION = 1002
        const val REQUEST_CODE_STORAGE_PERMISSION = 1003
    }

    // ========================
    // Overlay Permission (SYSTEM_ALERT_WINDOW)
    // ========================

    /**
     * Checks if the app has permission to draw over other apps.
     * This permission is required for the floating capture button and preview overlay.
     *
     * @return true if the permission is granted
     */
    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Creates an Intent to open the system settings page for overlay permission.
     * The user must manually enable the permission from this settings page.
     *
     * @return Intent to launch the overlay permission settings, or null if not supported
     */
    fun getOverlayPermissionIntent(): Intent? {
        return try {
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } catch (e: Exception) {
            // Fallback to general app settings if specific intent fails
            getAppSettingsIntent()
        }
    }

    /**
     * Opens the system settings page for overlay permission.
     * Should be called from an Activity context for best results.
     *
     * @param activity The activity to use for launching the settings
     */
    fun requestOverlayPermission(activity: Activity) {
        getOverlayPermissionIntent()?.let { intent ->
            activity.startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION)
        }
    }

    // ========================
    // Notification Permission (POST_NOTIFICATIONS - Android 13+)
    // ========================

    /**
     * Checks if the app has permission to post notifications.
     * On Android 12 and below, this always returns true as the permission
     * is automatically granted.
     *
     * @return true if the permission is granted
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Prior to Android 13, notification permission is granted by default
            true
        }
    }

    /**
     * Requests notification permission from the user.
     * Only applicable on Android 13 (API 33) and above.
     *
     * @param activity The activity to use for requesting the permission
     */
    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE_NOTIFICATION_PERMISSION
            )
        }
    }

    /**
     * Checks if the notification permission rationale should be shown.
     * Returns true if the user has previously denied the permission.
     *
     * @param activity The activity context
     * @return true if rationale should be shown
     */
    fun shouldShowNotificationPermissionRationale(activity: Activity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            false
        }
    }

    // ========================
    // Storage Permission
    // ========================

    /**
     * Checks if the app has read/write storage permission.
     * On Android 10+ (API 29+), scoped storage is used and this returns true
     * as we use SAF (Storage Access Framework) for file access.
     *
     * @return true if storage can be accessed
     */
    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // On Android 10+, we use SAF for external storage access
            true
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Requests storage permission for devices running Android 9 and below.
     *
     * @param activity The activity to use for requesting the permission
     */
    fun requestStoragePermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                REQUEST_CODE_STORAGE_PERMISSION
            )
        }
    }

    // ========================
    // Utility Methods
    // ========================

    /**
     * Creates an Intent to open the app's settings page.
     * Useful as a fallback when specific permission intents fail.
     *
     * @return Intent to launch the app settings
     */
    fun getAppSettingsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Opens the app's settings page.
     */
    fun openAppSettings() {
        context.startActivity(getAppSettingsIntent())
    }

    /**
     * Checks all permissions required for the app to function properly.
     *
     * @return A map of permission names to their granted status
     */
    fun checkAllPermissions(): Map<String, Boolean> {
        return mapOf(
            "overlay" to hasOverlayPermission(),
            "notification" to hasNotificationPermission(),
            "storage" to hasStoragePermission()
        )
    }

    /**
     * Returns a list of permissions that are not yet granted.
     *
     * @return List of permission names that need to be requested
     */
    fun getMissingPermissions(): List<String> {
        return checkAllPermissions()
            .filterValues { !it }
            .keys
            .toList()
    }

    /**
     * Checks if all required permissions are granted.
     *
     * @return true if all permissions are granted
     */
    fun hasAllRequiredPermissions(): Boolean {
        return hasOverlayPermission() && hasNotificationPermission()
    }
}
