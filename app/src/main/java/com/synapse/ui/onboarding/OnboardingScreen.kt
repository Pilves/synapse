package com.synapse.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch

/**
 * Main onboarding screen that hosts the HorizontalPager with all onboarding pages.
 *
 * @param onOnboardingComplete Callback when onboarding is finished
 * @param viewModel The OnboardingViewModel instance
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
    viewModel: OnboardingViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Pager state
    val pagerState = rememberPagerState(
        initialPage = state.currentPage,
        pageCount = { state.totalPages }
    )

    // Helper to advance to the next page via the pager (single source of truth)
    val goToNextPage: () -> Unit = {
        scope.launch {
            val next = (pagerState.currentPage + 1).coerceAtMost(state.totalPages - 1)
            pagerState.animateScrollToPage(next)
        }
    }

    // Pager is the single source of truth — sync pager → ViewModel only
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (state.currentPage != page) {
                viewModel.goToPage(page)
            }
        }
    }

    // Handle errors
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // Check if onboarding is complete
    LaunchedEffect(state.isOnboardingComplete) {
        if (state.isOnboardingComplete) {
            onOnboardingComplete()
        }
    }

    // Activity result launchers
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissions()
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            com.synapse.service.MediaProjectionHolder.setResult(result.resultCode, result.data!!)
            // Forward to OverlayService if it's running
            try {
                val serviceIntent = android.content.Intent(context, com.synapse.service.OverlayService::class.java).apply {
                    action = com.synapse.service.OverlayService.ACTION_SET_MEDIA_PROJECTION
                    putExtra(com.synapse.service.OverlayService.EXTRA_PROJECTION_RESULT_CODE, result.resultCode)
                    putExtra(com.synapse.service.OverlayService.EXTRA_PROJECTION_DATA, result.data)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                // Service might not be running yet during onboarding, that's ok
            }
            goToNextPage()
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        viewModel.onVaultFolderSelected(uri)
    }

    Scaffold(
        topBar = {
            if (pagerState.currentPage > 0) {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Go back"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Page indicator
            PageIndicator(
                pageCount = state.totalPages,
                currentPage = pagerState.currentPage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            )

            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                userScrollEnabled = true
            ) { page ->
                when (page) {
                    0 -> WelcomePage(
                        onGetStarted = goToNextPage
                    )
                    1 -> OverlayPermissionPage(
                        hasPermission = state.hasOverlayPermission,
                        onGrantPermission = {
                            viewModel.getOverlayPermissionIntent()?.let { intent ->
                                overlayPermissionLauncher.launch(intent)
                            }
                        },
                        onRefreshPermissions = { viewModel.refreshPermissions() },
                        onSkip = goToNextPage,
                        onContinue = goToNextPage
                    )
                    2 -> AccessibilityPermissionScreen(
                        onEnabled = goToNextPage,
                        onSkip = goToNextPage
                    )
                    3 -> ScreenCapturePermissionPage(
                        onGrant = {
                            val projectionManager = context.getSystemService(
                                android.content.Context.MEDIA_PROJECTION_SERVICE
                            ) as android.media.projection.MediaProjectionManager
                            screenCaptureLauncher.launch(
                                projectionManager.createScreenCaptureIntent()
                            )
                        },
                        onSkip = goToNextPage,
                        onContinue = goToNextPage
                    )
                    4 -> SelectVaultPage(
                        hasVaultConfigured = state.hasVaultConfigured,
                        vaultPath = state.vaultPath,
                        onPickFolder = {
                            folderPickerLauncher.launch(null)
                        },
                        onSkip = goToNextPage,
                        onContinue = goToNextPage
                    )
                    5 -> DestinationSetupScreen(
                        onComplete = goToNextPage,
                        onSkip = goToNextPage
                    )
                    6 -> ApiKeyPage(
                        hasApiKey = state.hasApiKey,
                        isValidating = state.isValidatingApiKey,
                        onSaveApiKey = { key ->
                            viewModel.saveApiKey(key)
                        },
                        onGetFreeKey = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.getGeminiApiKeyUrl()))
                            context.startActivity(intent)
                        },
                        onSkip = { viewModel.completeOnboarding() },
                        onComplete = { viewModel.completeOnboarding() }
                    )
                }
            }
        }
    }
}

/**
 * Page indicator dots showing current position in the onboarding flow.
 */
@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center
    ) {
        repeat(pageCount) { index ->
            val isSelected = index == currentPage
            val size by animateDpAsState(
                targetValue = if (isSelected) 10.dp else 8.dp,
                label = "indicator_size"
            )

            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(size)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        }
                    )
            )
        }
    }
}

/**
 * Page 1: Welcome page
 */
@Composable
private fun WelcomePage(
    onGetStarted: () -> Unit
) {
    OnboardingPage(
        icon = "\u270F\uFE0F",
        title = "Welcome to Synapse",
        description = "Capture handwritten notes\nwithout leaving your app",
        primaryButtonText = "Get Started",
        onPrimaryClick = onGetStarted
    )
}

/**
 * Page 2: Overlay permission page
 */
@Composable
private fun OverlayPermissionPage(
    hasPermission: Boolean,
    onGrantPermission: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit
) {
    // Check permission when returning from settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    OnboardingPage(
        icon = "\uD83D\uDCF1",
        title = "Overlay Permission",
        description = "Synapse needs to draw over\nother apps to capture your notes.",
        primaryButtonText = if (hasPermission) "Continue" else "Grant Permission",
        onPrimaryClick = if (hasPermission) onContinue else onGrantPermission,
        tertiaryButtonText = if (!hasPermission) "Skip for now" else null,
        onTertiaryClick = if (!hasPermission) onSkip else null
    )
}

/**
 * Page 3: Screen capture permission page
 */
@Composable
private fun ScreenCapturePermissionPage(
    onGrant: () -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit
) {
    // After returning from the system dialog, auto-advance if permission was granted
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (com.synapse.service.MediaProjectionHolder.hasResult()) {
                    onContinue()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasPermission = com.synapse.service.MediaProjectionHolder.hasResult()

    OnboardingPage(
        icon = "\uD83D\uDDBC\uFE0F",
        title = "Screen Capture",
        description = "Synapse can capture screenshots\nwhen no text is found in a selection.\nGreat for diagrams and images.",
        primaryButtonText = if (hasPermission) "Continue" else "Grant Permission",
        onPrimaryClick = if (hasPermission) onContinue else onGrant,
        tertiaryButtonText = if (!hasPermission) "Skip for now" else null,
        onTertiaryClick = if (!hasPermission) onSkip else null
    )
}

/**
 * Page 4: Select vault page
 */
@Composable
private fun SelectVaultPage(
    hasVaultConfigured: Boolean,
    vaultPath: String?,
    onPickFolder: () -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OnboardingPage(
            icon = "\uD83D\uDCC1",
            title = "Select Your Vault",
            description = if (hasVaultConfigured && vaultPath != null) {
                "Vault configured:\n${getDisplayPath(vaultPath)}"
            } else {
                "Choose your Obsidian\nvault folder."
            },
            primaryButtonText = if (hasVaultConfigured) "Continue" else "Pick Folder",
            onPrimaryClick = if (hasVaultConfigured) onContinue else onPickFolder,
            secondaryButtonText = if (hasVaultConfigured) "Change Folder" else null,
            onSecondaryClick = if (hasVaultConfigured) onPickFolder else null,
            tertiaryButtonText = if (!hasVaultConfigured) "Skip for now" else null,
            onTertiaryClick = if (!hasVaultConfigured) onSkip else null
        )
    }
}

/**
 * Page 4: API key page
 */
@Composable
private fun ApiKeyPage(
    hasApiKey: Boolean,
    isValidating: Boolean,
    onSaveApiKey: (String) -> Unit,
    onGetFreeKey: () -> Unit,
    onSkip: () -> Unit,
    onComplete: () -> Unit
) {
    var apiKeyInput by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon
        Text(
            text = "\uD83D\uDD11",
            fontSize = androidx.compose.ui.unit.TextUnit(72f, androidx.compose.ui.unit.TextUnitType.Sp),
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Title
        Text(
            text = "API Key",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Description
        Text(
            text = if (hasApiKey) {
                "API key configured!\nYou're all set."
            } else {
                "Enter your Gemini API key\nfor transcription."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // API Key input field (only show if not configured)
        AnimatedVisibility(
            visible = !hasApiKey,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                label = { Text("Gemini API Key") },
                placeholder = { Text("Enter your API key") },
                singleLine = true,
                visualTransformation = if (showApiKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            imageVector = if (showApiKey) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = if (showApiKey) "Hide API key" else "Show API key"
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (apiKeyInput.isNotBlank()) {
                            onSaveApiKey(apiKeyInput)
                        }
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Buttons
        if (hasApiKey) {
            androidx.compose.material3.Button(
                onClick = onComplete,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "Complete Setup",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onGetFreeKey,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                ) {
                    Text(
                        text = "Get free key",
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                androidx.compose.material3.Button(
                    onClick = {
                        focusManager.clearFocus()
                        onSaveApiKey(apiKeyInput)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    enabled = apiKeyInput.isNotBlank() && !isValidating
                ) {
                    if (isValidating) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = "Enter",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            // Skip button
            androidx.compose.material3.TextButton(
                onClick = onSkip,
                modifier = Modifier.padding(top = 16.dp),
                enabled = !isValidating
            ) {
                Text(
                    text = "Skip for now",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Helper function to get a display-friendly path from a URI.
 */
private fun getDisplayPath(uriString: String): String {
    return try {
        val uri = Uri.parse(uriString)
        val path = uri.path ?: uriString
        // Extract just the folder name from the path
        val segments = path.split("/")
        if (segments.size > 1) {
            ".../${segments.takeLast(2).joinToString("/")}"
        } else {
            path
        }
    } catch (e: Exception) {
        uriString
    }
}

/**
 * Finds an Activity of the given type from a Context, unwrapping ContextWrappers.
 */
private inline fun <reified T : android.app.Activity> android.content.Context.findActivity(): T? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is T) return ctx
        ctx = ctx.baseContext
    }
    return null
}
