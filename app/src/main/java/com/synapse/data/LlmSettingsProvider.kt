package com.synapse.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.synapse.api.LlmProvider
import com.synapse.data.storage.SecureKeyStorage
import com.synapse.model.LlmConfig
import kotlinx.coroutines.flow.first

/**
 * Reads LLM-related settings from DataStore and SecureKeyStorage.
 *
 * Provider selection and non-secret settings come from DataStore.
 * API keys are read from EncryptedSharedPreferences via SecureKeyStorage.
 */
class LlmSettingsProvider(
    private val dataStore: DataStore<Preferences>,
    private val secureKeyStorage: SecureKeyStorage
) {

    private val transcriptionProviderKey = stringPreferencesKey("transcription_provider")
    private val answeringProviderKey = stringPreferencesKey("answering_provider")
    private val rateLimitingSafeKey = booleanPreferencesKey("rate_limiting_safe")
    // Legacy fallback keys
    private val legacyProviderKey = stringPreferencesKey("llm_provider")

    /**
     * Reads the current transcription provider, API key, and rate-limiting flag.
     * API keys are read from encrypted storage with legacy DataStore fallback.
     */
    suspend fun readLlmSettings(): Triple<LlmProvider, String?, Boolean> {
        val prefs = dataStore.data.first()
        val providerName = prefs[transcriptionProviderKey]
            ?: prefs[legacyProviderKey]
            ?: LlmProvider.GEMINI.name
        val provider = LlmProvider.fromName(providerName) ?: LlmProvider.GEMINI
        val rateLimitingSafe = prefs[rateLimitingSafeKey] ?: true

        // Read API key from encrypted storage
        val apiKey = secureKeyStorage.getKey("transcription")
            ?: secureKeyStorage.getKey("legacy")

        return Triple(provider, apiKey, rateLimitingSafe)
    }

    /**
     * Reads the full LLM configuration including answering provider settings.
     * Returns null when no API key is configured.
     */
    suspend fun readFullLlmConfig(): LlmConfig? {
        val prefs = dataStore.data.first()
        val providerName = prefs[transcriptionProviderKey]
            ?: prefs[legacyProviderKey]
            ?: LlmProvider.GEMINI.name
        val provider = LlmProvider.fromName(providerName) ?: LlmProvider.GEMINI

        val apiKey = secureKeyStorage.getKey("transcription")
            ?: secureKeyStorage.getKey("legacy")

        return if (apiKey != null) {
            val answeringProv = prefs[answeringProviderKey]
            val answeringKey = secureKeyStorage.getKey("answering") ?: apiKey
            LlmConfig(
                transcriptionProvider = provider.name,
                transcriptionApiKey = apiKey,
                answeringProvider = answeringProv ?: provider.name,
                answeringApiKey = answeringKey
            )
        } else null
    }
}
