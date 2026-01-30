package com.synapse.data.cost

import com.synapse.model.CostEstimate
import com.synapse.model.TokenPricing

object LlmCostCalculator {

    private val pricing = mapOf(
        "gemini-2.5-flash" to TokenPricing(input = 0.0, output = 0.0),
        "gemini-2.0-flash" to TokenPricing(input = 0.0, output = 0.0),
        "gemini-1.5-pro" to TokenPricing(input = 3.50, output = 10.50),
        "claude-3-haiku" to TokenPricing(input = 0.25, output = 1.25),
        "claude-3-5-haiku" to TokenPricing(input = 0.25, output = 1.25),
        "claude-3-sonnet" to TokenPricing(input = 3.00, output = 15.00),
        "gpt-4o-mini" to TokenPricing(input = 0.15, output = 0.60),
        "gpt-4o" to TokenPricing(input = 5.00, output = 15.00)
    )

    private const val TOKENS_PER_KB_IMAGE = 85
    private const val OUTPUT_TOKENS_PER_CHUNK = 150

    fun estimateCost(
        chunkSizes: List<Int>,
        contextCount: Int = 0,
        model: String
    ): CostEstimate {
        val price = pricing[model]
            ?: pricing.entries.firstOrNull { model.startsWith(it.key) }?.value
            ?: return CostEstimate(0, 0, 0.0, model)

        val imageBytes = chunkSizes.sum()
        val imageTokens = (imageBytes / 1024) * TOKENS_PER_KB_IMAGE
        val contextTokens = contextCount * 100
        val promptTokens = 500

        val totalInputTokens = imageTokens + contextTokens + promptTokens
        val outputTokens = chunkSizes.size * OUTPUT_TOKENS_PER_CHUNK

        val inputCost = (totalInputTokens / 1_000_000.0) * price.input
        val outputCost = (outputTokens / 1_000_000.0) * price.output
        val totalCost = inputCost + outputCost

        return CostEstimate(
            inputTokens = totalInputTokens,
            outputTokens = outputTokens,
            estimatedCost = totalCost,
            model = model
        )
    }

    fun getModelDisplayName(model: String): String {
        return when {
            model.contains("gemini") -> "Gemini"
            model.contains("claude") -> "Claude"
            model.contains("gpt") -> "OpenAI"
            model.contains("ollama") || model.contains("llava") -> "Ollama (Free)"
            else -> model
        }
    }

    fun isFreeModel(model: String): Boolean {
        if (model.contains("ollama") || model.contains("llava")) return true
        val price = pricing[model] ?: return true
        return price.input == 0.0 && price.output == 0.0
    }
}
