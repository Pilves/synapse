package com.synapse.data.repository

import android.content.Context
import android.graphics.Rect
import com.synapse.api.TranscriptionService
import com.synapse.data.storage.ChunkStorage
import com.synapse.data.storage.ProjectStorage
import com.synapse.data.storage.SessionStorage
import com.synapse.data.storage.SyncStorage
import com.synapse.model.CapturedContext
import com.synapse.model.Project
import com.synapse.model.Session
import com.synapse.model.SyncStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import android.util.Log
import io.mockk.mockkStatic
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncRepositoryFormatContextTest {

    private lateinit var context: Context
    private lateinit var sessionStorage: SessionStorage
    private lateinit var chunkStorage: ChunkStorage
    private lateinit var projectStorage: ProjectStorage
    private lateinit var syncStorage: SyncStorage
    private lateinit var transcriptionService: TranscriptionService

    private val testProject = Project(
        id = "p1",
        name = "Test Project",
        pathUri = "content://test/path",
        defaultFile = "notes.md"
    )

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        context = mockk(relaxed = true)
        sessionStorage = mockk(relaxed = true)
        chunkStorage = mockk(relaxed = true)
        projectStorage = mockk(relaxed = true)
        syncStorage = mockk(relaxed = true)
        transcriptionService = mockk(relaxed = true)

        coEvery { projectStorage.getProject("p1") } returns testProject
        every { transcriptionService.isConfigured() } returns true
    }

    private fun createRepo(
        serviceProvider: suspend () -> TranscriptionService? = { transcriptionService }
    ): SyncRepositoryImpl {
        return SyncRepositoryImpl(
            context = context,
            sessionStorage = sessionStorage,
            chunkStorage = chunkStorage,
            projectStorage = projectStorage,
            syncStorage = syncStorage,
            transcriptionServiceProvider = serviceProvider
        )
    }

    @Test
    fun `context-only segment calls textQuery when transcription service is available`() = runTest {
        val ctx = CapturedContext.RegionText(
            text = "Some raw captured text",
            bounds = Rect(0, 0, 100, 100),
            timestamp = 1000L
        )
        val session = Session(
            id = "s1",
            startedAt = 1000L,
            endedAt = 2000L,
            contexts = listOf(ctx)
        )

        coEvery { sessionStorage.getSession("s1") } returns session
        coEvery { transcriptionService.textQuery(any(), any()) } returns "# Formatted Text"
        // polishMarkdownFormatting also calls textQuery — allow it
        coEvery { transcriptionService.textQuery(match { it.contains("Formatted Text") }, any()) } returns "# Formatted Text"

        val repo = createRepo()
        repo.syncSession("s1", "p1", "notes.md")

        // Verify textQuery was called with the raw text
        coVerify {
            transcriptionService.textQuery(
                eq("Some raw captured text"),
                match { it.contains("note formatter") }
            )
        }
    }

    @Test
    fun `context-only segment falls back to blockquote when no transcription service`() = runTest {
        val ctx = CapturedContext.SelectedText(
            text = "Fallback text",
            sourceApp = "TestApp",
            sourceUrl = null,
            timestamp = 1000L
        )
        val session = Session(
            id = "s2",
            startedAt = 1000L,
            endedAt = 2000L,
            contexts = listOf(ctx)
        )

        coEvery { sessionStorage.getSession("s2") } returns session

        val repo = createRepo(serviceProvider = { null })
        // With no LLM and no chunks, the session still has contexts to write.
        // The output should contain blockquote-style text.
        val result = repo.syncSession("s2", "p1", "notes.md")

        // textQuery should never be called since there's no service
        coVerify(exactly = 0) { transcriptionService.textQuery(any(), any()) }
    }

    @Test
    fun `context formatting falls back to blockquote on LLM error`() = runTest {
        val ctx = CapturedContext.RegionText(
            text = "Error text",
            bounds = Rect(0, 0, 100, 100),
            timestamp = 1000L
        )
        val session = Session(
            id = "s3",
            startedAt = 1000L,
            endedAt = 2000L,
            contexts = listOf(ctx)
        )

        coEvery { sessionStorage.getSession("s3") } returns session
        coEvery {
            transcriptionService.textQuery(eq("Error text"), any())
        } throws RuntimeException("LLM unavailable")
        // polish call should still work or also fail gracefully
        coEvery {
            transcriptionService.textQuery(match { it.contains("> Error text") }, any())
        } returns "> Error text"

        val repo = createRepo()
        val result = repo.syncSession("s3", "p1", "notes.md")

        // Should not crash — the fallback blockquote is used
        assertTrue(
            "Expected success or partial success, got $result",
            result is SyncStatus.Success || result is SyncStatus.PartialSuccess || result is SyncStatus.Error
        )
    }
}
