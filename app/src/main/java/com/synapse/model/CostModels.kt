package com.synapse.model

data class CostEstimate(
    val inputTokens: Int,
    val outputTokens: Int,
    val estimatedCost: Double,
    val model: String
)

data class UsageStats(
    val totalCost: Double,
    val monthlyCost: Double,
    val monthlySyncs: Int
)

data class TokenPricing(
    val input: Double,
    val output: Double
)
