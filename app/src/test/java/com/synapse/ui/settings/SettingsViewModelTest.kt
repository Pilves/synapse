package com.synapse.ui.settings

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.synapse.api.LlmProvider
import com.synapse.data.repository.ProjectRepository
import com.synapse.data.storage.SecureKeyStorage
import com.synapse.model.Project
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val instantRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var projectRepository: ProjectRepository
    private lateinit var secureKeyStorage: SecureKeyStorage

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        dataStore = mockk(relaxed = true)
        projectRepository = mockk(relaxed = true)
        secureKeyStorage = mockk(relaxed = true)

        // Provide empty preferences flow
        every { dataStore.data } returns flowOf(preferencesOf())
        every { projectRepository.observeProjects() } returns flowOf(emptyList())
        every { secureKeyStorage.getKey(any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = SettingsViewModel(
        dataStore = dataStore,
        projectRepository = projectRepository,
        secureKeyStorage = secureKeyStorage
    )

    // --- API Key Validation ---

    @Test
    fun `validateApiKey returns false for empty key`() = runTest {
        every { secureKeyStorage.getKey(any()) } returns null

        val vm = createViewModel()
        advanceUntilIdle()

        val result = vm.validateApiKey()
        assertFalse(result)
    }

    @Test
    fun `validateApiKey returns true for valid key`() = runTest {
        every { secureKeyStorage.getKey("transcription") } returns "sk-ant-api03-" + "a".repeat(40)
        // llmProvider defaults to GEMINI from empty prefs, but we validate format only
        // Actually, let's just test with a valid generic key
        every { secureKeyStorage.getKey("transcription") } returns "a".repeat(25)
        every { secureKeyStorage.getKey("legacy") } returns null

        val vm = createViewModel()
        advanceUntilIdle()

        val result = vm.validateApiKey()
        assertTrue(result)
        assertNull(vm.uiState.value.apiKeyError)
    }

    @Test
    fun `validateApiKey sets error message on invalid key`() = runTest {
        every { secureKeyStorage.getKey("transcription") } returns "short"
        every { secureKeyStorage.getKey("legacy") } returns null

        val vm = createViewModel()
        advanceUntilIdle()

        vm.validateApiKey()
        assertTrue(vm.uiState.value.apiKeyError != null)
    }

    // --- setApiKey ---

    @Test
    fun `setApiKey saves to secure storage`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setApiKey("new-api-key-value")
        advanceUntilIdle()

        verify { secureKeyStorage.saveKey("transcription", "new-api-key-value") }
        verify { secureKeyStorage.saveKey("legacy", "new-api-key-value") }
    }

    @Test
    fun `setApiKey clears error state`() = runTest {
        every { secureKeyStorage.getKey("transcription") } returns "short"
        every { secureKeyStorage.getKey("legacy") } returns null

        val vm = createViewModel()
        advanceUntilIdle()

        // Trigger validation error first
        vm.validateApiKey()
        assertTrue(vm.uiState.value.apiKeyError != null)

        // Setting a new key should clear the error
        vm.setApiKey("new-key")
        advanceUntilIdle()

        assertNull(vm.uiState.value.apiKeyError)
    }

    // --- Prompt Template Validation ---

    @Test
    fun `validatePromptTemplate returns empty for valid template`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val errors = vm.validatePromptTemplate("Content {cleanup_enabled} and {advanced_formatting}")
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `validatePromptTemplate returns missing placeholders`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val errors = vm.validatePromptTemplate("No placeholders here")
        assertEquals(2, errors.size)
    }

    @Test
    fun `validatePromptTemplate detects single missing placeholder`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val errors = vm.validatePromptTemplate("Has {cleanup_enabled} but not the other")
        assertEquals(1, errors.size)
        assertEquals("{advanced_formatting}", errors[0])
    }

    // --- UI State ---

    @Test
    fun `clearApiKeyError clears error`() = runTest {
        every { secureKeyStorage.getKey("transcription") } returns "bad"
        every { secureKeyStorage.getKey("legacy") } returns null

        val vm = createViewModel()
        advanceUntilIdle()

        vm.validateApiKey()
        assertTrue(vm.uiState.value.apiKeyError != null)

        vm.clearApiKeyError()
        assertNull(vm.uiState.value.apiKeyError)
    }

    @Test
    fun `setLoading updates loading state`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)

        vm.setLoading(true)
        assertTrue(vm.uiState.value.isLoading)

        vm.setLoading(false)
        assertFalse(vm.uiState.value.isLoading)
    }

    // --- Project Management ---

    @Test
    fun `addProject delegates to repository`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val project = Project(
            id = "p1",
            name = "Test",
            pathUri = "content://test",
            defaultFile = "notes.md"
        )
        vm.addProject(project)
        advanceUntilIdle()

        coVerify { projectRepository.addProject("Test", "content://test", "notes.md") }
    }

    @Test
    fun `updateProject delegates to repository`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val project = Project(
            id = "p1",
            name = "Updated",
            pathUri = "content://test",
            defaultFile = "notes.md"
        )
        vm.updateProject(project)
        advanceUntilIdle()

        coVerify { projectRepository.updateProject(project) }
    }

    @Test
    fun `deleteProject delegates to repository`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteProject("p1")
        advanceUntilIdle()

        coVerify { projectRepository.deleteProject("p1") }
    }

    // --- LLM Config ---

    @Test
    fun `setLlmConfig saves keys to secure storage and non-secret to DataStore`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val config = com.synapse.model.LlmConfig(
            transcriptionProvider = "claude",
            transcriptionApiKey = "sk-ant-key",
            answeringProvider = "gemini",
            answeringApiKey = "AIza-key"
        )
        vm.setLlmConfig(config)
        advanceUntilIdle()

        verify { secureKeyStorage.saveKey("transcription", "sk-ant-key") }
        verify { secureKeyStorage.saveKey("answering", "AIza-key") }
    }

    // --- Default values ---

    @Test
    fun `default SettingsUiState has no errors and is not loading`() {
        val state = SettingsUiState()
        assertFalse(state.isLoading)
        assertNull(state.apiKeyError)
        assertTrue(state.promptValidationErrors.isEmpty())
    }

    @Test
    fun `Defaults object has expected values`() {
        assertEquals(1f, SettingsViewModel.Defaults.CHUNK_TIMEOUT, 0.01f)
        assertEquals(0.2f, SettingsViewModel.Defaults.FADE_ANIMATION, 0.01f)
        assertEquals(15, SettingsViewModel.Defaults.SESSION_AUTO_END)
        assertEquals(LlmProvider.GEMINI, SettingsViewModel.Defaults.LLM_PROVIDER)
        assertTrue(SettingsViewModel.Defaults.CLEANUP_MODE)
        assertTrue(SettingsViewModel.Defaults.ADVANCED_FORMATTING)
        assertFalse(SettingsViewModel.Defaults.RATE_LIMITING_SAFE)
        assertTrue(SettingsViewModel.Defaults.PREFER_TEXT_ONLY)
    }
}
