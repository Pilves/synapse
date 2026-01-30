package com.synapse.service

import android.graphics.Rect
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import android.util.Log

/**
 * Handles gesture/input dispatch and region selection for OverlayService.
 *
 * Encapsulates haptic feedback and region capture delegation.
 */
class InputDispatcher(private val context: Context) {

    companion object {
        private const val TAG = "InputDispatcher"
    }

    /**
     * Triggers a short haptic vibration for region selection feedback.
     */
    fun vibrateForRegionSelection() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(50)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to vibrate", e)
        }
    }
}
