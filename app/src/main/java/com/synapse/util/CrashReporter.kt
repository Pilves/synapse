package com.synapse.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight crash reporter that captures uncaught exceptions to a log file.
 *
 * Can be replaced with Firebase Crashlytics or Sentry by implementing
 * the same initialize() / logNonFatal() API surface.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val CRASH_LOG_DIR = "crash_logs"
    private const val MAX_CRASH_LOGS = 10

    private var context: Context? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * Initialize the crash reporter. Call from Application.onCreate().
     */
    fun initialize(appContext: Context) {
        context = appContext.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log", e)
            }
            // Forward to default handler (system crash dialog)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Clean up old crash logs
        cleanupOldLogs()
        Log.d(TAG, "Crash reporter initialized")
    }

    /**
     * Log a non-fatal exception for later analysis.
     */
    fun logNonFatal(tag: String, message: String, throwable: Throwable) {
        Log.e(tag, message, throwable)
        try {
            writeCrashLog(throwable, isFatal = false, tag = tag, message = message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write non-fatal log", e)
        }
    }

    private fun writeCrashLog(
        throwable: Throwable,
        isFatal: Boolean = true,
        tag: String? = null,
        message: String? = null
    ) {
        val ctx = context ?: return
        val crashDir = File(ctx.filesDir, CRASH_LOG_DIR)
        if (!crashDir.exists()) crashDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val prefix = if (isFatal) "crash" else "nonfatal"
        val file = File(crashDir, "${prefix}_$timestamp.txt")

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val report = buildString {
            appendLine("=== ${if (isFatal) "CRASH" else "NON-FATAL"} REPORT ===")
            appendLine("Time: $timestamp")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            if (tag != null) appendLine("Tag: $tag")
            if (message != null) appendLine("Message: $message")
            appendLine()
            appendLine("Exception: ${throwable::class.java.name}")
            appendLine("Message: ${throwable.message}")
            appendLine()
            appendLine("Stack trace:")
            appendLine(sw.toString())
        }

        file.writeText(report)
    }

    private fun cleanupOldLogs() {
        val ctx = context ?: return
        val crashDir = File(ctx.filesDir, CRASH_LOG_DIR)
        if (!crashDir.exists()) return

        val logs = crashDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        if (logs.size > MAX_CRASH_LOGS) {
            logs.drop(MAX_CRASH_LOGS).forEach { it.delete() }
        }
    }
}
