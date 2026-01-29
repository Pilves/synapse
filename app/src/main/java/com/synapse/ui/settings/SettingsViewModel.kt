package com.synapse.ui.settings

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synapse.api.LlmProvider
import com.synapse.api.PromptTemplate
import com.synapse.data.repository.ProjectRepository
import com.synapse.model.LlmConfig
import com.synapse.model.Project
import com.synapse.ui.overlay.InputMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Extension property for DataStore
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "synapse_settings")

/**
 * ViewModel for managing all application settings.
 * Handles loading, saving, and validation of settings via DataStore.
 */
class SettingsViewModel(
    private val dataStore: DataStore<Preferences>,
    private val projectRepository: ProjectRepository
) : ViewModel() {

    // Preference Keys
    private object PreferenceKeys {
        // Capture settings
        val CHUNK_TIMEOUT = floatPreferencesKey("chunk_timeout_seconds")
        val FADE_ANIMATION = floatPreferencesKey("fade_animation_seconds")
        val SESSION_AUTO_END = intPreferencesKey("session_auto_end_minutes")
        val INPUT_MODE = stringPreferencesKey("input_mode")

        // Review settings
        val DEFAULT_VIEW_STITCHED = booleanPreferencesKey("default_view_stitched")

        // Transcription settings
        val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val API_KEY = stringPreferencesKey("api_key")
        val CLEANUP_MODE = booleanPreferencesKey("cleanup_mode")
        val ADVANCED_FORMATTING = booleanPreferencesKey("advanced_formatting")
        val RATE_LIMITING_SAFE = booleanPreferencesKey("rate_limiting_safe")
        val CUSTOM_PROMPT_TEMPLATE = stringPreferencesKey("custom_prompt_template")

        // Multi-provider LLM settings
        val TRANSCRIPTION_PROVIDER = stringPreferencesKey("transcription_provider")
        val TRANSCRIPTION_API_KEY = stringPreferencesKey("transcription_api_key")
        val ANSWERING_PROVIDER = stringPreferencesKey("answering_provider")
        val ANSWERING_API_KEY = stringPreferencesKey("answering_api_key")

        // Vault settings
        val VAULT_LOCATION = stringPreferencesKey("vault_location")
        val DEFAULT_PROJECT_ID = stringPreferencesKey("default_project_id")
    }

    // Default values
    object Defaults {
        const val CHUNK_TIMEOUT = 1f
        const val FADE_ANIMATION = 0.2f
        const val SESSION_AUTO_END = 15
        val INPUT_MODE = InputMode.STYLUS_WRITE_FINGER_SCROLL
        const val DEFAULT_VIEW_STITCHED = true
        val LLM_PROVIDER = LlmProvider.GEMINI
        const val CLEANUP_MODE = false
        const val ADVANCED_FORMATTING = false
        const val RATE_LIMITING_SAFE = true
        const val VAULT_LOCATION = ""
    }

    // UI State
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Settings flows
    val chunkTimeout: StateFlow<Float> = dataStore.data
        .map { it[PreferenceKeys.CHUNK_TIMEOUT] ?: Defaults.CHUNK_TIMEOUT }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Defaults.CHUNK_TIMEOUT)

    val fadeAnimation: StateFlow<Float> = dataStore.data
        .map { it[PreferenceKeys.FADE_ANIMATION] ?: Defaults.FADE_ANIMATION }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Defaults.FADE_ANIMATION)

    val sessionAutoEnd: StateFlow<Int> = dataStore.data
        .map { it[PreferenceKeys.SESSION_AUTO_END] ?: Defaults.SESSION_AUTO_END }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Defaults.SESSION_AUTO_END)

    val inputMode: StateFlow<InputMode> = dataStore.data
        .map { prefs ->
            prefs[PreferenceKeys.INPUT_MODE]?.let { name ->
                try {
                    InputMode.valueOf(name)
                } catch (e: IllegalArgumentException) {
                    Defaults.INPUT_MODE
                }
            } ?: Defaults.INPUT_MODE
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Defaults.INPUT_MODE)

    val defaultViewStitched: StateFlow<Boolean> = dataStore.data
        .map { it[PreferenceKeys.DEFAULT_VIEW_STITCHED] ?: Defaults.DEFAULT_VIEW_STITCHED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Defaults.DEFAULT_VIEW_STITCHED)

    val llmProvider: StateFlow<LlmProvider> = dataStore.data
        .map { prefs ->
            prefs[PreferenceKeys.LLM_PROVIDER]?.let { LlmProvider.fromName(it) }
                ?: Defaults.LLM_PROVIDER
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Defaults.LLM_PROVIDER)

    val apiKey: StateFlow<String> = dataStore.data
        .map { it[PreferenceKeys.API_KEY] ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val cleanupMode: StateFlow<Boolean> = dataStore.data
        .map { it[PreferenceKeys.CLEANUP_MODE] ?: Defaults.CLEANUP_MODE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Defaults.CLEANUP_MODE)

    val advancedFormatting: StateFlow<Boolean> = dataStore.data
        .map { it[PreferenceKeys.ADVANCED_FORMATTING] ?: Defaults.ADVANCED_FORMATTING }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Defaults.ADVANCED_FORMATTING)

    val rateLimitingSafe: StateFlow<Boolean> = dataStore.data
        .map { it[PreferenceKeys.RATE_LIMITING_SAFE] ?: Defaults.RATE_LIMITING_SAFE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Defaults.RATE_LIMITING_SAFE)

    val customPromptTemplate: StateFlow<String?> = dataStore.data
        .map { it[PreferenceKeys.CUSTOM_PROMPT_TEMPLATE] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val vaultLocation: StateFlow<String> = dataStore.data
        .map { it[PreferenceKeys.VAULT_LOCATION] ?: Defaults.VAULT_LOCATION }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Defaults.VAULT_LOCATION)

    val projects: StateFlow<List<Project>> = projectRepository.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val defaultProjectId: StateFlow<String?> = dataStore.data
        .map { it[PreferenceKeys.DEFAULT_PROJECT_ID] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val llmConfig: StateFlow<LlmConfig> = dataStore.data
        .map { prefs ->
            LlmConfig(
                transcriptionProvider = prefs[PreferenceKeys.TRANSCRIPTION_PROVIDER]
                    ?: prefs[PreferenceKeys.LLM_PROVIDER]?.lowercase()
                    ?: "gemini",
                transcriptionApiKey = prefs[PreferenceKeys.TRANSCRIPTION_API_KEY]
                    ?: prefs[PreferenceKeys.API_KEY]
                    ?: "",
                answeringProvider = prefs[PreferenceKeys.ANSWERING_PROVIDER],
                answeringApiKey = prefs[PreferenceKeys.ANSWERING_API_KEY]
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            LlmConfig(transcriptionProvider = "gemini", transcriptionApiKey = "")
        )

    // Capture settings
    fun setChunkTimeout(value: Float) {
        viewModelScope.launch {
            dataStore.edit { it[PreferenceKeys.CHUNK_TIMEOUT] = value.coerceIn(1f, 10f) }
        }
    }

    fun setFadeAnimation(value: Float) {
        viewModelScope.launch {
            dataStore.edit { it[PreferenceKeys.FADE_ANIMATION] = value.coerceIn(0f, 1f) }
        }
    }

    fun setSessionAutoEnd(minutes: Int) {
        viewModelScope.launch {
            dataStore.edit { it[PreferenceKeys.SESSION_AUTO_END] = minutes.coerceIn(5, 60) }
        }
    }

    fun setInputMode(mode: InputMode) {
        viewModelScope.launch {
            dataStore.edit { it[PreferenceKeys.INPUT_MODE] = mode.name }
        }
    }

    // Review settings
    fun setDefaultViewStitched(stitched: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[PreferenceKeys.DEFAULT_VIEW_STITCHED] = stitched }
        }
    }

    // Transcription settings
    fun setLlmProvider(provider: LlmProvider) {
        viewModelScope.launch {
            dataStore.edit { it[PreferenceKeys.LLM_PROVIDER] = provider.name }
            // Clear API key validation state when provider changes
            _uiState.update { it.copy(apiKeyError = null) }
        }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch {
            dataStore.edit { it[PreferenceKeys.API_KEY] = key }
            // Clear validation error when key changes
            _uiState.update { it.copy(apiKeyError = null) }
        }
    }

    fun validateApiKey(): Boolean {
        val currentKey = apiKey.value
        val currentProvider = llmProvider.value

        // Ollama doesn't require an API key
        if (currentProvider == LlmProvider.OLLAMA) {
            _uiState.update { it.copy(apiKeyError = null) }
            return true
        }

        if (currentKey.isBlank()) {
            _uiState.update { it.copy(
                apiKeyError = "API key is required for ${currentProvider.displayName}"
            ) }
            return false
        }

        // Basic format validation per provider
        val isValid = when (currentProvider) {
            LlmProvider.GEMINI -> currentKey.length >= 20
            LlmProvider.CLAUDE -> currentKey.startsWith("sk-ant-")
            LlmProvider.OPENAI -> currentKey.startsWith("sk-")
            LlmProvider.OLLAMA -> true
        }

        if (!isValid) {
            _uiState.update { it.copy(
                apiKeyError = "Invalid API key format for ${currentProvider.displayName}"
            ) }
            return false
        }

        _uiState.update { it.copy(apiKeyError = null) }
        return true
    }

    fun setLlmConfig(config: LlmConfig) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[PreferenceKeys.TRANSCRIPTION_PROVIDER] = config.transcriptionProvider
                prefs[PreferenceKeys.TRANSCRIPTION_API_KEY] = config.transcriptionApiKey
                if (config.answeringProvider != null) {
                    prefs[PreferenceKeys.ANSWERING_PROVIDER] = config.answeringProvider
                } else {
                    prefs.remove(PreferenceKeys.ANSWERING_PROVIDER)
                }
                if (config.answeringApiKey != null) {
                    prefs[PreferenceKeys.ANSWERING_API_KEY] = config.answeringApiKey
                } else {
                    prefs.remove(PreferenceKeys.ANSWERING_API_KEY)
                }
            }
        }
    }

    fun setCleanupMode(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[PreferenceKeys.CLEANUP_MODE] = enabled }
        }
    }

    fun setAdvancedFormatting(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[PreferenceKeys.ADVANCED_FORMATTING] = enabled }
        }
    }

    fun setRateLimitingSafe(safe: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[PreferenceKeys.RATE_LIMITING_SAFE] = safe }
        }
    }

    fun setCustomPromptTemplate(template: String?) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                if (template.isNullOrBlank()) {
                    prefs.remove(PreferenceKeys.CUSTOM_PROMPT_TEMPLATE)
                } else {
                    prefs[PreferenceKeys.CUSTOM_PROMPT_TEMPLATE] = template
                }
            }
        }
    }

    fun validatePromptTemplate(template: String): List<String> {
        return PromptTemplate.validateTemplate(template)
    }

    fun resetPromptTemplate() {
        setCustomPromptTemplate(null)
    }

    // Vault settings
    fun setVaultLocation(path: String) {
        viewModelScope.launch {
            dataStore.edit { it[PreferenceKeys.VAULT_LOCATION] = path }
            // Auto-sync projects from vault folders
            if (path.isNotBlank()) {
                projectRepository.syncProjectsFromVault(Uri.parse(path))
            }
        }
    }

    // Project management - delegates to ProjectRepository for shared storage
    fun addProject(project: Project) {
        viewModelScope.launch {
            projectRepository.addProject(project.name, project.pathUri, project.defaultFile)
        }
    }

    fun updateProject(project: Project) {
        viewModelScope.launch {
            projectRepository.updateProject(project)
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            projectRepository.deleteProject(projectId)
            // Clear default if deleted project was default
            if (defaultProjectId.value == projectId) {
                setDefaultProject(null)
            }
        }
    }

    fun setDefaultProject(projectId: String?) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                if (projectId == null) {
                    prefs.remove(PreferenceKeys.DEFAULT_PROJECT_ID)
                } else {
                    prefs[PreferenceKeys.DEFAULT_PROJECT_ID] = projectId
                }
            }
        }
    }

    // UI state management
    fun clearApiKeyError() {
        _uiState.update { it.copy(apiKeyError = null) }
    }

    fun setLoading(loading: Boolean) {
        _uiState.update { it.copy(isLoading = loading) }
    }
}

/**
 * UI state for the settings screen.
 */
data class SettingsUiState(
    val isLoading: Boolean = false,
    val apiKeyError: String? = null,
    val promptValidationErrors: List<String> = emptyList()
)

/**
 * Enum for default view options.
 */
enum class DefaultView(val displayName: String) {
    STITCHED("Stitched"),
    SEPARATE("Separate")
}

/**
 * Enum for rate limiting options.
 */
enum class RateLimitingMode(val displayName: String) {
    SAFE("Safe"),
    FAST("Fast")
}
