package com.synapse.data.repository

import com.synapse.data.storage.ChunkStorage
import com.synapse.data.storage.SessionStorage
import com.synapse.model.CapturedContext
import com.synapse.model.Chunk
import com.synapse.model.Session
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryImplTest {

    private lateinit var sessionStorage: SessionStorage
    private lateinit var chunkStorage: ChunkStorage
    private lateinit var repo: SessionRepositoryImpl

    private val testSession = Session(
        id = "s1",
        startedAt = System.currentTimeMillis(),
        endedAt = null
    )

    private val endedSession = testSession.copy(endedAt = System.currentTimeMillis())

    @Before
    fun setup() {
        sessionStorage = mockk(relaxed = true)
        chunkStorage = mockk(relaxed = true)
        repo = SessionRepositoryImpl(sessionStorage, chunkStorage)
    }

    // --- createSession ---

    @Test
    fun `createSession returns existing active session if one exists`() = runTest {
        coEvery { sessionStorage.getActiveSession() } returns testSession

        val result = repo.createSession()
        assertEquals("s1", result.id)
        coVerify(exactly = 0) { sessionStorage.createSession() }
    }

    @Test
    fun `createSession creates new when no active session`() = runTest {
        coEvery { sessionStorage.getActiveSession() } returns null
        coEvery { sessionStorage.createSession() } returns testSession

        val result = repo.createSession()
        assertEquals("s1", result.id)
        coVerify { sessionStorage.createSession() }
    }

    // --- endSession ---

    @Test
    fun `endSession calls storage when session is active`() = runTest {
        coEvery { sessionStorage.getSession("s1") } returns testSession

        repo.endSession("s1")
        coVerify { sessionStorage.endSession("s1") }
    }

    @Test
    fun `endSession is noop when session already ended`() = runTest {
        coEvery { sessionStorage.getSession("s1") } returns endedSession

        repo.endSession("s1")
        coVerify(exactly = 0) { sessionStorage.endSession(any()) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `endSession throws when session not found`() = runTest {
        coEvery { sessionStorage.getSession("missing") } returns null

        repo.endSession("missing")
    }

    // --- getSession ---

    @Test
    fun `getSession delegates to storage`() = runTest {
        coEvery { sessionStorage.getSession("s1") } returns testSession

        val result = repo.getSession("s1")
        assertNotNull(result)
        assertEquals("s1", result?.id)
    }

    @Test
    fun `getSession returns null when not found`() = runTest {
        coEvery { sessionStorage.getSession("missing") } returns null

        assertNull(repo.getSession("missing"))
    }

    // --- getPendingSessions ---

    @Test
    fun `getPendingSessions delegates to storage`() = runTest {
        coEvery { sessionStorage.getPendingSessions() } returns listOf(endedSession)

        val result = repo.getPendingSessions()
        assertEquals(1, result.size)
    }

    // --- deleteSession ---

    @Test
    fun `deleteSession deletes chunks first then metadata`() = runTest {
        repo.deleteSession("s1")

        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            chunkStorage.deleteSessionChunks("s1")
            sessionStorage.deleteSession("s1")
        }
    }

    // --- deleteChunk ---

    @Test
    fun `deleteChunk deletes file then updates metadata`() = runTest {
        repo.deleteChunk("s1", "c1")

        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            chunkStorage.deleteChunk("s1", "c1")
            sessionStorage.removeChunk("s1", "c1")
        }
    }

    // --- observeSessions ---

    @Test
    fun `observeSessions delegates to storage`() = runTest {
        coEvery { sessionStorage.observeSessions() } returns flowOf(listOf(testSession))

        val flow = repo.observeSessions()
        assertNotNull(flow)
    }

    // --- getActiveSession ---

    @Test
    fun `getActiveSession delegates to storage`() = runTest {
        coEvery { sessionStorage.getActiveSession() } returns testSession

        val result = repo.getActiveSession()
        assertEquals("s1", result?.id)
    }

    // --- addContext ---

    @Test
    fun `addContext delegates to storage`() = runTest {
        val ctx = CapturedContext.SelectedText(
            text = "test text",
            sourceApp = "Chrome",
            sourceUrl = null
        )

        repo.addContext("s1", ctx)
        coVerify { sessionStorage.addContext("s1", ctx) }
    }

    // --- removeContext ---

    @Test
    fun `removeContext delegates to storage`() = runTest {
        repo.removeContext("s1", "ctx-1")
        coVerify { sessionStorage.removeContext("s1", "ctx-1") }
    }
}
