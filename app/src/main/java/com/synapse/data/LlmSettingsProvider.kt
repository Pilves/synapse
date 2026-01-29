package com.synapse.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.synapse.api.LlmProvider
import com.synapse.model.LlmConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Reads LLM-related settings from DataStore.
 *
 * Extracted from the DI module to keep the Koin setup concise and make
 * the settings-reading logic reusable and testable.
 */
class LlmSettingsProvider(private val dataStore: DataStore<Preferences>) {

    private val transcriptionProviderKey = stringPreferencesKey("transcription_provider")
    private val transcriptionApiKeyKey = stringPreferencesKey("transcription_api_key")
    private val answeringProviderKey = stringPreferencesKey("answering_provider")
    private val answeringApiKeyKey = stringPreferencesKey("answering_api_key")
    private val rateLimitingSafeKey = booleanPreferencesKey("rate_limiting_safe")
    // Legacy fallback keys
    private val legacyProviderKey = stringPreferencesKey("llm_provider")
    private val legacyApiKeyKey = stringPreferencesKey("api_key")

    /**
     * Reads the current transcription provider, API key, and rate-limiting flag.
     * Falls back to legacy keys when the new keys are absent.
     */
    fun readLlmSettings(): Triple<LlmProvider, String?, Boolean> {
        return runBlocking {
            val prefs = dataStore.data.first()
            val providerName = prefs[transcriptionProviderKey]
                ?: prefs[legacyProviderKey]
                ?: LlmProvider.GEMINI.name
            val apiKey = prefs[transcriptionApiKeyKey]
                ?: prefs[legacyApiKeyKey]
            val provider = LlmProvider.fromName(providerName) ?: LlmProvider.GEMINI
            val rateLimitingSafe = prefs[rateLimitingSafeKey] ?: true
            Triple(provider, apiKey, rateLimitingSafe)
        }
    }

    /**
     * Reads the full LLM configuration including answering provider settings.
     * Returns null when no API key is configured.
     */
    fun readFullLlmConfig(): LlmConfig? {
        return runBlocking {
            val prefs = dataStore.data.first()
            val providerName = prefs[transcriptionProviderKey]
                ?: prefs[legacyProviderKey]
                ?: LlmProvider.GEMINI.name
            val apiKey = prefs[transcriptionApiKeyKey]
                ?: prefs[legacyApiKeyKey]
            val provider = LlmProvider.fromName(providerName) ?: LlmProvider.GEMINI
            if (apiKey != null) {
                val answeringProv = prefs[answeringProviderKey]
                val answeringKey = prefs[answeringApiKeyKey]
                LlmConfig(
                    transcriptionProvider = provider.name,
                    transcriptionApiKey = apiKey,
                    answeringProvider = answeringProv ?: provider.name,
                    answeringApiKey = answeringKey ?: apiKey
                )
            } else null
        }
    }
}
