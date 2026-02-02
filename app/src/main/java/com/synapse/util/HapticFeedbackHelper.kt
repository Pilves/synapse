package com.synapse.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Centralized haptic feedback helper for service contexts (non-Compose).
 *
 * For Compose UI, prefer `LocalView.current.performHapticFeedback()` which
 * requires no VIBRATE permission. This helper is for services and managers
 * that don't have a View reference.
 */
object HapticFeedbackHelper {

    private const val TAG = "HapticFeedback"

    private const val LIGHT_MS = 10L
    private const val MEDIUM_MS = 30L
    private const val HEAVY_MS = 50L

    fun light(context: Context) = vibrate(context, LIGHT_MS)
    fun medium(context: Context) = vibrate(context, MEDIUM_MS)
    fun heavy(context: Context) = vibrate(context, HEAVY_MS)

    fun vibrate(context: Context, durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Vibration permission denied", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Vibrator not available", e)
        }
    }
}
