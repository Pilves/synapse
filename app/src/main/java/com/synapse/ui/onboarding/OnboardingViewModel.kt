package com.synapse.ui.onboarding

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import com.synapse.data.repository.ProjectRepository
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.synapse.data.storage.SecureKeyStorage
import com.synapse.service.SynapseAccessibilityService
import com.synapse.ui.settings.settingsDataStore
import com.synapse.util.ApiKeyValidator
import com.synapse.util.PermissionHelper
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for managing the onboarding flow state and actions.
 *
 * Handles:
 * - Tracking current page
 * - Permission requests (overlay, notifications)
 * - Folder picker for vault selection
 * - API key validation and storage
 * - Marking onboarding as complete
 * - Checking what's already configured
 */
class OnboardingViewModel(
    application: Application,
    private val projectRepository: ProjectRepository,
    private val secureKeyStorage: SecureKeyStorage
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OnboardingViewModel"
    }

    private val context: Context
        get() = getApplication<Application>().applicationContext

    private val dataStore = context.settingsDataStore
    private val permissionHelper = PermissionHelper(context)

    private val _state = MutableStateFlow(OnboardingState.Initial)
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    // Preference keys — onboarding-specific state only
    private object PreferenceKeys {
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }

    // Settings keys — app-wide source of truth for vault/API config
    private object SettingsKeys {
        val VAULT_LOCATION = stringPreferencesKey("vault_location")
        val TRANSCRIPTION_PROVIDER = stringPreferencesKey("transcription_provider")
        val TRANSCRIPTION_API_KEY = stringPreferencesKey("transcription_api_key")
        val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val API_KEY = stringPreferencesKey("api_key")
    }

    init {
        loadSavedState()
    }

    /**
     * Loads previously saved state from DataStore and checks current permissions.
     */
    private fun loadSavedState() {
        viewModelScope.launch {
            // Check permissions
            val hasOverlay = permissionHelper.hasOverlayPermission()
            val hasNotification = permissionHelper.hasNotificationPermission()
            val hasAccessibility = SynapseAccessibilityService.isEnabled(context)

            // Load all state from single settings DataStore
            val prefs = dataStore.data.first()
            val isComplete = prefs[PreferenceKeys.ONBOARDING_COMPLETE] ?: false
            val vaultPath = prefs[SettingsKeys.VAULT_LOCATION]
            val apiKey = secureKeyStorage.getKey("transcription")
                ?: secureKeyStorage.getKey("legacy")

            _state.update { currentState ->
                currentState.copy(
                    hasOverlayPermission = hasOverlay,
                    hasAccessibilityPermission = hasAccessibility,
                    hasNotificationPermission = hasNotification,
                    hasVaultConfigured = !vaultPath.isNullOrBlank(),
                    vaultPath = vaultPath,
                    hasApiKey = !apiKey.isNullOrBlank(),
                    isOnboardingComplete = isComplete
                )
            }
        }
    }

    /**
     * Refreshes the permission states. Call this when returning from system settings.
     */
    fun refreshPermissions() {
        _state.update { currentState ->
            currentState.copy(
                hasOverlayPermission = permissionHelper.hasOverlayPermission(),
                hasAccessibilityPermission = SynapseAccessibilityService.isEnabled(context),
                hasNotificationPermission = permissionHelper.hasNotificationPermission()
            )
        }
    }

    /**
     * Navigates to the next onboarding page.
     */
    fun nextPage() {
        _state.update { currentState ->
            if (currentState.currentPage < currentState.totalPages - 1) {
                currentState.copy(currentPage = currentState.currentPage + 1)
            } else {
                currentState
            }
        }
    }

    /**
     * Navigates to the previous onboarding page.
     */
    fun previousPage() {
        _state.update { currentState ->
            if (currentState.currentPage > 0) {
                currentState.copy(currentPage = currentState.currentPage - 1)
            } else {
                currentState
            }
        }
    }

    /**
     * Navigates to a specific page.
     */
    fun goToPage(page: Int) {
        _state.update { currentState ->
            if (page in 0 until currentState.totalPages) {
                currentState.copy(currentPage = page)
            } else {
                currentState
            }
        }
    }

    /**
     * Requests the overlay permission by opening system settings.
     * Returns an Intent that should be launched by the Activity.
     */
    fun getOverlayPermissionIntent() = permissionHelper.getOverlayPermissionIntent()

    /**
     * Handles the result of a folder picker for vault selection.
     */
    fun onVaultFolderSelected(uri: Uri?) {
        if (uri == null) {
            _state.update { it.copy(errorMessage = "No folder selected") }
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "Vault folder selected: $uri")

                // Persist permission for the folder
                val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)

                val vaultPath = uri.toString()

                // Save to settings DataStore (app-wide source of truth)
                context.settingsDataStore.edit { prefs ->
                    prefs[SettingsKeys.VAULT_LOCATION] = vaultPath
                }
                Log.d(TAG, "Saved vault path to settings DataStore: $vaultPath")

                // Update UI immediately so user can continue onboarding
                _state.update { currentState ->
                    currentState.copy(
                        hasVaultConfigured = true,
                        vaultPath = vaultPath,
                        errorMessage = null
                    )
                }

                // Sync projects from the vault in the background.
                // Use SupervisorJob so a failure here doesn't cancel the parent scope.
                viewModelScope.launch(SupervisorJob()) {
                    try {
                        projectRepository.syncProjectsFromVault(Uri.parse(vaultPath))
                        Log.d(TAG, "Synced projects from vault")
                    } catch (e: Exception) {
                        Log.e(TAG, "Background vault sync failed", e)
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "Failed to access folder: ${e.message}") }
            }
        }
    }

    /**
     * Saves the API key and validates it.
     */
    fun saveApiKey(apiKey: String) {
        if (apiKey.isBlank()) {
            _state.update { it.copy(errorMessage = "API key cannot be empty") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isValidatingApiKey = true, errorMessage = null) }

            try {
                // Validate API key format based on common patterns
                val validationResult = ApiKeyValidator.validateFormat(apiKey.trim())

                if (!validationResult.isValid) {
                    _state.update {
                        it.copy(
                            isValidatingApiKey = false,
                            errorMessage = validationResult.errorMessage
                        )
                    }
                    return@launch
                }

                // Save to encrypted storage (used by LlmSettingsProvider at sync time)
                val trimmedKey = apiKey.trim()
                secureKeyStorage.saveKey("transcription", trimmedKey)
                secureKeyStorage.saveKey("legacy", trimmedKey)

                // Save provider to DataStore (non-secret config)
                val provider = ApiKeyValidator.detectProvider(trimmedKey)
                context.settingsDataStore.edit { prefs ->
                    prefs[SettingsKeys.TRANSCRIPTION_PROVIDER] = provider
                    prefs[SettingsKeys.LLM_PROVIDER] = provider
                }

                _state.update { currentState ->
                    currentState.copy(
                        hasApiKey = true,
                        isValidatingApiKey = false,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isValidatingApiKey = false,
                        errorMessage = "Failed to save API key: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Retrieves the saved API key for display (masked) or editing.
     */
    fun getApiKeyFlow() = context.settingsDataStore.data.map { prefs ->
        prefs[SettingsKeys.TRANSCRIPTION_API_KEY]
            ?: prefs[SettingsKeys.API_KEY]
            ?: ""
    }

    /**
     * Retrieves the saved vault path.
     */
    fun getVaultPathFlow() = context.settingsDataStore.data.map { prefs ->
        prefs[SettingsKeys.VAULT_LOCATION]
    }

    /**
     * Marks the onboarding as complete.
     */
    fun completeOnboarding() {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[PreferenceKeys.ONBOARDING_COMPLETE] = true
            }

            _state.update { currentState ->
                currentState.copy(isOnboardingComplete = true)
            }
        }
    }

    /**
     * Skips a specific setup step and moves to the next page.
     */
    fun skipCurrentStep() {
        nextPage()
    }

    /**
     * Clears any displayed error message.
     */
    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * Resets onboarding state (useful for testing or re-running onboarding).
     */
    fun resetOnboarding() {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs.remove(PreferenceKeys.ONBOARDING_COMPLETE)
            }

            _state.value = OnboardingState.Initial.copy(
                hasOverlayPermission = permissionHelper.hasOverlayPermission(),
                hasNotificationPermission = permissionHelper.hasNotificationPermission()
            )
        }
    }

    /**
     * Checks if onboarding should be shown based on current state.
     */
    fun shouldShowOnboarding(): Boolean {
        return !_state.value.isOnboardingComplete
    }

    /**
     * Returns the URL for getting a free Gemini API key.
     */
    fun getGeminiApiKeyUrl(): String {
        return "https://makersuite.google.com/app/apikey"
    }
}
