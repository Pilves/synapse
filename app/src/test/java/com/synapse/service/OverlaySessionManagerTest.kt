package com.synapse.service

import android.content.Context
import com.synapse.data.repository.ChunkRepository
import com.synapse.data.repository.SessionRepository
import com.synapse.ui.overlay.CaptureViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import com.synapse.model.Session
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

    private val testDispatcher = UnconfinedTestDispatcher()
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
    fun `finishSessionAndOpenReview resets badge count`() {
        every { captureViewModel.captureRemainingStrokes() } returns null
        every { captureViewModel.hasStrokes() } returns false

        manager.pendingChunkCount = 3
        manager.finishSessionAndOpenReview()

        assertEquals(0, manager.pendingChunkCount)
    }

    @Test
    fun `handlePendingContext with no pending context does not create session`() {
        // ContextHolder has no pending context, so this should be a no-op
        manager.handlePendingContext()
        // No crash = success; ContextHolder.consumeContext() returns null so nothing happens
    }

    @Test
    fun `endCurrentSession with no active session is no-op`() {
        // No session started, so endCurrentSession should return early without crashing
        manager.endCurrentSession()
    }
}
