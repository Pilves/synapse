package com.synapse

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.synapse.di.apiModule
import com.synapse.di.appModule
import com.synapse.di.repositoryModule
import com.synapse.di.serviceHelpersModule
import com.synapse.di.storageModule
import com.synapse.di.v2Module
import com.synapse.di.viewModelModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.java.KoinJavaComponent.get

/**
 * Application class for Synapse - Handwriting Capture App
 *
 * Initializes Koin dependency injection and sets up notification channels.
 *
 * Koin modules are loaded in dependency order:
 * 1. appModule - Core application dependencies (DataStore)
 * 2. storageModule - File storage layer (ChunkStorage, SessionStorage, etc.)
 * 3. apiModule - Network layer (OkHttpClient, TranscriptionServiceFactory)
 * 4. repositoryModule - Business logic (ChunkRepository, SessionRepository, etc.)
 * 5. serviceHelpersModule - Service utilities (NotificationHelper, PermissionHelper)
 * 6. viewModelModule - UI ViewModels
 * 7. Legacy modules for backward compatibility (dataModule, networkModule)
 */
class SynapseApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        initKoin()
        createNotificationChannels()
        get<com.synapse.service.NotificationHelper>(com.synapse.service.NotificationHelper::class.java).createNotificationChannels()

        // Migrate API keys from plain DataStore to encrypted storage
        appScope.launch {
            val secureKeyStorage = get<com.synapse.data.storage.SecureKeyStorage>(
                com.synapse.data.storage.SecureKeyStorage::class.java
            )
            val dataStore = get<androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>>(
                androidx.datastore.core.DataStore::class.java
            )
            secureKeyStorage.migrateFromDataStore(dataStore)
        }
    }

    /**
     * Initialize Koin dependency injection framework
     *
     * Modules are loaded in a specific order to ensure dependencies are available:
     * - Storage modules first (no dependencies on other app modules)
     * - API modules (may depend on storage)
     * - Repository modules (depend on storage and API)
     * - Service helpers (may depend on repositories)
     * - ViewModels last (may depend on all other modules)
     */
    private fun initKoin() {
        startKoin {
            // Use Android logger for Koin logs (debug only)
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)

            // Provide Android context
            androidContext(this@SynapseApplication)

            // Load Koin modules in dependency order
            modules(
                // Core application module
                appModule,

                // Storage layer - no dependencies on other app modules
                storageModule,

                // API/Network layer
                apiModule,

                // Repository layer - depends on storage and API
                repositoryModule,

                // V2 features - destinations, cost, capture, intent services
                v2Module,

                // Service helpers
                serviceHelpersModule,

                // ViewModel layer - depends on all other modules
                viewModelModule
            )
        }
    }

    /**
     * Create notification channels for Android 8.0+ (API 26+)
     */
    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        // Overlay Service Notification Channel
        val overlayChannel = NotificationChannel(
            OVERLAY_CHANNEL_ID,
            getString(R.string.overlay_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.overlay_notification_channel_description)
            setShowBadge(false)
        }

        // Capture Processing Notification Channel
        val captureChannel = NotificationChannel(
            CAPTURE_CHANNEL_ID,
            getString(R.string.capture_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.capture_notification_channel_description)
        }

        notificationManager.createNotificationChannels(
            listOf(overlayChannel, captureChannel)
        )
    }

    companion object {
        const val OVERLAY_CHANNEL_ID = "synapse_overlay_channel"
        const val CAPTURE_CHANNEL_ID = "synapse_capture_channel"
    }
}
