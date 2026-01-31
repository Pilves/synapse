package com.synapse.di

import com.synapse.api.DefaultTranscriptionServiceFactory
import com.synapse.api.QuestionAnswerService

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.synapse.data.cost.LlmCostCalculator
import com.synapse.data.repository.ChunkRepository
import com.synapse.data.repository.ProjectRepository
import com.synapse.data.repository.SessionRepository
import com.synapse.data.repository.SyncRepository
import com.synapse.data.repository.SyncRepositoryImpl
import com.synapse.data.storage.ChunkStorage
import com.synapse.data.storage.ImageProcessor
import com.synapse.data.storage.ProjectStorage
import com.synapse.data.storage.SecureKeyStorage
import com.synapse.data.storage.SessionStorage
import com.synapse.data.storage.SyncStorage
import com.synapse.data.storage.VaultManager
import com.synapse.service.NotificationHelper
import com.synapse.service.ScreenshotManager
import com.synapse.service.SynapseCapabilities
import com.synapse.service.PermissionHealthMonitor
import com.synapse.ui.onboarding.OnboardingViewModel
import com.synapse.ui.overlay.CaptureViewModel
import com.synapse.ui.review.ReviewViewModel
import com.synapse.ui.settings.SettingsViewModel
import com.synapse.ui.settings.settingsDataStore
import com.synapse.util.NetworkMonitor
import com.synapse.util.PermissionHelper
import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/**
 * Storage module - File storage, image processing, and app-level preferences
 *
 * Provides singleton instances of all storage-related classes:
 * - DataStore: Settings preferences
 * - SecureKeyStorage: Encrypted API key storage
 * - ChunkStorage: Manages chunk image files
 * - SessionStorage: Manages session metadata
 * - ProjectStorage: Manages project configurations
 * - SyncStorage: Manages sync queue
 * - VaultManager: Manages vault/project file operations
 * - ImageProcessor: Handles image conversion and manipulation
 */
val storageModule = module {
    // DataStore preferences for settings
    single { androidContext().settingsDataStore }

    // SecureKeyStorage - encrypted API key storage
    single { SecureKeyStorage(androidContext()) }

    // ChunkStorage - requires Context, scope for async thumbnail generation
    single { ChunkStorage(androidContext(), CoroutineScope(SupervisorJob() + Dispatchers.IO)) }

    // SessionStorage - requires Context and optionally ChunkStorage for cleanup
    single { SessionStorage(androidContext(), get<ChunkStorage>()) }

    // ProjectStorage - requires Context
    single { ProjectStorage(androidContext()) }

    // SyncStorage - requires Context
    single { SyncStorage(androidContext()) }

    // VaultManager - requires Context
    single { VaultManager(androidContext()) }

    // ImageProcessor - no dependencies
    single { ImageProcessor() }
}

/**
 * API/Network module - HTTP clients, API services, and LLM routing
 *
 * Provides singleton instances of:
 * - CertificatePinner: TLS certificate pinning for LLM APIs
 * - OkHttpClient: Configured HTTP client with timeouts
 * - DefaultTranscriptionServiceFactory: Factory for creating LLM service instances
 * - QuestionAnswerService: Q&A over LLM providers
 * - NetworkMonitor: Network connectivity monitoring
 */
val apiModule = module {
    // Certificate pinner for cloud LLM APIs.
    // Each host has two pins: the leaf certificate pin and an intermediate CA backup pin.
    // If a provider rotates their leaf certificate (common every 90 days–1 year),
    // the intermediate CA pin keeps connections working until the app is updated.
    //
    // PIN ROTATION: When a pin mismatch occurs, OkHttp throws SSLPeerUnverifiedException.
    // To update pins, run:
    //   openssl s_client -connect <host>:443 | openssl x509 -pubkey -noout \
    //     | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
    // Then ship an app update. Intermediate CA pins change rarely (years).
    single {
        CertificatePinner.Builder()
            // Anthropic — leaf + intermediate CA backup
            .add("api.anthropic.com",
                "sha256/60QDDZy98CjK1XTBTlPbInyzJzi+817KvW+usCk6r+o=",
                "sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=")
            // OpenAI — leaf + intermediate CA backup
            .add("api.openai.com",
                "sha256/y5npFVdBuoqCSOdQa42qiUSPqwMpoei7NK0rQWGUaSU=",
                "sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=")
            // Google Gemini — leaf + intermediate CA backup
            .add("generativelanguage.googleapis.com",
                "sha256/ePd8DjIPDZVBxKmWbWHXy+wZjO75a6PEiRzXE7mbzZ0=",
                "sha256/YPtHaftLw6/0vnc2BnNKGF54xiCA28WFcccjkA4ypCM=")
            .build()
    }

    // OkHttpClient with timeout configuration and certificate pinning
    single {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .certificatePinner(get())
            .connectionPool(ConnectionPool(2, 30, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .build()
    }

    // DefaultTranscriptionServiceFactory - singleton instance, shares OkHttpClient
    single<DefaultTranscriptionServiceFactory> {
        DefaultTranscriptionServiceFactory(get())
    }

    // QuestionAnswerService - Q&A over LLM providers
    single { QuestionAnswerService(get<DefaultTranscriptionServiceFactory>(), androidContext().filesDir) }

    // NetworkMonitor - network connectivity monitoring
    single { NetworkMonitor(androidContext()) }
}

/**
 * Repository module - Business logic layer and service helpers
 *
 * Provides singleton instances of:
 * - ChunkRepository: Chunk management operations
 * - SessionRepository: Session lifecycle management
 * - ProjectRepository: Project CRUD operations
 * - SyncRepository: Sync queue and transcription operations
 * - LlmCostCalculator: Token pricing and usage tracking
 * - NotificationHelper: Notification creation and management
 * - PermissionHelper: Permission checking and requesting
 * - SynapseCapabilities: Capability mode detection
 * - PermissionHealthMonitor: Reactive permission state monitoring
 * - ScreenshotManager: Screenshot and region capture
 */
val repositoryModule = module {
    // ChunkRepository - requires ChunkStorage and SessionStorage
    single {
        ChunkRepository(
            chunkStorage = get(),
            sessionStorage = get()
        )
    }

    // SessionRepository - requires SessionStorage and ChunkStorage
    single {
        SessionRepository(
            sessionStorage = get(),
            chunkStorage = get()
        )
    }

    // ProjectRepository - requires ProjectStorage and VaultManager
    single {
        ProjectRepository(
            projectStorage = get(),
            vaultManager = get()
        )
    }

    // LlmSettingsProvider - reads LLM settings from DataStore + SecureKeyStorage
    single { com.synapse.data.LlmSettingsProvider(get(), get()) }

    // SyncRepository - requires Context, storage classes, and DefaultTranscriptionServiceFactory
    single<SyncRepository> {
        val factory: DefaultTranscriptionServiceFactory = get()
        val settings: com.synapse.data.LlmSettingsProvider = get()

        SyncRepositoryImpl(
            context = androidContext(),
            sessionStorage = get(),
            chunkStorage = get(),
            projectStorage = get(),
            syncStorage = get(),
            transcriptionServiceProvider = {
                val (provider, apiKey, rateLimitingSafe) = settings.readLlmSettings()
                factory.create(provider, apiKey, rateLimitingSafe)
            },
            questionAnswerService = get<QuestionAnswerService>(),
            llmConfigProvider = suspend { settings.readFullLlmConfig() }
        )
    } onClose { (it as? java.io.Closeable)?.close() }

    // Cost tracking
    single { LlmCostCalculator }

    // NotificationHelper - requires Context
    single { NotificationHelper(androidContext()) }

    // PermissionHelper - requires Context
    single { PermissionHelper(androidContext()) }

    // SynapseCapabilities - capability mode detection
    single { SynapseCapabilities(androidContext()) }

    // PermissionHealthMonitor - reactive permission state monitoring
    single { PermissionHealthMonitor(androidContext()) }

    // ScreenshotManager - screenshot and region capture
    single { ScreenshotManager(androidContext()) }
}

/**
 * ViewModel module - UI ViewModels for Compose screens
 *
 * Provides viewModel instances for:
 * - CaptureViewModel: Overlay capture canvas state
 * - ReviewViewModel: Session review and sync UI
 * - SettingsViewModel: Application settings
 * - OnboardingViewModel: First-run setup flow
 */
val viewModelModule = module {
    // CaptureViewModel - no external dependencies, manages stroke state internally
    viewModel { CaptureViewModel() }

    // ReviewViewModel - uses repositories for sessions, projects, and sync
    viewModel {
        ReviewViewModel(
            sessionRepository = get(),
            projectRepository = get(),
            syncRepository = get(),
            llmSettingsProvider = get()
        )
    }

    // SettingsViewModel - requires DataStore, ProjectRepository, and SecureKeyStorage
    viewModel { SettingsViewModel(androidContext().settingsDataStore, get(), get()) }

    // OnboardingViewModel - requires Application, ProjectRepository, and SecureKeyStorage
    viewModel { OnboardingViewModel(androidApplication(), get(), get()) }
}

// Timeout configuration constants
private const val CONNECT_TIMEOUT_SECONDS = 30L
private const val READ_TIMEOUT_SECONDS = 60L
private const val WRITE_TIMEOUT_SECONDS = 60L

/**
 * Returns all Koin modules in the correct order for dependency resolution.
 *
 * The order ensures that dependencies are available when needed:
 * 1. storageModule - Storage layer and preferences (no dependencies on other app modules)
 * 2. apiModule - Network layer and LLM services
 * 3. repositoryModule - Business logic and service helpers (depends on storage and API)
 * 4. viewModelModule - UI layer (depends on all other modules)
 */
fun getAllModules() = listOf(
    storageModule,
    apiModule,
    repositoryModule,
    viewModelModule
)
