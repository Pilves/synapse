package com.synapse.data.destination

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.synapse.model.*

class LocalFolderDestination(
    private val context: Context
) : Destination {
    override val id = "local_folder"
    override val displayName = "Local Folder"
    override val iconRes = android.R.drawable.ic_menu_save
    override val requiresAuth = false

    private var fileUri: Uri? = null

    fun setFileUri(uri: Uri) {
        fileUri = uri
    }

    override suspend fun configure(): DestinationConfig {
        return DestinationConfig(
            destinationId = id,
            settings = mapOf("fileUri" to (fileUri?.toString() ?: ""))
        )
    }

    override suspend fun send(content: SyncContent): SyncResult {
        val uri = fileUri ?: return SyncResult.Failure(SyncError.NOT_FOUND)

        return try {
            val docFile = DocumentFile.fromSingleUri(context, uri)
            if (docFile != null && docFile.exists()) {
                context.contentResolver.openOutputStream(uri, "wa")?.use { stream ->
                    stream.write("\n\n".toByteArray())
                    stream.write(content.markdown.toByteArray())
                }
                SyncResult.Success
            } else {
                SyncResult.Failure(SyncError.NOT_FOUND)
            }
        } catch (e: SecurityException) {
            SyncResult.Failure(SyncError.PERMISSION_DENIED)
        } catch (e: Exception) {
            SyncResult.Failure(SyncError.UNKNOWN)
        }
    }

    override suspend fun testConnection(): Boolean {
        val uri = fileUri ?: return false
        return try {
            val docFile = DocumentFile.fromSingleUri(context, uri)
            docFile?.exists() == true
        } catch (e: Exception) {
            false
        }
    }
}
