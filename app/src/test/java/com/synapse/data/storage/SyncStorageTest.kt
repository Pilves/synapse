package com.synapse.data.storage

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncStorageTest {

    private lateinit var context: Context
    private lateinit var storage: SyncStorage

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        storage = SyncStorage(context)
    }

    @Test
    fun `addToQueue creates a new queue item`() = runTest {
        val item = storage.addToQueue("s1", "p1", "notes.md")

        assertNotNull(item.id)
        assertEquals("s1", item.sessionId)
        assertEquals("p1", item.projectId)
        assertEquals("notes.md", item.filename)
        assertEquals(SyncItemStatus.PENDING, item.status)
        assertEquals(0, item.attemptCount)
        assertNull(item.errorMessage)
    }

    @Test
    fun `addToQueue returns existing item for duplicate session`() = runTest {
        val first = storage.addToQueue("s1", "p1", "notes.md")
        val second = storage.addToQueue("s1", "p1", "notes.md")

        assertEquals(first.id, second.id)
    }

    @Test
    fun `getQueue returns all items`() = runTest {
        storage.addToQueue("s1", "p1", "a.md")
        storage.addToQueue("s2", "p1", "b.md")

        val queue = storage.getQueue()
        assertEquals(2, queue.size)
    }

    @Test
    fun `getQueueItem returns item by ID`() = runTest {
        val item = storage.addToQueue("s1", "p1", "notes.md")

        val retrieved = storage.getQueueItem(item.id)
        assertNotNull(retrieved)
        assertEquals(item.id, retrieved!!.id)
    }

    @Test
    fun `getQueueItem returns null for missing ID`() = runTest {
        assertNull(storage.getQueueItem("nonexistent"))
    }

    @Test
    fun `getQueueItemBySession returns item by session ID`() = runTest {
        storage.addToQueue("s1", "p1", "notes.md")

        val result = storage.getQueueItemBySession("s1")
        assertNotNull(result)
        assertEquals("s1", result!!.sessionId)
    }

    @Test
    fun `removeFromQueue removes item`() = runTest {
        val item = storage.addToQueue("s1", "p1", "notes.md")

        storage.removeFromQueue(item.id)

        val queue = storage.getQueue()
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `markInProgress updates status and increments attempt count`() = runTest {
        val item = storage.addToQueue("s1", "p1", "notes.md")

        storage.markInProgress(item.id)

        val updated = storage.getQueueItem(item.id)
        assertNotNull(updated)
        assertEquals(SyncItemStatus.IN_PROGRESS, updated!!.status)
        assertEquals(1, updated.attemptCount)
        assertNotNull(updated.lastAttemptAt)
    }

    @Test
    fun `markCompleted removes item from queue`() = runTest {
        val item = storage.addToQueue("s1", "p1", "notes.md")

        storage.markCompleted(item.id)

        assertNull(storage.getQueueItem(item.id))
    }

    @Test
    fun `markFailed updates status and error message`() = runTest {
        val item = storage.addToQueue("s1", "p1", "notes.md")

        storage.markFailed(item.id, "Network error")

        val updated = storage.getQueueItem(item.id)
        assertNotNull(updated)
        assertEquals(SyncItemStatus.FAILED, updated!!.status)
        assertEquals("Network error", updated.errorMessage)
    }

    @Test
    fun `getPendingItems returns only pending items`() = runTest {
        val pending = storage.addToQueue("s1", "p1", "a.md")
        val failed = storage.addToQueue("s2", "p1", "b.md")
        storage.markFailed(failed.id, "error")

        val items = storage.getPendingItems()
        assertEquals(1, items.size)
        assertEquals(pending.id, items[0].id)
    }

    @Test
    fun `getFailedItems returns only failed items`() = runTest {
        storage.addToQueue("s1", "p1", "a.md")
        val item = storage.addToQueue("s2", "p1", "b.md")
        storage.markFailed(item.id, "error")

        val failed = storage.getFailedItems()
        assertEquals(1, failed.size)
    }

    @Test
    fun `resetFailedItems resets to pending`() = runTest {
        val item = storage.addToQueue("s1", "p1", "notes.md")
        storage.markFailed(item.id, "error")

        val count = storage.resetFailedItems()
        assertEquals(1, count)

        val updated = storage.getQueueItem(item.id)
        assertEquals(SyncItemStatus.PENDING, updated!!.status)
        assertNull(updated.errorMessage)
    }

    @Test
    fun `observeQueue emits updates`() = runTest {
        val initial = storage.observeQueue().first()
        assertTrue(initial.isEmpty())

        storage.addToQueue("s1", "p1", "notes.md")
        val updated = storage.observeQueue().first()
        assertEquals(1, updated.size)
    }

    @Test
    fun `queue persists to disk`() = runTest {
        storage.addToQueue("s1", "p1", "notes.md")

        // Create a new storage instance reading from the same directory
        val storage2 = SyncStorage(context)
        storage2.initialize()

        val queue = storage2.getQueue()
        assertEquals(1, queue.size)
        assertEquals("s1", queue[0].sessionId)
    }

    @Test
    fun `corrupted queue file recovers gracefully`() = runTest {
        // Write garbage to the queue file
        val syncDir = java.io.File(context.filesDir, "sync")
        syncDir.mkdirs()
        java.io.File(syncDir, "queue.json").writeText("{{invalid json}}")

        val storage2 = SyncStorage(context)
        storage2.initialize()

        val queue = storage2.getQueue()
        assertTrue(queue.isEmpty())
    }
}
