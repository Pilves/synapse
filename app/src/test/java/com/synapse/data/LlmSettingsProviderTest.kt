package com.synapse.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.synapse.api.LlmProvider
import com.synapse.data.storage.SecureKeyStorage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LlmSettingsProviderTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var secureKeyStorage: SecureKeyStorage
    private lateinit var provider: LlmSettingsProvider

    @Before
    fun setup() {
        dataStore = mockk(relaxed = true)
        secureKeyStorage = mockk(relaxed = true)
        provider = LlmSettingsProvider(dataStore, secureKeyStorage)
    }

    @Test
    fun `readLlmSettings returns default Gemini when no provider set`() = runTest {
        every { dataStore.data } returns flowOf(preferencesOf())
        every { secureKeyStorage.getKey("transcription") } returns "test-key"
        every { secureKeyStorage.getKey("legacy") } returns null

        val (llmProvider, apiKey, rateLimitingSafe) = provider.readLlmSettings()

        assertEquals(LlmProvider.GEMINI, llmProvider)
        assertEquals("test-key", apiKey)
        assertTrue(rateLimitingSafe)
    }

    @Test
    fun `readLlmSettings reads transcription provider from DataStore`() = runTest {
        val prefs = preferencesOf(
            stringPreferencesKey("transcription_provider") to "claude"
        )
        every { dataStore.data } returns flowOf(prefs)
        every { secureKeyStorage.getKey("transcription") } returns "sk-ant-key"
        every { secureKeyStorage.getKey("legacy") } returns null

        val (llmProvider, apiKey, _) = provider.readLlmSettings()

        assertEquals(LlmProvider.CLAUDE, llmProvider)
        assertEquals("sk-ant-key", apiKey)
    }

    @Test
    fun `readLlmSettings falls back to legacy provider key`() = runTest {
        val prefs = preferencesOf(
            stringPreferencesKey("llm_provider") to "openai"
        )
        every { dataStore.data } returns flowOf(prefs)
        every { secureKeyStorage.getKey("transcription") } returns null
        every { secureKeyStorage.getKey("legacy") } returns "sk-openai"

        val (llmProvider, apiKey, _) = provider.readLlmSettings()

        assertEquals(LlmProvider.OPENAI, llmProvider)
        assertEquals("sk-openai", apiKey)
    }

    @Test
    fun `readLlmSettings returns null apiKey when no keys stored`() = runTest {
        every { dataStore.data } returns flowOf(preferencesOf())
        every { secureKeyStorage.getKey(any()) } returns null

        val (_, apiKey, _) = provider.readLlmSettings()

        assertNull(apiKey)
    }

    @Test
    fun `readLlmSettings reads rateLimitingSafe flag`() = runTest {
        val prefs = preferencesOf(
            booleanPreferencesKey("rate_limiting_safe") to false
        )
        every { dataStore.data } returns flowOf(prefs)
        every { secureKeyStorage.getKey(any()) } returns null

        val (_, _, rateLimitingSafe) = provider.readLlmSettings()

        assertEquals(false, rateLimitingSafe)
    }

    @Test
    fun `readFullLlmConfig returns null when no API key`() = runTest {
        every { dataStore.data } returns flowOf(preferencesOf())
        every { secureKeyStorage.getKey(any()) } returns null

        val config = provider.readFullLlmConfig()

        assertNull(config)
    }

    @Test
    fun `readFullLlmConfig returns config when API key present`() = runTest {
        every { dataStore.data } returns flowOf(preferencesOf())
        every { secureKeyStorage.getKey("transcription") } returns "test-key"
        every { secureKeyStorage.getKey("legacy") } returns null
        every { secureKeyStorage.getKey("answering") } returns null

        val config = provider.readFullLlmConfig()

        assertNotNull(config)
        assertEquals("test-key", config!!.transcriptionApiKey)
        assertEquals("test-key", config.answeringApiKey) // falls back to transcription key
    }
}
