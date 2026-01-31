package com.synapse.data.storage

import android.content.Context
import com.synapse.model.Chunk
import com.synapse.model.Session
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
class SessionStorageTest {

    private lateinit var context: Context
    private lateinit var storage: SessionStorage

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        storage = SessionStorage(context, chunkStorage = null)
    }

    @Test
    fun `createSession returns session with unique ID`() = runTest {
        val session = storage.createSession()

        assertNotNull(session.id)
        assertTrue(session.id.isNotBlank())
        assertNotNull(session.startedAt)
        assertNull(session.endedAt)
        assertTrue(session.chunks.isEmpty())
    }

    @Test
    fun `createSession persists to disk`() = runTest {
        storage.createSession()

        val sessionsDir = java.io.File(context.filesDir, "sessions")
        val files = sessionsDir.listFiles()?.filter { it.name.endsWith(".json") }
        assertTrue(files != null && files.isNotEmpty())
    }

    @Test
    fun `getSession returns created session`() = runTest {
        val created = storage.createSession()
        val retrieved = storage.getSession(created.id)

        assertNotNull(retrieved)
        assertEquals(created.id, retrieved!!.id)
        assertEquals(created.startedAt, retrieved.startedAt)
    }

    @Test
    fun `getSession returns null for missing session`() = runTest {
        assertNull(storage.getSession("nonexistent"))
    }

    @Test
    fun `endSession sets endedAt`() = runTest {
        val created = storage.createSession()
        val ended = storage.endSession(created.id)

        assertNotNull(ended.endedAt)
        assertTrue(ended.endedAt!! >= ended.startedAt)
    }

    @Test(expected = NoSuchElementException::class)
    fun `endSession throws when session not found`() = runTest {
        storage.endSession("nonexistent")
    }

    @Test
    fun `getAllSessions returns all created sessions`() = runTest {
        storage.createSession()
        storage.createSession()

        val all = storage.getAllSessions()
        assertEquals(2, all.size)
    }

    @Test
    fun `getActiveSession returns non-ended session`() = runTest {
        val active = storage.createSession()
        val other = storage.createSession("other-session")
        storage.endSession("other-session")

        val result = storage.getActiveSession()
        assertNotNull(result)
        assertEquals(active.id, result!!.id)
    }

    @Test
    fun `getPendingSessions returns ended sessions with chunks`() = runTest {
        val session = storage.createSession()
        storage.addChunk(session.id, Chunk(
            id = "c1",
            sessionId = session.id,
            index = 0,
            filePath = "/test/path",
            timestampSeconds = 1.0f,
            createdAt = System.currentTimeMillis()
        ))
        storage.endSession(session.id)

        val pending = storage.getPendingSessions()
        assertEquals(1, pending.size)
    }

    @Test
    fun `deleteSession removes from memory and disk`() = runTest {
        val session = storage.createSession()
        val deleted = storage.deleteSession(session.id)

        assertTrue(deleted)
        assertNull(storage.getSession(session.id))
    }

    @Test
    fun `observeSessions emits updates`() = runTest {
        val initial = storage.observeSessions().first()
        assertTrue(initial.isEmpty())

        storage.createSession()
        val updated = storage.observeSessions().first()
        assertEquals(1, updated.size)
    }

    @Test
    fun `addChunk adds chunk to session`() = runTest {
        val session = storage.createSession()
        val chunk = Chunk(
            id = "c1",
            sessionId = session.id,
            index = 0,
            filePath = "/test/path",
            timestampSeconds = 1.0f,
            createdAt = System.currentTimeMillis()
        )

        val updated = storage.addChunk(session.id, chunk)
        assertEquals(1, updated.chunks.size)
        assertEquals("c1", updated.chunks[0].id)
    }

    @Test
    fun `addChunk deduplicates by chunk ID`() = runTest {
        val session = storage.createSession()
        val chunk = Chunk(
            id = "c1",
            sessionId = session.id,
            index = 0,
            filePath = "/test/path",
            timestampSeconds = 1.0f,
            createdAt = System.currentTimeMillis()
        )

        storage.addChunk(session.id, chunk)
        val updated = storage.addChunk(session.id, chunk)
        assertEquals(1, updated.chunks.size)
    }

    @Test
    fun `removeChunk removes chunk from session`() = runTest {
        val session = storage.createSession()
        val chunk = Chunk(
            id = "c1",
            sessionId = session.id,
            index = 0,
            filePath = "/test/path",
            timestampSeconds = 1.0f,
            createdAt = System.currentTimeMillis()
        )

        storage.addChunk(session.id, chunk)
        val updated = storage.removeChunk(session.id, "c1")

        assertNotNull(updated)
        assertEquals(0, updated!!.chunks.size)
    }

    @Test
    fun `generateSessionId produces unique values`() {
        val ids = (1..100).map { storage.generateSessionId() }.toSet()
        assertEquals(100, ids.size)
    }
}
