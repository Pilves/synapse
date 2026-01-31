package com.synapse

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.synapse.di.apiModule
import com.synapse.di.repositoryModule
import com.synapse.di.storageModule
import com.synapse.di.viewModelModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.synapse.util.CrashReporter
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.java.KoinJavaComponent.get

/**
 * Application class for Synapse - Handwriting Capture App
 *
 * Initializes Koin dependency injection and sets up notification channels.
 *
 * Koin modules are loaded in dependency order:
 * 1. storageModule - Storage layer and preferences (DataStore, ChunkStorage, etc.)
 * 2. apiModule - Network layer and LLM services (OkHttpClient, TranscriptionServiceFactory)
 * 3. repositoryModule - Business logic and service helpers (repositories, helpers)
 * 4. viewModelModule - UI ViewModels
 */
class SynapseApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        CrashReporter.initialize(this)
        initKoin()
        createNotificationChannels()
        get<com.synapse.service.NotificationHelper>(com.synapse.service.NotificationHelper::class.java).createNotificationChannels()

        // Initialize storage and migrate API keys asynchronously
        appScope.launch {
            // Initialize SessionStorage from disk (was previously blocking startup)
            get<com.synapse.data.storage.SessionStorage>(
                com.synapse.data.storage.SessionStorage::class.java
            ).initialize()
        }
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
                // Storage layer and preferences - no dependencies on other app modules
                storageModule,

                // API/Network layer and LLM services
                apiModule,

                // Repository layer and service helpers - depends on storage and API
                repositoryModule,

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
