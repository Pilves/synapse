package com.synapse.ui.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.synapse.ui.onboarding.OnboardingScreen
import com.synapse.ui.onboarding.OnboardingViewModel
import com.synapse.ui.review.ReviewScreen
import com.synapse.ui.review.ReviewViewModel
import org.koin.androidx.compose.koinViewModel
import com.synapse.ui.settings.ProjectManagerScreen
import com.synapse.ui.settings.PromptEditorScreen
import com.synapse.ui.settings.SettingsScreen
import com.synapse.ui.settings.SettingsViewModel
import com.synapse.ui.settings.settingsDataStore

/**
 * Sealed class representing all navigation destinations in the app.
 *
 * Each screen has a unique route string used for navigation.
 */
sealed class Screen(val route: String) {
    /** Onboarding flow shown on first launch */
    object Onboarding : Screen("onboarding")

    /** Main review screen for reviewing captured sessions */
    object Review : Screen("review")

    /** Settings screen for app configuration */
    object Settings : Screen("settings")

    /** Prompt template editor (accessible from Settings) */
    object PromptEditor : Screen("prompt_editor")

    /** Project manager for managing Obsidian vault projects */
    object ProjectManager : Screen("project_manager")
}

/**
 * Main navigation graph for the Synapse app.
 *
 * Sets up navigation between all screens with proper transitions
 * and handles the overlay service start callback.
 *
 * @param navController The NavHostController managing navigation
 * @param startDestination The initial screen to display (usually Review or Onboarding)
 * @param onStartOverlay Callback to start the overlay capture service
 * @param modifier Optional modifier for the NavHost
 */
@Composable
fun SynapseNavGraph(
    navController: NavHostController,
    startDestination: String,
    onStartOverlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Create shared SettingsViewModel with DataStore
    val settingsViewModel: SettingsViewModel = viewModel {
        SettingsViewModel(context.settingsDataStore)
    }

    // Folder picker callback holder - using a simple mutable reference
    val folderPickerCallback = remember { mutableStateOf<((String) -> Unit)?>(null) }
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { selectedUri ->
            // Persist permission
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(selectedUri, takeFlags)

            // Notify the callback
            folderPickerCallback.value?.invoke(selectedUri.toString())
            folderPickerCallback.value = null
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // Onboarding screen
        composable(route = Screen.Onboarding.route) {
            val onboardingViewModel: OnboardingViewModel = viewModel()

            OnboardingScreen(
                onOnboardingComplete = {
                    navController.navigate(Screen.Review.route) {
                        // Clear back stack so user can't go back to onboarding
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
                viewModel = onboardingViewModel
            )
        }

        // Main Review screen (wrapped in MainScreen scaffold)
        composable(route = Screen.Review.route) {
            val reviewViewModel: ReviewViewModel = koinViewModel()

            MainScreen(
                navController = navController,
                onStartOverlay = onStartOverlay,
                reviewViewModel = reviewViewModel
            )
        }

        // Settings screen
        composable(route = Screen.Settings.route) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPromptEditor = {
                    navController.navigate(Screen.PromptEditor.route)
                },
                onNavigateToProjectManager = {
                    navController.navigate(Screen.ProjectManager.route)
                },
                onSelectVaultLocation = {
                    folderPickerCallback.value = { path ->
                        settingsViewModel.setVaultLocation(path)
                    }
                    folderPickerLauncher.launch(null)
                }
            )
        }

        // Prompt Editor screen
        composable(route = Screen.PromptEditor.route) {
            PromptEditorScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Project Manager screen
        composable(route = Screen.ProjectManager.route) {
            ProjectManagerScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { navController.popBackStack() },
                onSelectFolder = { callback ->
                    folderPickerCallback.value = callback
                    folderPickerLauncher.launch(null)
                }
            )
        }
    }
}
