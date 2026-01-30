package com.synapse.di

import com.synapse.api.DefaultTranscriptionServiceFactory
import com.synapse.api.LlmProviderFactory
import com.synapse.api.QuestionAnswerService
import com.synapse.api.TranscriptionServiceFactory
import com.synapse.data.cost.LlmCostCalculator
import com.synapse.data.cost.UsageTracker
import com.synapse.data.destination.ClipboardDestination
import com.synapse.data.destination.DestinationRepository
import com.synapse.data.destination.LocalFolderDestination
import com.synapse.data.destination.ShareIntentDestination
import com.synapse.data.repository.ChunkRepository
import com.synapse.data.repository.ChunkRepositoryImpl
import com.synapse.data.repository.ProjectRepository
import com.synapse.data.repository.ProjectRepositoryImpl
import com.synapse.data.repository.SessionRepository
import com.synapse.data.repository.SessionRepositoryImpl
import com.synapse.data.repository.SyncRepository
import com.synapse.data.repository.SyncRepositoryImpl
import com.synapse.data.storage.ChunkStorage
import com.synapse.data.storage.ImageProcessor
import com.synapse.data.storage.ProjectStorage
import com.synapse.data.storage.SecureKeyStorage
import com.synapse.data.storage.SessionStorage
import com.synapse.data.storage.StorageHelper
import com.synapse.data.storage.SyncStorage
import com.synapse.data.storage.VaultManager
import com.synapse.model.Destination
import com.synapse.service.NotificationHelper
import com.synapse.service.RegionCaptureManager
import com.synapse.service.ReminderManager
import com.synapse.service.ScreenshotManager
import com.synapse.service.SynapseAccessibilityService
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
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/**
 * Storage module - File storage and image processing
 *
 * Provides singleton instances of all storage-related classes:
 * - ChunkStorage: Manages chunk image files
 * - SessionStorage: Manages session metadata
 * - ProjectStorage: Manages project configurations
 * - SyncStorage: Manages sync queue
 * - VaultManager: Manages vault/project file operations
 * - ImageProcessor: Handles image conversion and manipulation
 * - StorageHelper: Utility functions for storage operations
 */
val storageModule = module {
    // SecureKeyStorage - encrypted API key storage
    single { SecureKeyStorage(androidContext()) }

    // ChunkStorage - requires Context
    single { ChunkStorage(androidContext()) }

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

    // StorageHelper - requires Context
    single { StorageHelper(androidContext()) }
}

/**
 * Repository module - Business logic layer
 *
 * Provides singleton instances of repository implementations:
 * - ChunkRepository: Chunk management operations
 * - SessionRepository: Session lifecycle management
 * - ProjectRepository: Project CRUD operations
 * - SyncRepository: Sync queue and transcription operations
 */
val repositoryModule = module {
    // ChunkRepository - requires ChunkStorage and SessionStorage
    single<ChunkRepository> {
        ChunkRepositoryImpl(
            chunkStorage = get(),
            sessionStorage = get()
        )
    }

    // SessionRepository - requires SessionStorage and ChunkStorage
    single<SessionRepository> {
        SessionRepositoryImpl(
            sessionStorage = get(),
            chunkStorage = get()
        )
    }

    // ProjectRepository - requires ProjectStorage and VaultManager
    single<ProjectRepository> {
        ProjectRepositoryImpl(
            projectStorage = get(),
            vaultManager = get()
        )
    }

    // LlmSettingsProvider - reads LLM settings from DataStore + SecureKeyStorage
    single { com.synapse.data.LlmSettingsProvider(get(), get()) }

    // SyncRepository - requires Context, storage classes, and TranscriptionServiceFactory
    single<SyncRepository> {
        val factory: TranscriptionServiceFactory = get()
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
    }
}

/**
 * API/Network module - HTTP clients and API services
 *
 * Provides singleton instances of:
 * - OkHttpClient: Configured HTTP client with timeouts
 * - TranscriptionServiceFactory: Factory for creating LLM service instances
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
            .retryOnConnectionFailure(true)
            .build()
    }

    // TranscriptionServiceFactory - singleton instance, shares OkHttpClient
    single<TranscriptionServiceFactory> {
        DefaultTranscriptionServiceFactory(get())
    }
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
            syncRepository = get()
        )
    }

    // SettingsViewModel - requires DataStore, ProjectRepository, and SecureKeyStorage
    viewModel { SettingsViewModel(androidContext().settingsDataStore, get(), get()) }

    // OnboardingViewModel - requires Application and ProjectRepository for AndroidViewModel
    viewModel { OnboardingViewModel(androidApplication(), get()) }
}

/**
 * Service helpers module - Utility classes for services
 *
 * Provides singleton instances of:
 * - NotificationHelper: Notification creation and management
 * - PermissionHelper: Permission checking and requesting
 */
val serviceHelpersModule = module {
    // NotificationHelper - requires Context
    single { NotificationHelper(androidContext()) }

    // PermissionHelper - requires Context
    single { PermissionHelper(androidContext()) }

    // SynapseCapabilities - capability mode detection
    single { SynapseCapabilities(androidContext()) }

    // PermissionHealthMonitor - reactive permission state monitoring
    single { PermissionHealthMonitor(androidContext()) }
}

/**
 * V2 features module - Destination, cost, context, intent services
 *
 * Provides singleton instances of all v2 feature classes:
 * - Destinations: Clipboard, Share, LocalFolder + DestinationRepository
 * - Cost: LlmCostCalculator, UsageTracker
 * - Context: RegionCaptureManager, ScreenshotManager
 * - Intent: ReminderManager, QuestionAnswerService
 * - Network: NetworkMonitor
 * - LLM: LlmProviderFactory
 */
val v2Module = module {
    // Destinations
    single { ClipboardDestination(androidContext()) }
    single { ShareIntentDestination(androidContext()) }
    single { LocalFolderDestination(androidContext()) }
    single {
        val destinations = mapOf<String, Destination>(
            "clipboard" to get<ClipboardDestination>(),
            "share" to get<ShareIntentDestination>(),
            "local_folder" to get<LocalFolderDestination>()
        )
        DestinationRepository(
            dataStore = androidContext().settingsDataStore,
            destinations = destinations
        )
    }

    // Cost tracking
    single { LlmCostCalculator }
    single { UsageTracker(androidContext().settingsDataStore) }

    // Network
    single { NetworkMonitor(androidContext()) }

    // Screenshot & region capture
    single { ScreenshotManager(androidContext()) }
    single {
        RegionCaptureManager(
            context = androidContext(),
            accessibilityServiceProvider = { SynapseAccessibilityService.getInstance() },
            screenshotManager = get()
        )
    }

    // Intent services
    single { ReminderManager(androidContext()) }

    // LLM routing
    single { LlmProviderFactory(get()) }
    single { QuestionAnswerService(get<LlmProviderFactory>(), androidContext().filesDir) }
}

/**
 * Main application module - Core dependencies
 *
 * This module combines DataStore configuration with any additional
 * application-level dependencies not covered by other modules.
 */
val appModule = module {
    // DataStore preferences for settings
    single { androidContext().settingsDataStore }
}

// Timeout configuration constants
private const val CONNECT_TIMEOUT_SECONDS = 30L
private const val READ_TIMEOUT_SECONDS = 60L
private const val WRITE_TIMEOUT_SECONDS = 60L

/**
 * Returns all Koin modules in the correct order for dependency resolution.
 *
 * The order ensures that dependencies are available when needed:
 * 1. appModule - Core application dependencies
 * 2. storageModule - Storage layer (no repository dependencies)
 * 3. apiModule - Network layer
 * 4. repositoryModule - Business logic (depends on storage and API)
 * 5. serviceHelpersModule - Service utilities
 * 6. viewModelModule - UI layer (depends on all other modules)
 */
fun getAllModules() = listOf(
    appModule,
    storageModule,
    apiModule,
    repositoryModule,
    v2Module,
    serviceHelpersModule,
    viewModelModule
)
