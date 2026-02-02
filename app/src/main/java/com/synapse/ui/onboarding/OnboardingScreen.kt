package com.synapse.ui.onboarding

import android.content.Intent
import androidx.core.net.toUri
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.synapse.R
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
            snackbarHostState.showSnackbar(
                message = error,
                duration = androidx.compose.material3.SnackbarDuration.Long
            )
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
                                contentDescription = stringResource(R.string.cd_go_back)
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
                    1 -> PermissionsPage(
                        hasOverlayPermission = state.hasOverlayPermission,
                        hasAccessibilityPermission = state.hasAccessibilityPermission,
                        onGrantOverlay = {
                            viewModel.getOverlayPermissionIntent()?.let { intent ->
                                overlayPermissionLauncher.launch(intent)
                            }
                        },
                        onGrantAccessibility = {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        },
                        onRefreshPermissions = { viewModel.refreshPermissions() },
                        onContinue = goToNextPage
                    )
                    2 -> SelectVaultPage(
                        hasVaultConfigured = state.hasVaultConfigured,
                        vaultPath = state.vaultPath,
                        onPickFolder = {
                            folderPickerLauncher.launch(null)
                        },
                        onSkip = goToNextPage,
                        onContinue = goToNextPage
                    )
                    3 -> DestinationSetupScreen(
                        onComplete = { _ -> goToNextPage() },
                        onSkip = goToNextPage
                    )
                    4 -> ApiKeyPage(
                        hasApiKey = state.hasApiKey,
                        isValidating = state.isValidatingApiKey,
                        onSaveApiKey = { key ->
                            viewModel.saveApiKey(key)
                        },
                        onGetFreeKey = {
                            val intent = Intent(Intent.ACTION_VIEW, viewModel.getGeminiApiKeyUrl().toUri())
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
        title = stringResource(R.string.onboarding_welcome_title),
        description = stringResource(R.string.onboarding_welcome_description),
        primaryButtonText = stringResource(R.string.onboarding_get_started),
        onPrimaryClick = onGetStarted
    )
}

/**
 * Page 1: Combined permissions page for overlay and accessibility.
 */
@Composable
private fun PermissionsPage(
    hasOverlayPermission: Boolean,
    hasAccessibilityPermission: Boolean,
    onGrantOverlay: () -> Unit,
    onGrantAccessibility: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onContinue: () -> Unit
) {
    // Refresh permission state when returning from settings
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

    // Auto-advance when both permissions are granted
    var hasAutoAdvanced by remember { mutableStateOf(false) }
    LaunchedEffect(hasOverlayPermission, hasAccessibilityPermission) {
        if (hasOverlayPermission && hasAccessibilityPermission && !hasAutoAdvanced) {
            hasAutoAdvanced = true
            kotlinx.coroutines.delay(500)
            onContinue()
        }
    }

    val allGranted = hasOverlayPermission && hasAccessibilityPermission

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "\uD83D\uDD12",
            fontSize = androidx.compose.ui.unit.TextUnit(72f, androidx.compose.ui.unit.TextUnitType.Sp),
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Text(
            text = stringResource(R.string.onboarding_permissions_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = stringResource(R.string.onboarding_permissions_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Overlay permission row
        PermissionRow(
            label = stringResource(R.string.onboarding_draw_over_apps),
            description = stringResource(R.string.onboarding_draw_over_apps_description),
            granted = hasOverlayPermission,
            onGrant = onGrantOverlay
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Accessibility permission row
        PermissionRow(
            label = stringResource(R.string.onboarding_accessibility),
            description = stringResource(R.string.onboarding_accessibility_description),
            granted = hasAccessibilityPermission,
            onGrant = onGrantAccessibility
        )

        Spacer(modifier = Modifier.height(32.dp))

        androidx.compose.material3.Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = if (allGranted) stringResource(R.string.action_continue) else stringResource(R.string.action_skip_for_now),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/**
 * A row showing a permission with its grant status and an action button.
 */
@Composable
private fun PermissionRow(
    label: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (granted) {
            Text(
                text = "\u2705",
                fontSize = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp)
            )
        } else {
            androidx.compose.material3.OutlinedButton(onClick = onGrant) {
                Text(stringResource(R.string.action_grant))
            }
        }
    }
}

/**
 * Page 3: Select vault page
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
            title = stringResource(R.string.onboarding_vault_title),
            description = if (hasVaultConfigured && vaultPath != null) {
                stringResource(R.string.onboarding_vault_configured, getDisplayPath(vaultPath))
            } else {
                stringResource(R.string.onboarding_vault_description)
            },
            primaryButtonText = if (hasVaultConfigured) stringResource(R.string.action_continue) else stringResource(R.string.onboarding_pick_folder),
            onPrimaryClick = if (hasVaultConfigured) onContinue else onPickFolder,
            secondaryButtonText = if (hasVaultConfigured) stringResource(R.string.onboarding_change_folder) else null,
            onSecondaryClick = if (hasVaultConfigured) onPickFolder else null,
            tertiaryButtonText = if (!hasVaultConfigured) stringResource(R.string.action_skip_for_now) else null,
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
    var apiKeyInput by rememberSaveable { mutableStateOf("") }
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
            text = stringResource(R.string.onboarding_api_key_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Description
        Text(
            text = if (hasApiKey) {
                stringResource(R.string.onboarding_api_key_configured)
            } else {
                stringResource(R.string.onboarding_api_key_description)
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
                label = { Text(stringResource(R.string.onboarding_gemini_api_key_label)) },
                placeholder = { Text(stringResource(R.string.onboarding_api_key_placeholder)) },
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
                            contentDescription = if (showApiKey) stringResource(R.string.cd_hide_api_key) else stringResource(R.string.cd_show_api_key)
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
                    text = stringResource(R.string.onboarding_complete_setup),
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
                        text = stringResource(R.string.onboarding_get_free_key),
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
                            text = stringResource(R.string.onboarding_enter),
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
                    text = stringResource(R.string.action_skip_for_now),
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
        val uri = uriString.toUri()
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
