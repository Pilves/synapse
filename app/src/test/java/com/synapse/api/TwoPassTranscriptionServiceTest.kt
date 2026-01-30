package com.synapse.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoPassTranscriptionServiceTest {

    private fun createService(preferTextOnly: Boolean = true): TwoPassTranscriptionService {
        // Use a minimal stub for the delegate since we're testing decision logic only
        val delegate = object : TranscriptionService {
            override suspend fun transcribe(
                chunks: List<ChunkData>,
                cleanupEnabled: Boolean,
                advancedFormatting: Boolean
            ) = TranscriptionResult.empty()

            override fun isConfigured() = true
            override val provider = LlmProvider.GEMINI
            override val modelId = "test"
            override fun setApiKey(apiKey: String?) {}
            override fun setCustomPrompt(template: String?) {}
            override fun getRateLimitState(): RateLimitState? = null
            override fun canMakeRequest() = true
            override fun getWaitTimeMs() = 0L
            override suspend fun textQuery(prompt: String, systemPrompt: String?) = "test response"
        }

        return TwoPassTranscriptionService(delegate, preferTextOnly)
    }

    @Test
    fun `canUseTextOnly returns true when sufficient text context available`() {
        val service = createService(preferTextOnly = true)
        val texts = listOf("This is a detailed paragraph with many words providing sufficient context")
        assertTrue(service.canUseTextOnly(texts))
    }

    @Test
    fun `canUseTextOnly returns false when text is too short`() {
        val service = createService(preferTextOnly = true)
        val texts = listOf("Hi")
        assertFalse(service.canUseTextOnly(texts))
    }

    @Test
    fun `canUseTextOnly returns false when empty`() {
        val service = createService(preferTextOnly = true)
        assertFalse(service.canUseTextOnly(emptyList()))
    }

    @Test
    fun `canUseTextOnly returns false when preferTextOnly is disabled`() {
        val service = createService(preferTextOnly = false)
        val texts = listOf("This is a detailed paragraph with many words")
        assertFalse(service.canUseTextOnly(texts))
    }

    @Test
    fun `canUseTextOnly returns false when text has fewer than 3 words`() {
        val service = createService(preferTextOnly = true)
        val texts = listOf("two words")
        assertFalse(service.canUseTextOnly(texts))
    }

    @Test
    fun `canUseTextOnly returns true with multiple short contexts that combine to sufficient`() {
        val service = createService(preferTextOnly = true)
        val texts = listOf("First context", "Second context", "Third context with more words")
        assertTrue(service.canUseTextOnly(texts))
    }

    @Test
    fun `canUseTextOnly returns false when all contexts are blank`() {
        val service = createService(preferTextOnly = true)
        val texts = listOf("   ", "", " ")
        assertFalse(service.canUseTextOnly(texts))
    }
}
