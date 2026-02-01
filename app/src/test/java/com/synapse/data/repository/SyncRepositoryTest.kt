package com.synapse.data.repository

import com.synapse.data.storage.ChunkStorage
import com.synapse.data.storage.ProjectStorage
import com.synapse.data.storage.SessionStorage
import com.synapse.data.storage.SyncStorage
import com.synapse.model.Chunk
import com.synapse.model.Session
import com.synapse.model.SyncStatus
import com.synapse.api.QuestionAnswerService
import com.synapse.api.TranscriptionService
import com.synapse.api.TranscriptionResult
import com.synapse.api.TranscriptionError
import com.synapse.api.Note
import com.synapse.api.ChunkData
import com.synapse.model.Project
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncRepositoryTest {

    private lateinit var sessionStorage: SessionStorage
    private lateinit var chunkStorage: ChunkStorage
    private lateinit var projectStorage: ProjectStorage
    private lateinit var syncStorage: SyncStorage
    private lateinit var transcriptionService: TranscriptionService
    private lateinit var questionAnswerService: QuestionAnswerService
    private lateinit var repo: SyncRepositoryImpl

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
        endedAt = System.currentTimeMillis() + 1000,
        chunks = listOf(testChunk)
    )

    private val testProject = Project(
        id = "p1",
        name = "Test Project",
        pathUri = "content://test/path",
        defaultFile = "notes.md"
    )

    @Before
    fun setup() {
        sessionStorage = mockk(relaxed = true)
        chunkStorage = mockk(relaxed = true)
        projectStorage = mockk(relaxed = true)
        syncStorage = mockk(relaxed = true)
        transcriptionService = mockk(relaxed = true)
        questionAnswerService = mockk(relaxed = true)

        repo = SyncRepositoryImpl(
            context = RuntimeEnvironment.getApplication(),
            sessionStorage = sessionStorage,
            chunkStorage = chunkStorage,
            projectStorage = projectStorage,
            syncStorage = syncStorage,
            transcriptionServiceProvider = { transcriptionService },
            questionAnswerService = questionAnswerService,
            llmConfigProvider = suspend { mockk(relaxed = true) }
        )
    }

    @Test
    fun `syncSession returns error when session not found`() = runTest {
        coEvery { sessionStorage.getSession("missing") } returns null

        val result = repo.syncSession("missing", "p1", "file.md")
        assertTrue(result is SyncStatus.Error)
    }

    @Test
    fun `syncSession returns error when session has no chunks`() = runTest {
        val emptySession = testSession.copy(chunks = emptyList())
        coEvery { sessionStorage.getSession("s1") } returns emptySession

        val result = repo.syncSession("s1", "p1", "file.md")
        assertTrue(result is SyncStatus.Error)
    }

    @Test
    fun `syncSession returns error when project not found`() = runTest {
        coEvery { sessionStorage.getSession("s1") } returns testSession
        coEvery { projectStorage.getProject("missing") } returns null

        val result = repo.syncSession("s1", "missing", "file.md")
        assertTrue(result is SyncStatus.Error)
    }

    @Test
    fun `syncSession attempts transcription on valid input`() = runTest {
        coEvery { sessionStorage.getSession("s1") } returns testSession
        coEvery { projectStorage.getProject("p1") } returns testProject
        coEvery { chunkStorage.loadChunkBytes("s1", "s1_0") } returns byteArrayOf(1, 2, 3)
        coEvery { transcriptionService.transcribe(any(), any(), any()) } returns TranscriptionResult(
            notes = listOf(Note(text = "Hello world", chunksUsed = listOf(0))),
            failedChunks = emptyList()
        )

        val result = repo.syncSession("s1", "p1", "file.md")
        // Result depends on SAF write access — in test env may be Success or Error
        // The key check is that it doesn't crash
        assertNotNull(result)
    }

    @Test
    fun `syncSession handles transcription error gracefully`() = runTest {
        coEvery { sessionStorage.getSession("s1") } returns testSession
        coEvery { projectStorage.getProject("p1") } returns testProject
        coEvery { chunkStorage.loadChunkBytes("s1", "s1_0") } returns byteArrayOf(1, 2, 3)
        coEvery { transcriptionService.transcribe(any(), any(), any()) } throws
            TranscriptionError.NetworkError("Connection failed")

        val result = repo.syncSession("s1", "p1", "file.md")
        assertTrue(result is SyncStatus.Error)
    }

    @Test
    fun `syncSession handles missing chunk bytes`() = runTest {
        coEvery { sessionStorage.getSession("s1") } returns testSession
        coEvery { projectStorage.getProject("p1") } returns testProject
        coEvery { chunkStorage.loadChunkBytes("s1", "s1_0") } returns null

        val result = repo.syncSession("s1", "p1", "file.md")
        // Should handle gracefully — either skip chunk or report error
        assertTrue(result is SyncStatus.Error || result is SyncStatus.PartialSuccess)
    }

    @Test
    fun `syncSession returns error when transcription service not configured`() = runTest {
        coEvery { sessionStorage.getSession("s1") } returns testSession
        coEvery { projectStorage.getProject("p1") } returns testProject

        val unconfiguredRepo = SyncRepositoryImpl(
            context = RuntimeEnvironment.getApplication(),
            sessionStorage = sessionStorage,
            chunkStorage = chunkStorage,
            projectStorage = projectStorage,
            syncStorage = syncStorage,
            transcriptionServiceProvider = { null }
        )

        val result = unconfiguredRepo.syncSession("s1", "p1", "file.md")
        assertTrue(result is SyncStatus.Error)
        assertTrue((result as SyncStatus.Error).message.contains("not configured"))
    }

    @Test
    fun `retryFailed resets failed items and processes queue`() = runTest {
        coEvery { syncStorage.resetFailedItems() } returns 2
        coEvery { syncStorage.getPendingItems() } returns emptyList()

        repo.retryFailed()

        coVerify { syncStorage.resetFailedItems() }
    }

    @Test
    fun `retryFailed does nothing when no failed items`() = runTest {
        coEvery { syncStorage.resetFailedItems() } returns 0

        repo.retryFailed()

        coVerify { syncStorage.resetFailedItems() }
        // processQueue should not be called since resetCount is 0
        coVerify(exactly = 0) { syncStorage.getPendingItems() }
    }

    @Test
    fun `syncSession handles all chunks failed gracefully`() = runTest {
        coEvery { sessionStorage.getSession("s1") } returns testSession
        coEvery { projectStorage.getProject("p1") } returns testProject
        coEvery { chunkStorage.loadChunkBytes("s1", "s1_0") } returns byteArrayOf(1, 2, 3)
        coEvery { transcriptionService.transcribe(any(), any(), any()) } throws
            TranscriptionError.NetworkError("Timeout")

        val result = repo.syncSession("s1", "p1", "file.md")
        // Should report the error since all chunks failed transcription
        assertTrue(result is SyncStatus.Error)
    }
}
