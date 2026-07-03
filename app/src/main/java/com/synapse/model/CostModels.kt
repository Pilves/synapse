package com.synapse.model

data class CostEstimate(
    val inputTokens: Int,
    val outputTokens: Int,
    val estimatedCost: Double,
    val model: String
)

data class TokenPricing(
    val input: Double,
    val output: Double
)
