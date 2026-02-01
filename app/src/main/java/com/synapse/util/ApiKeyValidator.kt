package com.synapse.util

import com.synapse.api.LlmProvider

/**
 * Result of API key validation.
 */
data class ApiKeyValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null
)

/**
 * Shared API key validation logic used by both onboarding and settings screens.
 */
object ApiKeyValidator {

    private const val MIN_KEY_LENGTH = 20
    private const val MIN_ANTHROPIC_KEY_LENGTH = 40
    private const val MIN_OPENAI_KEY_LENGTH = 40

    /**
     * Validates an API key format based on common provider patterns.
     * Does not require a specific provider — detects from key prefix.
     */
    fun validateFormat(apiKey: String): ApiKeyValidationResult {
        if (apiKey.length < MIN_KEY_LENGTH) {
            return ApiKeyValidationResult(
                isValid = false,
                errorMessage = "API key is too short (minimum $MIN_KEY_LENGTH characters)"
            )
        }

        if (apiKey.contains(" ")) {
            return ApiKeyValidationResult(
                isValid = false,
                errorMessage = "API key should not contain spaces"
            )
        }

        return when {
            apiKey.startsWith("sk-ant-") -> {
                if (apiKey.length >= MIN_ANTHROPIC_KEY_LENGTH) {
                    ApiKeyValidationResult(isValid = true)
                } else {
                    ApiKeyValidationResult(
                        isValid = false,
                        errorMessage = "Claude API key appears incomplete"
                    )
                }
            }
            apiKey.startsWith("sk-") -> {
                if (apiKey.length >= MIN_OPENAI_KEY_LENGTH) {
                    ApiKeyValidationResult(isValid = true)
                } else {
                    ApiKeyValidationResult(
                        isValid = false,
                        errorMessage = "OpenAI API key appears incomplete"
                    )
                }
            }
            apiKey.startsWith("AIza") -> {
                ApiKeyValidationResult(isValid = true)
            }
            else -> {
                ApiKeyValidationResult(
                    isValid = false,
                    errorMessage = "Unrecognized API key format. Supported providers: Gemini (AIza…), Claude (sk-ant-…), OpenAI (sk-…)"
                )
            }
        }
    }

    /**
     * Validates an API key for a specific provider.
     * Used by the settings screen where the provider is already known.
     */
    fun validateForProvider(apiKey: String, provider: LlmProvider): ApiKeyValidationResult {
        if (provider == LlmProvider.OLLAMA) {
            return ApiKeyValidationResult(isValid = true)
        }

        if (apiKey.isBlank()) {
            return ApiKeyValidationResult(
                isValid = false,
                errorMessage = "API key is required for ${provider.displayName}"
            )
        }

        val isValid = when (provider) {
            LlmProvider.GEMINI -> apiKey.length >= MIN_KEY_LENGTH
            LlmProvider.CLAUDE -> apiKey.startsWith("sk-ant-") && apiKey.length >= MIN_ANTHROPIC_KEY_LENGTH
            LlmProvider.OPENAI -> apiKey.startsWith("sk-") && apiKey.length >= MIN_OPENAI_KEY_LENGTH
            LlmProvider.OLLAMA -> true
        }

        return if (isValid) {
            ApiKeyValidationResult(isValid = true)
        } else {
            ApiKeyValidationResult(
                isValid = false,
                errorMessage = "Invalid API key format for ${provider.displayName}"
            )
        }
    }

    /**
     * Detects the LLM provider from an API key prefix.
     */
    fun detectProvider(apiKey: String): String {
        val trimmed = apiKey.trim()
        return when {
            trimmed.startsWith("AIza") -> "gemini"
            trimmed.startsWith("sk-ant-") -> "claude"
            trimmed.startsWith("sk-") -> "openai"
            else -> "gemini"
        }
    }
}
