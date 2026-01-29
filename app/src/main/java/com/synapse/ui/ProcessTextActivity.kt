package com.synapse.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.synapse.model.CapturedContext
import com.synapse.service.OverlayService

class ProcessTextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()

        if (text != null) {
            ContextHolder.pendingContext = CapturedContext.SelectedText(
                text = text,
                sourceApp = referrer?.host,
                sourceUrl = null
            )

            val overlayIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SHOW_WITH_CONTEXT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(overlayIntent)
            } else {
                startService(overlayIntent)
            }
        }

        finish()
    }
}

object ContextHolder {
    var pendingContext: CapturedContext? = null

    fun consumeContext(): CapturedContext? {
        val context = pendingContext
        pendingContext = null
        return context
    }
}
