package com.synapse.model

data class Settings(
    // Capture settings
    val captureIntervalSeconds: Int = 30,
    val imageQuality: Int = 85,
    val maxStorageMb: Long = 1024,

    // Transcription settings
    val autoTranscribe: Boolean = true,
    val transcriptionBatchSize: Int = 10,
    val preferredLanguage: String = "en",

    // Sync settings
    val autoSync: Boolean = true,
    val syncOnWifiOnly: Boolean = true,
    val syncIntervalMinutes: Int = 15,

    // Notification settings
    val showCaptureNotification: Boolean = true,
    val showSyncNotification: Boolean = true,
    val notificationSoundEnabled: Boolean = false,

    // Storage settings
    val storageLocation: String = "internal",
    val autoCleanupEnabled: Boolean = true,
    val cleanupAfterDays: Int = 30,

    // Theme settings
    val darkModeEnabled: Boolean = false,
    val useSystemTheme: Boolean = true,

    // Advanced settings
    val debugLoggingEnabled: Boolean = false,
    val crashReportingEnabled: Boolean = true,
    val analyticsEnabled: Boolean = true
)
