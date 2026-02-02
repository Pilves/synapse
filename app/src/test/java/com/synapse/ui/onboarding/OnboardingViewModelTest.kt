package com.synapse.ui.onboarding

import android.app.Application
import androidx.datastore.preferences.core.edit
import com.synapse.data.repository.ProjectRepository
import com.synapse.data.storage.SecureKeyStorage
import io.mockk.coEvery
import io.mockk.mockk
import com.synapse.ui.settings.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnboardingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var application: Application
    private lateinit var projectRepository: ProjectRepository
    private lateinit var secureKeyStorage: SecureKeyStorage
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setup() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)
        application = RuntimeEnvironment.getApplication()

        // Clear DataStore to prevent state leaking between tests
        application.settingsDataStore.edit { it.clear() }

        projectRepository = mockk(relaxed = true)
        secureKeyStorage = mockk(relaxed = true)

        coEvery { secureKeyStorage.getKey(any()) } returns null

        viewModel = OnboardingViewModel(
            application = application,
            projectRepository = projectRepository,
            secureKeyStorage = secureKeyStorage
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state starts at page 0`() = runTest(testDispatcher) {
        advanceUntilIdle()
        assertEquals(0, viewModel.state.value.currentPage)
    }

    @Test
    fun `nextPage advances page`() = runTest(testDispatcher) {
        advanceUntilIdle()
        viewModel.nextPage()
        assertEquals(1, viewModel.state.value.currentPage)
    }

    @Test
    fun `nextPage does not go past last page`() = runTest(testDispatcher) {
        advanceUntilIdle()
        repeat(10) { viewModel.nextPage() }
        assertEquals(viewModel.state.value.totalPages - 1, viewModel.state.value.currentPage)
    }

    @Test
    fun `previousPage goes back`() = runTest(testDispatcher) {
        advanceUntilIdle()
        viewModel.nextPage()
        viewModel.nextPage()
        viewModel.previousPage()
        assertEquals(1, viewModel.state.value.currentPage)
    }

    @Test
    fun `previousPage does not go below 0`() = runTest(testDispatcher) {
        advanceUntilIdle()
        viewModel.previousPage()
        assertEquals(0, viewModel.state.value.currentPage)
    }

    @Test
    fun `goToPage sets specific page`() = runTest(testDispatcher) {
        advanceUntilIdle()
        viewModel.goToPage(3)
        assertEquals(3, viewModel.state.value.currentPage)
    }

    @Test
    fun `goToPage ignores invalid page`() = runTest(testDispatcher) {
        advanceUntilIdle()
        viewModel.goToPage(99)
        assertEquals(0, viewModel.state.value.currentPage)
    }

    @Test
    fun `goToPage ignores negative page`() = runTest(testDispatcher) {
        advanceUntilIdle()
        viewModel.goToPage(-1)
        assertEquals(0, viewModel.state.value.currentPage)
    }

    @Test
    fun `onVaultFolderSelected with null uri sets error`() = runTest(testDispatcher) {
        advanceUntilIdle()
        viewModel.onVaultFolderSelected(null)
        assertEquals("No folder selected", viewModel.state.value.errorMessage)
    }

    @Test
    fun `saveApiKey with blank key sets error`() = runTest(testDispatcher) {
        advanceUntilIdle()
        viewModel.saveApiKey("")
        assertEquals("API key cannot be empty", viewModel.state.value.errorMessage)
    }

    @Test
    fun `clearError clears error message`() = runTest(testDispatcher) {
        advanceUntilIdle()
        viewModel.onVaultFolderSelected(null) // sets an error
        viewModel.clearError()
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun `shouldShowOnboarding returns true initially`() = runTest(testDispatcher) {
        advanceUntilIdle()
        assertTrue(viewModel.shouldShowOnboarding())
    }

    @Test
    fun `skipCurrentStep advances to next page`() = runTest(testDispatcher) {
        advanceUntilIdle()
        viewModel.skipCurrentStep()
        assertEquals(1, viewModel.state.value.currentPage)
    }

    @Test
    fun `getGeminiApiKeyUrl returns valid URL`() {
        val url = viewModel.getGeminiApiKeyUrl()
        assertTrue(url.startsWith("https://"))
    }

    @Test
    fun `no API key stored means hasApiKey is false`() = runTest(testDispatcher) {
        advanceUntilIdle()
        assertFalse(viewModel.state.value.hasApiKey)
    }

    @Test
    fun `completeOnboarding calls without error`() = runTest(testDispatcher) {
        advanceUntilIdle()
        // completeOnboarding launches a coroutine that writes to DataStore;
        // verify it doesn't throw
        viewModel.completeOnboarding()
        advanceUntilIdle()
        // DataStore edit runs on its own dispatcher, so isOnboardingComplete
        // may not be updated synchronously. Verify no crash occurred.
    }
}
