package com.synapse.data.cost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmCostCalculatorTest {

    // --- estimateCost ---

    @Test
    fun `estimateCost returns zero cost for free gemini models`() {
        val estimate = LlmCostCalculator.estimateCost(
            chunkSizes = listOf(50 * 1024),
            contextCount = 1,
            model = "gemini-2.0-flash"
        )
        assertEquals(0.0, estimate.estimatedCost, 0.001)
        assertTrue(estimate.inputTokens > 0)
        assertTrue(estimate.outputTokens > 0)
        assertEquals("gemini-2.0-flash", estimate.model)
    }

    @Test
    fun `estimateCost returns nonzero cost for paid models`() {
        val estimate = LlmCostCalculator.estimateCost(
            chunkSizes = listOf(50 * 1024),
            contextCount = 1,
            model = "claude-3-sonnet"
        )
        assertTrue(estimate.estimatedCost > 0.0)
    }

    @Test
    fun `estimateCost returns zero for unknown model`() {
        val estimate = LlmCostCalculator.estimateCost(
            chunkSizes = listOf(50 * 1024),
            model = "unknown-model"
        )
        assertEquals(0, estimate.inputTokens)
        assertEquals(0, estimate.outputTokens)
        assertEquals(0.0, estimate.estimatedCost, 0.001)
    }

    @Test
    fun `estimateCost scales with number of chunks`() {
        val oneChunk = LlmCostCalculator.estimateCost(
            chunkSizes = listOf(50 * 1024),
            model = "gpt-4o"
        )
        val twoChunks = LlmCostCalculator.estimateCost(
            chunkSizes = listOf(50 * 1024, 50 * 1024),
            model = "gpt-4o"
        )
        assertTrue(twoChunks.estimatedCost > oneChunk.estimatedCost)
        assertTrue(twoChunks.inputTokens > oneChunk.inputTokens)
        assertEquals(oneChunk.outputTokens * 2, twoChunks.outputTokens)
    }

    @Test
    fun `estimateCost includes context tokens`() {
        val noContext = LlmCostCalculator.estimateCost(
            chunkSizes = listOf(50 * 1024),
            contextCount = 0,
            model = "gpt-4o-mini"
        )
        val withContext = LlmCostCalculator.estimateCost(
            chunkSizes = listOf(50 * 1024),
            contextCount = 5,
            model = "gpt-4o-mini"
        )
        assertTrue(withContext.inputTokens > noContext.inputTokens)
        assertEquals(500, withContext.inputTokens - noContext.inputTokens) // 5 * 100 tokens
    }

    @Test
    fun `estimateCost handles empty chunk list`() {
        val estimate = LlmCostCalculator.estimateCost(
            chunkSizes = emptyList(),
            model = "gpt-4o"
        )
        assertEquals(0, estimate.outputTokens)
        // Still has prompt tokens (500) in input
        assertTrue(estimate.inputTokens > 0)
    }

    // --- getModelDisplayName ---

    @Test
    fun `getModelDisplayName maps gemini correctly`() {
        assertEquals("Gemini", LlmCostCalculator.getModelDisplayName("gemini-2.0-flash"))
    }

    @Test
    fun `getModelDisplayName maps claude correctly`() {
        assertEquals("Claude", LlmCostCalculator.getModelDisplayName("claude-3-sonnet"))
    }

    @Test
    fun `getModelDisplayName maps openai correctly`() {
        assertEquals("OpenAI", LlmCostCalculator.getModelDisplayName("gpt-4o-mini"))
    }

    @Test
    fun `getModelDisplayName maps ollama correctly`() {
        assertEquals("Ollama (Free)", LlmCostCalculator.getModelDisplayName("ollama-llava"))
    }

    @Test
    fun `getModelDisplayName returns raw name for unknown`() {
        assertEquals("custom-model", LlmCostCalculator.getModelDisplayName("custom-model"))
    }

    // --- isFreeModel ---

    @Test
    fun `isFreeModel returns true for gemini flash`() {
        assertTrue(LlmCostCalculator.isFreeModel("gemini-2.0-flash"))
        assertTrue(LlmCostCalculator.isFreeModel("gemini-2.5-flash"))
    }

    @Test
    fun `isFreeModel returns true for ollama`() {
        assertTrue(LlmCostCalculator.isFreeModel("ollama"))
        assertTrue(LlmCostCalculator.isFreeModel("llava-13b"))
    }

    @Test
    fun `isFreeModel returns true for unknown model not in pricing`() {
        assertTrue(LlmCostCalculator.isFreeModel("unknown-model"))
    }

    @Test
    fun `isFreeModel returns false for paid models`() {
        assertEquals(false, LlmCostCalculator.isFreeModel("gpt-4o"))
        assertEquals(false, LlmCostCalculator.isFreeModel("claude-3-sonnet"))
        assertEquals(false, LlmCostCalculator.isFreeModel("claude-3-haiku"))
    }
}
