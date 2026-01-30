package com.synapse.service

import android.content.Intent

/**
 * Singleton holder for MediaProjection result data.
 *
 * Stores the result code and data Intent from the MediaProjection permission
 * consent dialog so that services (like OverlayService) can create their own
 * MediaProjection instances when needed.
 *
 * The MediaProjection token can only be obtained from an Activity result,
 * so this holder bridges the gap between the Activity that requests permission
 * and the services that need to perform screen capture.
 */
object MediaProjectionHolder {

    /** Maximum age of a stored projection result before re-consent is required. */
    private const val TOKEN_TTL_MS = 30L * 60L * 1000L // 30 minutes

    private val lock = Any()
    private var resultCode: Int? = null
    private var resultData: Intent? = null
    private var storedAtMs: Long = 0L

    /**
     * Store the MediaProjection permission result.
     * Called from MainActivity when the user grants screen capture permission.
     */
    fun setResult(resultCode: Int, data: Intent) {
        synchronized(lock) {
            this.resultCode = resultCode
            this.resultData = data
            this.storedAtMs = System.currentTimeMillis()
        }
    }

    /**
     * Get the stored result code, or null if no permission has been granted.
     */
    fun getResultCode(): Int? = synchronized(lock) { resultCode }

    /**
     * Get the stored result data Intent, or null if no permission has been granted.
     */
    fun getResultData(): Intent? = synchronized(lock) { resultData }

    /**
     * Check whether a MediaProjection result has been stored.
     */
    /**
     * Check whether a valid (non-expired) MediaProjection result has been stored.
     */
    fun hasResult(): Boolean = synchronized(lock) {
        if (resultCode == null || resultData == null) return false
        val age = System.currentTimeMillis() - storedAtMs
        if (age > TOKEN_TTL_MS) {
            // Token expired — clear stale state
            resultCode = null
            resultData = null
            storedAtMs = 0L
            return false
        }
        true
    }

    /**
     * Clear the stored result (e.g., when the projection is released).
     */
    fun clear() {
        synchronized(lock) {
            resultCode = null
            resultData = null
            storedAtMs = 0L
        }
    }
}
