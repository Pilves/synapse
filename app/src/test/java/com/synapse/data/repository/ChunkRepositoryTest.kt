package com.synapse.data.repository

import com.synapse.data.storage.ChunkStorage
import com.synapse.data.storage.SessionStorage
import com.synapse.model.Chunk
import com.synapse.model.Session
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChunkRepositoryTest {

    private lateinit var chunkStorage: ChunkStorage
    private lateinit var sessionStorage: SessionStorage
    private lateinit var repo: ChunkRepository

    private val testChunk = Chunk(
        id = "s1_0",
        sessionId = "s1",
        index = 0,
        filePath = "/test/chunk.webp",
        timestampSeconds = 1.0f,
        createdAt = System.currentTimeMillis()
    )

    private val testSession = Session(
        id = "s1",
        startedAt = System.currentTimeMillis(),
        endedAt = null,
        chunks = listOf(testChunk)
    )

    @Before
    fun setup() {
        chunkStorage = mockk(relaxed = true)
        sessionStorage = mockk(relaxed = true)
        repo = ChunkRepository(chunkStorage, sessionStorage)
    }

    // --- saveChunk ---

    @Test(expected = IllegalArgumentException::class)
    fun `saveChunk throws when session not found`() = runTest {
        coEvery { sessionStorage.getSession("missing") } returns null

        repo.saveChunk("missing", mockk(relaxed = true), 1.0f)
    }

    @Test
    fun `saveChunk creates chunk with correct index`() = runTest {
        coEvery { sessionStorage.getSession("s1") } returns testSession
        coEvery { sessionStorage.getNextChunkIndex("s1") } returns 1
        coEvery { chunkStorage.saveChunk("s1", 1, any()) } returns Pair("s1_1", "/path/chunk1.webp")
        coEvery { sessionStorage.addChunk("s1", any()) } returns testSession

        val bitmap = mockk<android.graphics.Bitmap>(relaxed = true)
        val chunk = repo.saveChunk("s1", bitmap, 2.5f)

        assertEquals("s1_1", chunk.id)
        assertEquals(1, chunk.index)
        assertEquals(2.5f, chunk.timestampSeconds)
    }

    @Test
    fun `saveChunk adds chunk to session storage`() = runTest {
        coEvery { sessionStorage.getSession("s1") } returns testSession
        coEvery { sessionStorage.getNextChunkIndex("s1") } returns 0
        coEvery { chunkStorage.saveChunk("s1", 0, any()) } returns Pair("s1_0", "/path")
        coEvery { sessionStorage.addChunk("s1", any()) } returns testSession

        repo.saveChunk("s1", mockk(relaxed = true), 0f)

        coVerify { sessionStorage.addChunk("s1", any()) }
    }

    // --- getChunksForSession ---

    @Test
    fun `getChunksForSession returns sorted chunks`() = runTest {
        val chunk0 = testChunk.copy(index = 0)
        val chunk1 = testChunk.copy(id = "s1_1", index = 1)
        val session = testSession.copy(chunks = listOf(chunk1, chunk0))
        coEvery { sessionStorage.getSession("s1") } returns session

        val chunks = repo.getChunksForSession("s1")
        assertEquals(2, chunks.size)
        assertEquals(0, chunks[0].index)
        assertEquals(1, chunks[1].index)
    }

    @Test
    fun `getChunksForSession returns empty for missing session`() = runTest {
        coEvery { sessionStorage.getSession("missing") } returns null

        val chunks = repo.getChunksForSession("missing")
        assertTrue(chunks.isEmpty())
    }

    // --- getChunk ---

    @Test
    fun `getChunk returns chunk when found`() = runTest {
        coEvery { sessionStorage.findChunk("s1_0") } returns Pair("s1", testChunk)

        val chunk = repo.getChunk("s1_0")
        assertNotNull(chunk)
        assertEquals("s1_0", chunk!!.id)
    }

    @Test
    fun `getChunk returns null when not found`() = runTest {
        coEvery { sessionStorage.findChunk("missing") } returns null

        assertNull(repo.getChunk("missing"))
    }

    // --- deleteChunk ---

    @Test
    fun `deleteChunk removes from storage and session`() = runTest {
        coEvery { sessionStorage.findChunk("s1_0") } returns Pair("s1", testChunk)

        repo.deleteChunk("s1_0")

        coVerify { chunkStorage.deleteChunk("s1", "s1_0") }
        coVerify { sessionStorage.removeChunk("s1", "s1_0") }
    }

    @Test
    fun `deleteChunk is noop when chunk not found`() = runTest {
        coEvery { sessionStorage.findChunk("missing") } returns null

        repo.deleteChunk("missing")

        coVerify(exactly = 0) { chunkStorage.deleteChunk(any(), any()) }
    }

    // --- markChunkCorrupted ---

    @Test
    fun `markChunkCorrupted updates chunk in session`() = runTest {
        coEvery { sessionStorage.findChunk("s1_0") } returns Pair("s1", testChunk)

        repo.markChunkCorrupted("s1_0")

        coVerify {
            sessionStorage.updateChunk("s1", match { it.isCorrupted })
        }
    }

    @Test
    fun `markChunkCorrupted is noop when chunk not found`() = runTest {
        coEvery { sessionStorage.findChunk("missing") } returns null

        repo.markChunkCorrupted("missing")

        coVerify(exactly = 0) { sessionStorage.updateChunk(any(), any()) }
    }

    // --- getChunkImage ---

    @Test
    fun `getChunkImage returns null for corrupted chunk`() = runTest {
        val corrupted = testChunk.copy(isCorrupted = true)
        coEvery { sessionStorage.findChunk("s1_0") } returns Pair("s1", corrupted)

        val result = repo.getChunkImage("s1_0")
        assertNull(result)
    }

    @Test
    fun `getChunkImage returns null when chunk not found`() = runTest {
        coEvery { sessionStorage.findChunk("missing") } returns null

        assertNull(repo.getChunkImage("missing"))
    }
}
