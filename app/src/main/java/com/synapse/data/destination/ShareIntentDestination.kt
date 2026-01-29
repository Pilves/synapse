package com.synapse.data.destination

import android.content.Context
import android.content.Intent
import com.synapse.model.*

class ShareIntentDestination(
    private val context: Context
) : Destination {
    override val id = "share"
    override val displayName = "Share to..."
    override val iconRes = android.R.drawable.ic_menu_share
    override val requiresAuth = false

    companion object {
        /** Packages known to render markdown properly */
        val MARKDOWN_FRIENDLY_PACKAGES = setOf(
            "com.discord",
            "com.slack",
            "md.obsidian",
            "notion.id",
            "it.feio.android.omninotes",
            "com.logseq.app"
        )
    }

    private val markdownFriendlyApps = MARKDOWN_FRIENDLY_PACKAGES

    override suspend fun configure(): DestinationConfig {
        return DestinationConfig(destinationId = id)
    }

    override suspend fun send(content: SyncContent): SyncResult {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, content.plainText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share note").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            SyncResult.Success
        } catch (e: Exception) {
            SyncResult.Failure(SyncError.UNKNOWN)
        }
    }

    override suspend fun testConnection(): Boolean = true

    fun formatForPackage(packageName: String, content: SyncContent): String {
        return if (packageName in markdownFriendlyApps) {
            content.markdown
        } else {
            content.plainText
        }
    }
}
