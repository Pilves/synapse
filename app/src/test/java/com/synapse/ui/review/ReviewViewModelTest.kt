package com.synapse.ui.review

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.synapse.data.repository.ProjectRepository
import com.synapse.data.repository.SessionRepository
import com.synapse.data.repository.SyncRepository
import com.synapse.model.CapturedContext
import com.synapse.model.Chunk
import com.synapse.model.Project
import com.synapse.model.Session
import com.synapse.model.SyncStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {

    @get:Rule
    val instantRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var sessionRepository: SessionRepository
    private lateinit var projectRepository: ProjectRepository
    private lateinit var syncRepository: SyncRepository

    private fun createChunk(id: String, sessionId: String, index: Int = 0) = Chunk(
        id = id,
        sessionId = sessionId,
        index = index,
        filePath = "/path/$id.png",
        timestampSeconds = 100f + index,
        createdAt = System.currentTimeMillis()
    )

    private fun createSession(
        id: String,
        ended: Boolean = true,
        chunks: List<Chunk> = emptyList(),
        contexts: List<CapturedContext> = emptyList()
    ) = Session(
        id = id,
        startedAt = System.currentTimeMillis() - 60000,
        endedAt = if (ended) System.currentTimeMillis() else null,
        chunks = chunks,
        contexts = contexts
    )

    private fun createProject(id: String = "proj-1", name: String = "Test Project") = Project(
        id = id,
        name = name,
        pathUri = "content://vault/$id",
        defaultFile = "notes.md"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        sessionRepository = mockk(relaxed = true)
        projectRepository = mockk(relaxed = true)
        syncRepository = mockk(relaxed = true)

        every { sessionRepository.observeSessions() } returns flowOf(emptyList())
        every { projectRepository.observeProjects() } returns flowOf(emptyList())
        every { syncRepository.observeSyncStatus() } returns flowOf(SyncStatus.Idle)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = ReviewViewModel(
        sessionRepository = sessionRepository,
        projectRepository = projectRepository,
        syncRepository = syncRepository
    )

    // --- Init / Loading ---

    @Test
    fun `init observes sessions and sets loading false when done`() = runTest {
        val chunks = listOf(createChunk("c1", "s1"))
        val sessions = listOf(createSession("s1", ended = true, chunks = chunks))
        every { sessionRepository.observeSessions() } returns flowOf(sessions)

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.sessions.size)
        assertEquals("s1", state.sessions[0].id)
    }

    @Test
    fun `init filters to only ended sessions`() = runTest {
        val ended = createSession("s1", ended = true)
        val active = createSession("s2", ended = false)
        every { sessionRepository.observeSessions() } returns flowOf(listOf(ended, active))

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.sessions.size)
        assertEquals("s1", vm.uiState.value.sessions[0].id)
    }

    @Test
    fun `init loads projects and selects first`() = runTest {
        val projects = listOf(createProject("p1", "First"), createProject("p2", "Second"))
        every { projectRepository.observeProjects() } returns flowOf(projects)

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.projects.size)
        assertEquals("p1", vm.uiState.value.selectedProject?.id)
    }

    // --- View Mode ---

    @Test
    fun `toggleViewMode switches between stitched and separate`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(ViewMode.STITCHED, vm.uiState.value.viewMode)

        vm.toggleViewMode()
        assertEquals(ViewMode.SEPARATE, vm.uiState.value.viewMode)

        vm.toggleViewMode()
        assertEquals(ViewMode.STITCHED, vm.uiState.value.viewMode)
    }

    @Test
    fun `switching to stitched mode clears chunk selections`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setViewMode(ViewMode.SEPARATE)
        vm.toggleChunkSelection("c1")
        assertEquals(setOf("c1"), vm.uiState.value.selectedChunkIds)

        vm.setViewMode(ViewMode.STITCHED)
        assertTrue(vm.uiState.value.selectedChunkIds.isEmpty())
    }

    // --- Chunk Selection ---

    @Test
    fun `toggleChunkSelection adds and removes chunks`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleChunkSelection("c1")
        assertTrue("c1" in vm.uiState.value.selectedChunkIds)

        vm.toggleChunkSelection("c1")
        assertFalse("c1" in vm.uiState.value.selectedChunkIds)
    }

    @Test
    fun `selectAllChunksInSession selects all chunks of a session`() = runTest {
        val chunks = listOf(createChunk("c1", "s1"), createChunk("c2", "s1", 1))
        val session = createSession("s1", chunks = chunks)
        every { sessionRepository.observeSessions() } returns flowOf(listOf(session))

        val vm = createViewModel()
        advanceUntilIdle()

        vm.selectAllChunksInSession(session)
        assertEquals(setOf("c1", "c2"), vm.uiState.value.selectedChunkIds)
    }

    @Test
    fun `deselectAllChunksInSession removes session chunks from selection`() = runTest {
        val chunks = listOf(createChunk("c1", "s1"), createChunk("c2", "s1", 1))
        val session = createSession("s1", chunks = chunks)
        every { sessionRepository.observeSessions() } returns flowOf(listOf(session))

        val vm = createViewModel()
        advanceUntilIdle()

        vm.selectAllChunksInSession(session)
        vm.toggleChunkSelection("c3") // from another session
        vm.deselectAllChunksInSession(session)

        assertEquals(setOf("c3"), vm.uiState.value.selectedChunkIds)
    }

    @Test
    fun `selectAllChunks selects chunks across all sessions`() = runTest {
        val s1 = createSession("s1", chunks = listOf(createChunk("c1", "s1")))
        val s2 = createSession("s2", chunks = listOf(createChunk("c2", "s2")))
        every { sessionRepository.observeSessions() } returns flowOf(listOf(s1, s2))

        val vm = createViewModel()
        advanceUntilIdle()

        vm.selectAllChunks()
        assertEquals(setOf("c1", "c2"), vm.uiState.value.selectedChunkIds)
    }

    @Test
    fun `clearSelection empties selected chunk ids`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleChunkSelection("c1")
        vm.clearSelection()
        assertTrue(vm.uiState.value.selectedChunkIds.isEmpty())
    }

    // --- Project & Filename ---

    @Test
    fun `selectProject updates selected project`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val project = createProject("p1")
        vm.selectProject(project)
        assertEquals("p1", vm.uiState.value.selectedProject?.id)
    }

    @Test
    fun `updateFilename updates filename`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.updateFilename("my-notes.md")
        assertEquals("my-notes.md", vm.uiState.value.filename)
    }

    // --- Delete Operations ---

    @Test
    fun `deleteChunk removes chunk from session and updates state`() = runTest {
        val chunk = createChunk("c1", "s1")
        val chunk2 = createChunk("c2", "s1", 1)
        val session = createSession("s1", chunks = listOf(chunk, chunk2))
        every { sessionRepository.observeSessions() } returns flowOf(listOf(session))

        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleChunkSelection("c1")
        vm.deleteChunk(chunk)
        advanceUntilIdle()

        coVerify { sessionRepository.deleteChunk("s1", "c1") }
        val state = vm.uiState.value
        assertEquals(1, state.sessions[0].chunks.size)
        assertFalse("c1" in state.selectedChunkIds)
    }

    @Test
    fun `deleteChunk removes session when last chunk deleted`() = runTest {
        val chunk = createChunk("c1", "s1")
        val session = createSession("s1", chunks = listOf(chunk))
        every { sessionRepository.observeSessions() } returns flowOf(listOf(session))

        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteChunk(chunk)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.sessions.isEmpty())
    }

    @Test
    fun `deleteChunk sets error on failure`() = runTest {
        val chunk = createChunk("c1", "s1")
        val session = createSession("s1", chunks = listOf(chunk))
        every { sessionRepository.observeSessions() } returns flowOf(listOf(session))
        coEvery { sessionRepository.deleteChunk(any(), any()) } throws RuntimeException("IO error")

        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteChunk(chunk)
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.error)
        assertTrue(vm.uiState.value.error!!.contains("IO error"))
    }

    @Test
    fun `deleteSession removes session and clears its chunk selections`() = runTest {
        val chunks = listOf(createChunk("c1", "s1"), createChunk("c2", "s1", 1))
        val session = createSession("s1", chunks = chunks)
        every { sessionRepository.observeSessions() } returns flowOf(listOf(session))

        val vm = createViewModel()
        advanceUntilIdle()

        vm.selectAllChunksInSession(session)
        vm.deleteSession(session)
        advanceUntilIdle()

        coVerify { sessionRepository.deleteSession("s1") }
        assertTrue(vm.uiState.value.sessions.isEmpty())
        assertTrue(vm.uiState.value.selectedChunkIds.isEmpty())
    }

    // --- Sync ---

    @Test
    fun `syncAll errors when no project selected`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.syncAll()
        advanceUntilIdle()

        assertEquals("Please select a project", vm.uiState.value.error)
    }

    @Test
    fun `syncAll errors when filename is blank`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.selectProject(createProject())
        vm.updateFilename("")
        vm.syncAll()
        advanceUntilIdle()

        assertEquals("Please enter a valid filename", vm.uiState.value.error)
    }

    @Test
    fun `syncAll errors when no sessions available`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.selectProject(createProject())
        vm.updateFilename("notes.md")
        vm.syncAll()
        advanceUntilIdle()

        assertEquals("No sessions to sync", vm.uiState.value.error)
    }

    @Test
    fun `syncAll sets success status on successful sync`() = runTest {
        val session = createSession("s1", chunks = listOf(createChunk("c1", "s1")))
        every { sessionRepository.observeSessions() } returns flowOf(listOf(session))
        coEvery { syncRepository.syncSession(any(), any(), any()) } returns SyncStatus.Success

        val vm = createViewModel()
        advanceUntilIdle()

        vm.selectProject(createProject())
        vm.syncAll()
        advanceUntilIdle()

        assertEquals(SyncStatus.Success, vm.uiState.value.syncStatus)
        coVerify { sessionRepository.deleteSession("s1") }
    }

    @Test
    fun `syncAll sets error status when all sessions fail`() = runTest {
        val session = createSession("s1", chunks = listOf(createChunk("c1", "s1")))
        every { sessionRepository.observeSessions() } returns flowOf(listOf(session))
        coEvery { syncRepository.syncSession(any(), any(), any()) } returns SyncStatus.Error("LLM failed")

        val vm = createViewModel()
        advanceUntilIdle()

        vm.selectProject(createProject())
        vm.syncAll()
        advanceUntilIdle()

        val status = vm.uiState.value.syncStatus
        assertTrue(status is SyncStatus.Error)
        assertEquals("LLM failed", (status as SyncStatus.Error).message)
    }

    @Test
    fun `syncAll keeps session on partial success for retry`() = runTest {
        val session = createSession("s1", chunks = listOf(createChunk("c1", "s1")))
        every { sessionRepository.observeSessions() } returns flowOf(listOf(session))
        coEvery { syncRepository.syncSession(any(), any(), any()) } returns SyncStatus.PartialSuccess(1, 1)

        val vm = createViewModel()
        advanceUntilIdle()

        vm.selectProject(createProject())
        vm.syncAll()
        advanceUntilIdle()

        // Session should NOT be deleted on partial success — kept for retry
        coVerify(exactly = 0) { sessionRepository.deleteSession("s1") }
        val status = vm.uiState.value.syncStatus
        assertTrue(status is SyncStatus.PartialSuccess)
    }

    // --- Misc ---

    @Test
    fun `resetSyncStatus sets idle`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.resetSyncStatus()
        assertEquals(SyncStatus.Idle, vm.uiState.value.syncStatus)
    }

    @Test
    fun `clearError sets error to null`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        // Trigger an error first
        vm.syncAll()
        advanceUntilIdle()

        vm.clearError()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `setPreviewChunk updates preview`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val chunk = createChunk("c1", "s1")
        vm.setPreviewChunk(chunk)
        assertEquals("c1", vm.uiState.value.previewChunk?.id)

        vm.setPreviewChunk(null)
        assertNull(vm.uiState.value.previewChunk)
    }

    @Test
    fun `addContext appends to contexts list`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val ctx = CapturedContext.SelectedText(
            text = "test",
            sourceApp = null,
            sourceUrl = null
        )
        vm.addContext(ctx)
        assertEquals(1, vm.uiState.value.contexts.size)
    }

    @Test
    fun `removeContext removes by id`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val ctx = CapturedContext.SelectedText(
            id = "ctx-1",
            text = "test",
            sourceApp = null,
            sourceUrl = null
        )
        vm.addContext(ctx)
        vm.removeContext("ctx-1")
        assertTrue(vm.uiState.value.contexts.isEmpty())
    }

    @Test
    fun `getChunkOffset calculates relative time`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val session = Session(
            id = "s1",
            startedAt = 10000L, // 10 seconds in ms
            endedAt = 20000L
        )
        val chunk = Chunk(
            id = "c1",
            sessionId = "s1",
            index = 0,
            filePath = "/path/c1.png",
            timestampSeconds = 15f,
            createdAt = 15000L
        )

        val offset = vm.getChunkOffset(session, chunk)
        assertEquals(5f, offset, 0.01f) // 15 - (10000/1000) = 5
    }

    @Test
    fun `updateQueueSummary updates counts`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.updateQueueSummary(pendingCount = 3, queuedCount = 1, failedCount = 2)
        val state = vm.uiState.value
        assertEquals(3, state.pendingSyncCount)
        assertEquals(1, state.queuedSyncCount)
        assertEquals(2, state.failedSyncCount)
    }
}
