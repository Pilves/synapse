package com.synapse.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.synapse.model.CapturedContext
import com.synapse.service.OverlayService
import java.util.concurrent.atomic.AtomicReference

class ProcessTextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.take(50_000)

        if (text != null) {
            ContextHolder.setPendingContext(CapturedContext.SelectedText(
                text = text,
                sourceApp = referrer?.host,
                sourceUrl = null
            ))

            val overlayIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SHOW_WITH_CONTEXT
            }
            startForegroundService(overlayIntent)

            val preview = if (text.length > 40) text.take(40) + "…" else text
            Toast.makeText(this, "✓ Text captured: $preview", Toast.LENGTH_SHORT).show()
        }

        finish()
    }
}

object ContextHolder {
    private val pendingContext = AtomicReference<CapturedContext?>(null)

    fun setPendingContext(context: CapturedContext) {
        pendingContext.set(context)
    }

    fun consumeContext(): CapturedContext? {
        return pendingContext.getAndSet(null)
    }
}
