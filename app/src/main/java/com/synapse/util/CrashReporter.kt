package com.synapse.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Crash reporter backed by Firebase Crashlytics.
 *
 * Keeps the same public API (initialize / logNonFatal) so existing
 * call sites in SynapseApplication do not need to change.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"

    /**
     * Initialize the crash reporter. Call from Application.onCreate().
     */
    fun initialize(appContext: Context) {
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCrashlyticsCollectionEnabled(true)

            // Set baseline device keys for every future report
            crashlytics.setCustomKey("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            crashlytics.setCustomKey("api_level", Build.VERSION.SDK_INT)
            crashlytics.setCustomKey("os_version", Build.VERSION.RELEASE)

            Log.d(TAG, "Crashlytics initialized")
        } catch (e: Exception) {
            // Firebase not configured (missing google-services.json) – app still runs
            Log.w(TAG, "Firebase not configured – crash reporting disabled. " +
                    "Add google-services.json to app/ to enable Crashlytics.", e)
        }
    }

    /**
     * Log a non-fatal exception for later analysis.
     */
    fun logNonFatal(tag: String, message: String, throwable: Throwable) {
        Log.e(tag, message, throwable)
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("tag", tag)
            crashlytics.setCustomKey("message", message)
            crashlytics.recordException(throwable)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record non-fatal exception", e)
        }
    }
}
