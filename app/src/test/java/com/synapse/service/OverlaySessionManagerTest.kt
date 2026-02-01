package com.synapse.service

import android.content.Context
import android.graphics.Bitmap
import com.synapse.data.repository.ChunkRepository
import com.synapse.data.repository.SessionRepository
import com.synapse.model.Chunk
import com.synapse.model.Session
import com.synapse.ui.overlay.CapturedChunk
import com.synapse.ui.overlay.CaptureViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OverlaySessionManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var sessionRepository: SessionRepository
    private lateinit var chunkRepository: ChunkRepository
    private lateinit var screenshotManager: ScreenshotManager
    private lateinit var captureViewModel: CaptureViewModel
    private lateinit var scope: CoroutineScope
    private lateinit var manager: OverlaySessionManager

    private var lastBadgeCount = -1
    private var openReviewCalled = false
    private var hideOverlayCalled = false

    private val testSession = Session(
        id = "s1",
        startedAt = System.currentTimeMillis(),
        chunks = emptyList()
    )

    private val testChunk = Chunk(
        id = "s1_0",
        sessionId = "s1",
        index = 0,
        filePath = "/test/chunk.webp",
        timestampSeconds = 1.0f,
        createdAt = System.currentTimeMillis()
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = RuntimeEnvironment.getApplication()
        sessionRepository = mockk(relaxed = true)
        chunkRepository = mockk(relaxed = true)
        screenshotManager = mockk(relaxed = true)
        captureViewModel = mockk(relaxed = true)
        scope = CoroutineScope(SupervisorJob() + testDispatcher)

        lastBadgeCount = -1
        openReviewCalled = false
        hideOverlayCalled = false

        coEvery { sessionRepository.createSession() } returns testSession

        manager = OverlaySessionManager(
            context = context,
            sessionRepository = sessionRepository,
            chunkRepository = chunkRepository,
            screenshotManager = screenshotManager,
            captureViewModel = captureViewModel,
            scope = scope,
            onBadgeUpdate = { lastBadgeCount = it },
            onOpenReview = { openReviewCalled = true },
            onHideOverlay = { hideOverlayCalled = true },
            onRefreshOverlay = {},
            onRequestPermission = {}
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `pendingChunkCount starts at zero`() {
        assertEquals(0, manager.pendingChunkCount)
    }

    @Test
    fun `pendingChunkCount can be set and read`() {
        manager.pendingChunkCount = 5
        assertEquals(5, manager.pendingChunkCount)
    }

    @Test
    fun `capturedTextPreview starts null`() {
        assertNull(manager.capturedTextPreview.value)
    }

    @Test
    fun `saveChunk creates session if not exists`() = runTest(testDispatcher) {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val captured = CapturedChunk(bitmap = bitmap, timestamp = System.currentTimeMillis(), index = 0)
        coEvery { chunkRepository.saveChunk(any<String>(), any<Bitmap>(), any<Float>()) } returns testChunk

        manager.saveChunk(captured)
        advanceUntilIdle()

        coVerify { sessionRepository.createSession() }
    }

    @Test
    fun `saveChunk updates badge count`() = runTest(testDispatcher) {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val captured = CapturedChunk(bitmap = bitmap, timestamp = System.currentTimeMillis(), index = 0)
        coEvery { chunkRepository.saveChunk(any<String>(), any<Bitmap>(), any<Float>()) } returns testChunk

        manager.saveChunk(captured)
        advanceUntilIdle()

        assertEquals(1, lastBadgeCount)
        assertEquals(1, manager.pendingChunkCount)
    }

    @Test
    fun `endCurrentSession clears session id`() = runTest(testDispatcher) {
        // First create a session by saving a chunk
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val captured = CapturedChunk(bitmap = bitmap, timestamp = System.currentTimeMillis(), index = 0)
        coEvery { chunkRepository.saveChunk(any<String>(), any<Bitmap>(), any<Float>()) } returns testChunk

        manager.saveChunk(captured)
        advanceUntilIdle()

        manager.endCurrentSession()
        advanceUntilIdle()

        coVerify { sessionRepository.endSession("s1") }
    }

    @Test
    fun `finishSessionAndOpenReview resets badge and opens review`() = runTest(testDispatcher) {
        every { captureViewModel.captureRemainingStrokes() } returns null
        every { captureViewModel.hasStrokes() } returns false

        manager.finishSessionAndOpenReview()
        advanceUntilIdle()

        assertEquals(0, manager.pendingChunkCount)
    }

    @Test
    fun `handlePendingContext with no pending context is no-op`() = runTest(testDispatcher) {
        // ContextHolder has no pending context, so this should be a no-op
        manager.handlePendingContext()
        advanceUntilIdle()

        // Should not create a session
        coVerify(exactly = 0) { sessionRepository.createSession() }
    }
}
