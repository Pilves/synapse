package com.synapse.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.synapse.util.OutputSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Manages Obsidian vault access via Storage Access Framework (SAF).
 *
 * Provides operations for:
 * - Setting and persisting vault root URI with persistent permissions
 * - Listing folders within the vault for project selection
 * - Reading, writing, and appending markdown content to vault files
 *
 * All file operations use DocumentFile for SAF compatibility.
 */
class VaultManager(private val context: Context) {

    companion object {
        private const val TAG = "VaultManager"
        private const val PREFS_NAME = "vault_prefs"
        private const val KEY_VAULT_ROOT_URI = "vault_root_uri"
    }

    private val appendMutex = Mutex()

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Sets the vault root URI and takes persistent permission.
     *
     * This should be called after the user selects a folder via SAF picker.
     * The URI will be stored persistently along with read/write permissions.
     *
     * @param uri The URI returned from the SAF folder picker
     */
    suspend fun setVaultRoot(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            // Take persistable URI permission for both read and write
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

            context.contentResolver.takePersistableUriPermission(uri, takeFlags)

            // Store the URI
            prefs.edit()
                .putString(KEY_VAULT_ROOT_URI, uri.toString())
                .apply()

            Log.d(TAG, "Vault root set: $uri")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to take persistable permission", e)
            throw e
        }
    }

    /**
     * Gets the currently configured vault root URI.
     *
     * @return The vault root URI, or null if not configured
     */
    fun getVaultRoot(): Uri? {
        val uriString = prefs.getString(KEY_VAULT_ROOT_URI, null)
        return uriString?.let { Uri.parse(it) }
    }

    /**
     * Checks if a vault has been configured.
     *
     * @return true if a vault root URI is stored
     */
    fun hasVaultConfigured(): Boolean {
        return getVaultRoot() != null
    }

    /**
     * Checks if we still have permission to access the vault.
     *
     * @return true if we have valid read/write permission to the vault
     */
    fun hasValidPermission(): Boolean {
        val vaultUri = getVaultRoot() ?: return false

        val persistedPermissions = context.contentResolver.persistedUriPermissions
        return persistedPermissions.any { permission ->
            permission.uri == vaultUri &&
                    permission.isReadPermission &&
                    permission.isWritePermission
        }
    }

    /**
     * Lists all subfolders within a parent folder.
     *
     * Useful for letting users select a project folder within the vault.
     *
     * @param parentUri The parent folder URI to list
     * @param maxDepth Maximum depth to recurse (default 3)
     * @return List of folder information, sorted alphabetically by name
     */
    suspend fun listFolders(parentUri: Uri, maxDepth: Int = 3): List<FolderInfo> = withContext(Dispatchers.IO) {
        try {
            val parentDoc = DocumentFile.fromTreeUri(context, parentUri)
            if (parentDoc == null || !parentDoc.exists()) {
                Log.w(TAG, "Parent folder does not exist: $parentUri")
                return@withContext emptyList()
            }

            listFoldersRecursive(parentDoc, maxDepth, 0)
                .sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list folders", e)
            emptyList()
        }
    }

    /**
     * Recursively lists folders up to maxDepth.
     */
    private fun listFoldersRecursive(parent: DocumentFile, maxDepth: Int, currentDepth: Int): List<FolderInfo> {
        if (currentDepth >= maxDepth) return emptyList()

        val result = mutableListOf<FolderInfo>()

        parent.listFiles()
            .filter { it.isDirectory }
            .forEach { folder ->
                folder.name?.let { name ->
                    // Skip hidden folders (e.g. .obsidian, .git, .trash)
                    if (name.startsWith(".")) return@forEach
                    result.add(FolderInfo(name = name, uri = folder.uri))
                    // Recurse into subfolders
                    result.addAll(listFoldersRecursive(folder, maxDepth, currentDepth + 1))
                }
            }

        return result
    }

    /**
     * Checks if a folder exists at the given URI.
     *
     * @param uri The folder URI to check
     * @return true if the folder exists and is accessible
     */
    suspend fun folderExists(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val doc = DocumentFile.fromTreeUri(context, uri)
            doc?.exists() == true && doc.isDirectory
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check folder existence", e)
            false
        }
    }

    /**
     * Appends markdown content to a file within a project folder.
     *
     * If the file doesn't exist, it will be created first.
     * Content is appended to the end of the existing file.
     *
     * @param projectUri The project folder URI
     * @param filename The name of the file to append to (e.g., "notes.md")
     * @param content The markdown content to append
     * @return WriteResult indicating success or failure
     */
    suspend fun appendToFile(
        projectUri: Uri,
        filename: String,
        content: String
    ): WriteResult = appendMutex.withLock {
        withContext(Dispatchers.IO) {
        try {
            val projectDoc = DocumentFile.fromTreeUri(context, projectUri)
            if (projectDoc == null || !projectDoc.exists()) {
                return@withContext WriteResult.Error("Project folder not found")
            }

            // Find or create the file
            var fileDoc = projectDoc.findFile(filename)
            if (fileDoc == null) {
                fileDoc = projectDoc.createFile("text/markdown", filename)
                if (fileDoc == null) {
                    return@withContext WriteResult.Error("Failed to create file: $filename")
                }
                Log.d(TAG, "Created new file: $filename")
            }

            // Sanitize and append content
            val sanitizedContent = OutputSanitizer.sanitize(content)
            context.contentResolver.openOutputStream(fileDoc.uri, "wa")?.use { outputStream ->
                outputStream.write(sanitizedContent.toByteArray(Charsets.UTF_8))
                outputStream.flush()
            } ?: return@withContext WriteResult.Error("Failed to open file for writing")

            Log.d(TAG, "Successfully appended to file: $filename")
            WriteResult.Success
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied writing to file", e)
            WriteResult.Error("Permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append to file", e)
            WriteResult.Error("Write failed: ${e.message}")
        }
    }
    }

    /**
     * Creates a file if it doesn't already exist.
     *
     * @param projectUri The project folder URI
     * @param filename The name of the file to create
     * @return The URI of the created or existing file, or null on failure
     */
    suspend fun createFileIfNeeded(
        projectUri: Uri,
        filename: String
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val projectDoc = DocumentFile.fromTreeUri(context, projectUri)
            if (projectDoc == null || !projectDoc.exists()) {
                Log.w(TAG, "Project folder not found: $projectUri")
                return@withContext null
            }

            // Check if file already exists
            val existingFile = projectDoc.findFile(filename)
            if (existingFile != null && existingFile.exists()) {
                Log.d(TAG, "File already exists: $filename")
                return@withContext existingFile.uri
            }

            // Create the file
            val newFile = projectDoc.createFile("text/markdown", filename)
            if (newFile == null) {
                Log.e(TAG, "Failed to create file: $filename")
                return@withContext null
            }

            Log.d(TAG, "Created file: $filename")
            newFile.uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create file", e)
            null
        }
    }

    /**
     * Reads the content of a file within a project folder.
     *
     * @param projectUri The project folder URI
     * @param filename The name of the file to read
     * @return The file content as a string, or null if the file doesn't exist or cannot be read
     */
    suspend fun readFile(
        projectUri: Uri,
        filename: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val projectDoc = DocumentFile.fromTreeUri(context, projectUri)
            if (projectDoc == null || !projectDoc.exists()) {
                Log.w(TAG, "Project folder not found: $projectUri")
                return@withContext null
            }

            val fileDoc = projectDoc.findFile(filename)
            if (fileDoc == null || !fileDoc.exists()) {
                Log.d(TAG, "File not found: $filename")
                return@withContext null
            }

            readFileContent(fileDoc.uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file", e)
            null
        }
    }

    /**
     * Reads content from a file URI.
     */
    private fun readFileContent(fileUri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file content", e)
            null
        }
    }

    /**
     * Clears the stored vault root URI and releases permissions.
     */
    suspend fun clearVaultRoot() = withContext(Dispatchers.IO) {
        try {
            getVaultRoot()?.let { uri ->
                try {
                    val releaseFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.releasePersistableUriPermission(uri, releaseFlags)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Failed to release permission (may already be released)", e)
                }
            }

            prefs.edit()
                .remove(KEY_VAULT_ROOT_URI)
                .apply()

            Log.d(TAG, "Vault root cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear vault root", e)
        }
    }
}

/**
 * Information about a folder in the vault.
 *
 * @property name The display name of the folder
 * @property uri The SAF URI for accessing the folder
 */
data class FolderInfo(
    val name: String,
    val uri: Uri
)

/**
 * Result of a file write operation.
 */
sealed class WriteResult {
    /** Write completed successfully */
    object Success : WriteResult()

    /** Write failed with an error message */
    data class Error(val message: String) : WriteResult()
}
