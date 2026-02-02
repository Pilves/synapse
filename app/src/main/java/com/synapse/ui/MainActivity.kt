package com.synapse.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.synapse.service.MediaProjectionHolder
import com.synapse.service.OverlayService
import com.synapse.ui.navigation.Screen
import com.synapse.ui.navigation.SynapseNavGraph
import com.synapse.ui.settings.settingsDataStore
import com.synapse.ui.theme.SynapseTheme
import com.synapse.util.PermissionHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Main entry point Activity for Synapse
 *
 * This activity hosts the Compose navigation and serves as the
 * launcher activity for the application.
 *
 * Responsibilities:
 * - Determining the start destination (onboarding vs review)
 * - Handling overlay service start/stop
 * - Processing intent actions (from notifications)
 * - Managing permission requests
 */
class MainActivity : ComponentActivity() {

    private lateinit var permissionHelper: PermissionHelper

    // DataStore key for onboarding completion
    private val onboardingCompleteKey = booleanPreferencesKey("onboarding_complete")

    // Navigation callback to trigger navigation from intents
    private var navigateToReview: (() -> Unit)? = null

    // Flag to request navigation to review on next composition
    private var pendingNavigateToReview = false

    // Flag to show screen capture permission dialog on next composition
    private val showScreenCaptureDialog = mutableStateOf(false)

    // MediaProjection permission request launcher
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultCode = result.resultCode
            val data = requireNotNull(result.data) { "MediaProjection result data is null" }
            // Store the result intent for creating MediaProjection later
            MediaProjectionHolder.setResult(resultCode, data)
            // Send to OverlayService which has the mediaProjection foreground type
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SET_MEDIA_PROJECTION
                putExtra(OverlayService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
                putExtra(OverlayService.EXTRA_PROJECTION_DATA, data)
            }
            startForegroundService(serviceIntent)
            Log.d(TAG, "MediaProjection result forwarded to OverlayService")
            // Return to whatever was on screen before the permission prompt
            moveTaskToBack(true)
        } else {
            Log.w(TAG, "MediaProjection permission denied")
            Toast.makeText(
                this,
                "Screen capture permission is required for region capture",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Overlay permission request launcher
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Check if permission was granted after returning from settings
        if (permissionHelper.hasOverlayPermission()) {
            // Permission granted, start the overlay service
            startOverlayService()
        } else {
            Toast.makeText(
                this,
                "Overlay permission is required to capture notes",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Notification permission request launcher (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(
                this,
                "Notification permission is recommended for background capture",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionHelper = PermissionHelper(this)

        enableEdgeToEdge()

        setContent {
            SynapseTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    // State for start destination
                    var startDestination by remember { mutableStateOf<String?>(null) }

                    // State for showing permission rationale dialog
                    var showOverlayPermissionDialog by remember { mutableStateOf(false) }

                    // Observe screen capture dialog state from intent handling
                    val showScreenCapturePermissionDialog by showScreenCaptureDialog

                    // Check if onboarding is complete
                    LaunchedEffect(Unit) {
                        val isOnboardingComplete = settingsDataStore.data.map { prefs ->
                            prefs[onboardingCompleteKey] ?: false
                        }.first()

                        startDestination = if (isOnboardingComplete) {
                            Screen.Review.route
                        } else {
                            Screen.Onboarding.route
                        }

                        // Handle intent if the app was started from a notification
                        handleIntent(intent)

                        // Request notification permission on Android 13+
                        requestNotificationPermissionIfNeeded()
                    }

                    // Show the navigation graph once we've determined the start destination
                    startDestination?.let { destination ->
                        // Set up navigation callback for intent handling
                        navigateToReview = {
                            navController.navigate(Screen.Review.route) {
                                popUpTo(navController.graph.startDestinationId) { inclusive = false }
                                launchSingleTop = true
                            }
                        }

                        // Check if there's a pending navigation request
                        if (pendingNavigateToReview) {
                            pendingNavigateToReview = false
                            navigateToReview?.invoke()
                        }

                        SynapseNavGraph(
                            navController = navController,
                            startDestination = destination,
                            onStartOverlay = {
                                Log.d(TAG, "onStartOverlay called")
                                val hasPermission = permissionHelper.hasOverlayPermission()
                                Log.d(TAG, "hasOverlayPermission: $hasPermission")
                                if (hasPermission) {
                                    startOverlayService()
                                } else {
                                    showOverlayPermissionDialog = true
                                }
                            }
                        )
                    }

                    // Overlay permission rationale dialog
                    if (showOverlayPermissionDialog) {
                        AlertDialog(
                            onDismissRequest = { showOverlayPermissionDialog = false },
                            title = { Text("Overlay Permission Required") },
                            text = {
                                Text(
                                    "Synapse needs permission to draw over other apps to capture " +
                                            "your handwritten notes from any screen. This is required " +
                                            "for the floating capture button to work."
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showOverlayPermissionDialog = false
                                        requestOverlayPermission()
                                    }
                                ) {
                                    Text("Grant Permission")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { showOverlayPermissionDialog = false }
                                ) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    // Screen capture permission rationale dialog
                    if (showScreenCapturePermissionDialog) {
                        AlertDialog(
                            onDismissRequest = {
                                showScreenCaptureDialog.value = false
                            },
                            title = { Text("Screen Capture Permission") },
                            text = {
                                Text(
                                    "Synapse needs screen capture permission to take screenshots " +
                                            "of selected regions. This is used when text cannot be " +
                                            "extracted directly from the screen content. Your screen " +
                                            "will only be captured when you explicitly select a region."
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showScreenCaptureDialog.value = false
                                        requestMediaProjectionPermission()
                                    }
                                ) {
                                    Text("Allow Screen Capture")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        showScreenCaptureDialog.value = false
                                    }
                                ) {
                                    Text("Not Now")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Handles incoming intents, including those from notification actions.
     */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_REVIEW -> {
                // Navigate to review screen - try immediately or set pending flag
                if (navigateToReview != null) {
                    navigateToReview?.invoke()
                } else {
                    pendingNavigateToReview = true
                }
            }
            ACTION_STOP_OVERLAY -> {
                stopOverlayService()
            }
            OverlayService.ACTION_START -> {
                if (permissionHelper.hasOverlayPermission()) {
                    startOverlayService()
                }
            }
            OverlayService.ACTION_STOP -> {
                stopOverlayService()
            }
            ACTION_REQUEST_SCREEN_CAPTURE -> {
                Log.d(TAG, "Screen capture permission requested via intent")
                showScreenCaptureDialog.value = true
            }
        }
    }

    /**
     * Requests MediaProjection permission for screen capture.
     * Launches the system screen capture consent dialog.
     */
    fun requestMediaProjectionPermission() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    /**
     * Requests the overlay permission by opening system settings.
     */
    private fun requestOverlayPermission() {
        permissionHelper.getOverlayPermissionIntent()?.let { intent ->
            overlayPermissionLauncher.launch(intent)
        }
    }

    /**
     * Requests notification permission on Android 13+.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Starts the overlay foreground service.
     */
    private fun startOverlayService() {
        Log.d(TAG, "startOverlayService called")
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
        }
        try {
            Log.d(TAG, "Starting foreground service")
            startForegroundService(serviceIntent)
            Log.d(TAG, "Service start initiated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
        }

        // Optionally minimize the app after starting the overlay
        // moveTaskToBack(true)
    }

    /**
     * Stops the overlay foreground service.
     */
    private fun stopOverlayService() {
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    /**
     * Marks onboarding as complete in DataStore.
     * Called from the OnboardingScreen when the user completes the flow.
     */
    fun markOnboardingComplete() {
        lifecycleScope.launch {
            settingsDataStore.edit { prefs ->
                prefs[onboardingCompleteKey] = true
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        /** Intent action for opening the review screen from notification */
        const val ACTION_OPEN_REVIEW = "com.synapse.action.OPEN_REVIEW"

        /** Intent action for stopping the overlay from notification */
        const val ACTION_STOP_OVERLAY = "com.synapse.action.STOP_OVERLAY"

        /** Intent action for requesting screen capture (MediaProjection) permission */
        const val ACTION_REQUEST_SCREEN_CAPTURE = "com.synapse.action.REQUEST_SCREEN_CAPTURE"
    }
}
