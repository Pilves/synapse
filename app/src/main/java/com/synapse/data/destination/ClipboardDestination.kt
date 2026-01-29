package com.synapse.data.destination

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.synapse.model.*

class ClipboardDestination(
    private val context: Context
) : Destination {
    override val id = "clipboard"
    override val displayName = "Clipboard"
    override val iconRes = android.R.drawable.ic_menu_edit
    override val requiresAuth = false

    override suspend fun configure(): DestinationConfig {
        return DestinationConfig(destinationId = id)
    }

    override suspend fun send(content: SyncContent): SyncResult {
        return try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.setPrimaryClip(
                ClipData.newPlainText("Synapse Note", content.markdown)
            )
            SyncResult.Success
        } catch (e: Exception) {
            SyncResult.Failure(SyncError.UNKNOWN)
        }
    }

    override suspend fun testConnection(): Boolean = true
}
