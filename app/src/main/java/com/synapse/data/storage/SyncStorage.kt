package com.synapse.data.storage

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

/**
 * Handles persistent storage for sync queue items.
 *
 * Sync items represent pending, in-progress, or failed sync operations
 * that need to transcribe session chunks and write to project files.
 */
class SyncStorage(private val context: Context) {

    companion object {
        private const val TAG = "SyncStorage"
        private const val SYNC_DIR = "sync"
        private const val QUEUE_FILE = "queue.json"
    }

    private val mutex = Mutex()
    private val _queueFlow = MutableStateFlow<List<SyncQueueItem>>(emptyList())

    private val syncDir: File
        get() = File(context.filesDir, SYNC_DIR).also { it.mkdirs() }

    private val queueFile: File
        get() = File(syncDir, QUEUE_FILE)

    /**
     * Initializes the storage and loads existing queue.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        loadQueue()
    }

    /**
     * Observes the sync queue.
     *
     * @return Flow of queue items
     */
    fun observeQueue(): Flow<List<SyncQueueItem>> = _queueFlow.asStateFlow()

    /**
     * Gets all queued items.
     *
     * @return List of queue items
     */
    suspend fun getQueue(): List<SyncQueueItem> = withContext(Dispatchers.IO) {
        _queueFlow.value
    }

    /**
     * Gets a queue item by ID.
     *
     * @param id The queue item ID
     * @return The item, or null if not found
     */
    suspend fun getQueueItem(id: String): SyncQueueItem? = withContext(Dispatchers.IO) {
        _queueFlow.value.find { it.id == id }
    }

    /**
     * Gets a queue item by session ID.
     *
     * @param sessionId The session ID
     * @return The item, or null if not found
     */
    suspend fun getQueueItemBySession(sessionId: String): SyncQueueItem? = withContext(Dispatchers.IO) {
        _queueFlow.value.find { it.sessionId == sessionId }
    }

    /**
     * Adds a new item to the sync queue.
     *
     * @param sessionId The session to sync
     * @param projectId The target project
     * @param filename The target filename
     * @return The created queue item
     */
    suspend fun addToQueue(
        sessionId: String,
        projectId: String,
        filename: String
    ): SyncQueueItem = mutex.withLock {
        // Check if already queued
        val existing = _queueFlow.value.find { it.sessionId == sessionId }
        if (existing != null) {
            Log.w(TAG, "Session $sessionId already in queue")
            return@withLock existing
        }

        val item = SyncQueueItem(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            projectId = projectId,
            filename = filename,
            status = SyncItemStatus.PENDING,
            createdAt = System.currentTimeMillis(),
            lastAttemptAt = null,
            attemptCount = 0,
            errorMessage = null
        )

        val queue = loadQueueFromDisk().toMutableList()
        queue.add(item)
        saveQueueToDisk(queue)
        _queueFlow.value = queue

        Log.d(TAG, "Added to sync queue: ${item.id} (session: $sessionId)")
        item
    }

    /**
     * Updates a queue item.
     *
     * @param item The updated item
     */
    suspend fun updateQueueItem(item: SyncQueueItem) = mutex.withLock {
        val queue = loadQueueFromDisk().toMutableList()
        val index = queue.indexOfFirst { it.id == item.id }

        if (index != -1) {
            queue[index] = item
            saveQueueToDisk(queue)
            _queueFlow.value = queue
            Log.d(TAG, "Updated queue item: ${item.id}")
        }
    }

    /**
     * Removes an item from the queue.
     *
     * @param id The queue item ID
     */
    suspend fun removeFromQueue(id: String) = mutex.withLock {
        val queue = loadQueueFromDisk().toMutableList()
        val removed = queue.removeAll { it.id == id }

        if (removed) {
            saveQueueToDisk(queue)
            _queueFlow.value = queue
            Log.d(TAG, "Removed from queue: $id")
        }
    }

    /**
     * Gets all pending items in the queue.
     *
     * @return List of pending items, sorted by creation time
     */
    suspend fun getPendingItems(): List<SyncQueueItem> = withContext(Dispatchers.IO) {
        _queueFlow.value
            .filter { it.status == SyncItemStatus.PENDING }
            .sortedBy { it.createdAt }
    }

    /**
     * Gets all failed items in the queue.
     *
     * @return List of failed items
     */
    suspend fun getFailedItems(): List<SyncQueueItem> = withContext(Dispatchers.IO) {
        _queueFlow.value.filter { it.status == SyncItemStatus.FAILED }
    }

    /**
     * Marks an item as in progress.
     *
     * @param id The queue item ID
     */
    suspend fun markInProgress(id: String) {
        val item = getQueueItem(id) ?: return
        updateQueueItem(item.copy(
            status = SyncItemStatus.IN_PROGRESS,
            lastAttemptAt = System.currentTimeMillis(),
            attemptCount = item.attemptCount + 1
        ))
    }

    /**
     * Marks an item as completed and removes it from the queue.
     *
     * @param id The queue item ID
     */
    suspend fun markCompleted(id: String) {
        removeFromQueue(id)
    }

    /**
     * Marks an item as failed.
     *
     * @param id The queue item ID
     * @param errorMessage The error message
     */
    suspend fun markFailed(id: String, errorMessage: String) {
        val item = getQueueItem(id) ?: return
        updateQueueItem(item.copy(
            status = SyncItemStatus.FAILED,
            errorMessage = errorMessage
        ))
    }

    /**
     * Resets all failed items to pending for retry.
     *
     * @return Number of items reset
     */
    suspend fun resetFailedItems(): Int = mutex.withLock {
        val queue = loadQueueFromDisk().toMutableList()
        var count = 0

        queue.forEachIndexed { index, item ->
            if (item.status == SyncItemStatus.FAILED) {
                queue[index] = item.copy(
                    status = SyncItemStatus.PENDING,
                    errorMessage = null
                )
                count++
            }
        }

        if (count > 0) {
            saveQueueToDisk(queue)
            _queueFlow.value = queue
            Log.d(TAG, "Reset $count failed items to pending")
        }

        count
    }

    private suspend fun loadQueue() {
        val queue = loadQueueFromDisk()
        _queueFlow.value = queue
    }

    private fun loadQueueFromDisk(): List<SyncQueueItem> {
        return try {
            if (!queueFile.exists()) {
                return emptyList()
            }

            val content = queueFile.readText()
            val dto = StorageJson.instance.decodeFromString<SyncQueueDto>(content)
            dto.items.map { it.toSyncQueueItem() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sync queue", e)
            emptyList()
        }
    }

    private fun saveQueueToDisk(queue: List<SyncQueueItem>) {
        try {
            val dto = SyncQueueDto(
                items = queue.map { SyncQueueItemDto.fromSyncQueueItem(it) }
            )
            StorageHelper.atomicWriteText(queueFile, StorageJson.instance.encodeToString(dto))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sync queue", e)
        }
    }
}

/**
 * Represents an item in the sync queue.
 */
data class SyncQueueItem(
    val id: String,
    val sessionId: String,
    val projectId: String,
    val filename: String,
    val status: SyncItemStatus,
    val createdAt: Long,
    val lastAttemptAt: Long?,
    val attemptCount: Int,
    val errorMessage: String?
)

/**
 * Status of a sync queue item.
 */
enum class SyncItemStatus {
    PENDING,
    IN_PROGRESS,
    FAILED
}

/**
 * DTO for sync queue serialization.
 */
@Serializable
private data class SyncQueueDto(
    val items: List<SyncQueueItemDto>
)

/**
 * DTO for sync queue item serialization.
 */
@Serializable
private data class SyncQueueItemDto(
    val id: String,
    val sessionId: String,
    val projectId: String,
    val filename: String,
    val status: String,
    val createdAt: Long,
    val lastAttemptAt: Long? = null,
    val attemptCount: Int = 0,
    val errorMessage: String? = null
) {
    fun toSyncQueueItem(): SyncQueueItem = SyncQueueItem(
        id = id,
        sessionId = sessionId,
        projectId = projectId,
        filename = filename,
        status = try { SyncItemStatus.valueOf(status) } catch (_: IllegalArgumentException) { SyncItemStatus.FAILED },
        createdAt = createdAt,
        lastAttemptAt = lastAttemptAt,
        attemptCount = attemptCount,
        errorMessage = errorMessage
    )

    companion object {
        fun fromSyncQueueItem(item: SyncQueueItem): SyncQueueItemDto = SyncQueueItemDto(
            id = item.id,
            sessionId = item.sessionId,
            projectId = item.projectId,
            filename = item.filename,
            status = item.status.name,
            createdAt = item.createdAt,
            lastAttemptAt = item.lastAttemptAt,
            attemptCount = item.attemptCount,
            errorMessage = item.errorMessage
        )
    }
}
