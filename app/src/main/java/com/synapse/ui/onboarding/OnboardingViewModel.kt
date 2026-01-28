package com.synapse.ui.onboarding

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.synapse.util.PermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// DataStore extension for onboarding preferences
private val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "onboarding_preferences"
)

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
    application: Application
) : AndroidViewModel(application) {

    private val context: Context
        get() = getApplication<Application>().applicationContext

    private val dataStore = context.onboardingDataStore
    private val permissionHelper = PermissionHelper(context)

    private val _state = MutableStateFlow(OnboardingState.Initial)
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    // Preference keys
    private object PreferenceKeys {
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val VAULT_PATH = stringPreferencesKey("vault_path")
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

            // Load saved preferences
            val prefs = dataStore.data.first()
            val isComplete = prefs[PreferenceKeys.ONBOARDING_COMPLETE] ?: false
            val vaultPath = prefs[PreferenceKeys.VAULT_PATH]
            val apiKey = prefs[PreferenceKeys.API_KEY]

            _state.update { currentState ->
                currentState.copy(
                    hasOverlayPermission = hasOverlay,
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
                // Persist permission for the folder
                val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)

                val vaultPath = uri.toString()

                // Save to DataStore
                dataStore.edit { prefs ->
                    prefs[PreferenceKeys.VAULT_PATH] = vaultPath
                }

                _state.update { currentState ->
                    currentState.copy(
                        hasVaultConfigured = true,
                        vaultPath = vaultPath,
                        errorMessage = null
                    )
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
                val validationResult = validateApiKeyFormat(apiKey.trim())

                if (!validationResult.isValid) {
                    _state.update {
                        it.copy(
                            isValidatingApiKey = false,
                            errorMessage = validationResult.errorMessage
                        )
                    }
                    return@launch
                }

                // Save to DataStore
                dataStore.edit { prefs ->
                    prefs[PreferenceKeys.API_KEY] = apiKey.trim()
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
     * Validates API key format based on provider patterns.
     * Supports Gemini, Claude, and OpenAI key formats.
     */
    private fun validateApiKeyFormat(apiKey: String): ApiKeyValidationResult {
        // Check minimum length
        if (apiKey.length < 20) {
            return ApiKeyValidationResult(
                isValid = false,
                errorMessage = "API key is too short (minimum 20 characters)"
            )
        }

        // Check for common invalid patterns
        if (apiKey.contains(" ")) {
            return ApiKeyValidationResult(
                isValid = false,
                errorMessage = "API key should not contain spaces"
            )
        }

        // Validate based on detected provider format
        return when {
            // Gemini keys are typically alphanumeric and 39 characters
            apiKey.length >= 30 && apiKey.all { it.isLetterOrDigit() || it == '-' || it == '_' } -> {
                ApiKeyValidationResult(isValid = true)
            }
            // Claude keys start with sk-ant-
            apiKey.startsWith("sk-ant-") -> {
                if (apiKey.length >= 40) {
                    ApiKeyValidationResult(isValid = true)
                } else {
                    ApiKeyValidationResult(
                        isValid = false,
                        errorMessage = "Claude API key appears incomplete"
                    )
                }
            }
            // OpenAI keys start with sk-
            apiKey.startsWith("sk-") -> {
                if (apiKey.length >= 40) {
                    ApiKeyValidationResult(isValid = true)
                } else {
                    ApiKeyValidationResult(
                        isValid = false,
                        errorMessage = "OpenAI API key appears incomplete"
                    )
                }
            }
            // Accept other formats if they meet minimum requirements
            apiKey.length >= 20 -> {
                ApiKeyValidationResult(isValid = true)
            }
            else -> {
                ApiKeyValidationResult(
                    isValid = false,
                    errorMessage = "Invalid API key format"
                )
            }
        }
    }

    private data class ApiKeyValidationResult(
        val isValid: Boolean,
        val errorMessage: String? = null
    )

    /**
     * Retrieves the saved API key for display (masked) or editing.
     */
    fun getApiKeyFlow() = dataStore.data.map { prefs ->
        prefs[PreferenceKeys.API_KEY] ?: ""
    }

    /**
     * Retrieves the saved vault path.
     */
    fun getVaultPathFlow() = dataStore.data.map { prefs ->
        prefs[PreferenceKeys.VAULT_PATH]
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
                prefs.clear()
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
