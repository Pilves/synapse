package com.synapse.api

import com.synapse.model.CapturedContext
import com.synapse.model.LlmConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QuestionAnswerServiceTest {

    private lateinit var factory: DefaultTranscriptionServiceFactory
    private lateinit var transcriptionService: TranscriptionService
    private lateinit var service: QuestionAnswerService
    private lateinit var tempDir: File

    private val testConfig = LlmConfig(
        transcriptionProvider = "gemini",
        transcriptionApiKey = "test-key",
        answeringProvider = "gemini",
        answeringApiKey = "test-key"
    )

    @Before
    fun setup() {
        factory = mockk(relaxed = true)
        transcriptionService = mockk(relaxed = true)
        every { factory.getAnsweringService(any()) } returns transcriptionService
        every { transcriptionService.provider } returns LlmProvider.GEMINI
        every { transcriptionService.modelId } returns "gemini-2.0-flash"

        tempDir = File(System.getProperty("java.io.tmpdir"), "qa_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        service = QuestionAnswerService(factory, tempDir)
    }

    @Test
    fun `answerQuestion calls textQuery for text-only question`() = runTest {
        coEvery { transcriptionService.textQuery(any(), any()) } returns "Test answer"

        val result = service.answerQuestion("What is this?", testConfig)
        assertEquals("Test answer", result)

        coVerify { transcriptionService.textQuery(any(), any()) }
    }

    @Test
    fun `answerQuestion includes context text in prompt`() = runTest {
        coEvery { transcriptionService.textQuery(any(), any()) } returns "Answer with context"

        val contexts = listOf(
            CapturedContext.SelectedText(text = "important text")
        )
        val result = service.answerQuestion("summarize", testConfig, contexts)
        assertEquals("Answer with context", result)
    }

    @Test
    fun `answerQuestion uses visionQuery when images provided`() = runTest {
        coEvery { transcriptionService.visionQuery(any(), any(), any()) } returns "Vision answer"

        val result = service.answerQuestion(
            "describe",
            testConfig,
            additionalImages = listOf(byteArrayOf(1, 2, 3))
        )
        assertEquals("Vision answer", result)

        coVerify { transcriptionService.visionQuery(any(), any(), any()) }
    }

    @Test
    fun `answerQuestion loads region images from disk`() = runTest {
        val imageFile = File(tempDir, "screenshot.png")
        imageFile.writeBytes(ByteArray(2048) { 0xFF.toByte() })

        coEvery { transcriptionService.visionQuery(any(), any(), any()) } returns "Image answer"

        val contexts = listOf(
            CapturedContext.RegionImage(
                imagePath = imageFile.absolutePath,
                description = "screenshot"
            )
        )
        val result = service.answerQuestion("describe", testConfig, contexts)
        assertEquals("Image answer", result)
    }

    @Test
    fun `answerQuestion skips images outside app files dir`() = runTest {
        val outsideFile = File("/tmp/outside/screenshot.png")
        coEvery { transcriptionService.textQuery(any(), any()) } returns "Text answer"

        val contexts = listOf(
            CapturedContext.RegionImage(
                imagePath = outsideFile.absolutePath,
                description = "screenshot"
            )
        )
        val result = service.answerQuestion("describe", testConfig, contexts)

        // Should fall back to textQuery since image was outside allowed dir
        coVerify { transcriptionService.textQuery(any(), any()) }
    }

    @Test
    fun `answerQuestion skips oversized images`() = runTest {
        val bigFile = File(tempDir, "big.png")
        // Create a file larger than 10MB limit
        bigFile.writeBytes(ByteArray(11 * 1024 * 1024))

        coEvery { transcriptionService.textQuery(any(), any()) } returns "No image"

        val contexts = listOf(
            CapturedContext.RegionImage(
                imagePath = bigFile.absolutePath,
                description = "huge"
            )
        )
        service.answerQuestion("describe", testConfig, contexts)

        // Should fall back to textQuery since image was too large
        coVerify { transcriptionService.textQuery(any(), any()) }
    }

    @Test(expected = TranscriptionError.ApiKeyInvalid::class)
    fun `answerQuestion propagates TranscriptionError`() = runTest {
        coEvery { transcriptionService.textQuery(any(), any()) } throws
            TranscriptionError.ApiKeyInvalid("bad key")

        service.answerQuestion("test", testConfig)
    }
}
