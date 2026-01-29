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

    private var resultCode: Int? = null
    private var resultData: Intent? = null

    /**
     * Store the MediaProjection permission result.
     * Called from MainActivity when the user grants screen capture permission.
     */
    fun setResult(resultCode: Int, data: Intent) {
        this.resultCode = resultCode
        this.resultData = data
    }

    /**
     * Get the stored result code, or null if no permission has been granted.
     */
    fun getResultCode(): Int? = resultCode

    /**
     * Get the stored result data Intent, or null if no permission has been granted.
     */
    fun getResultData(): Intent? = resultData

    /**
     * Check whether a MediaProjection result has been stored.
     */
    fun hasResult(): Boolean = resultCode != null && resultData != null

    /**
     * Clear the stored result (e.g., when the projection is released).
     */
    fun clear() {
        resultCode = null
        resultData = null
    }
}
