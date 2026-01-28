package com.synapse.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.synapse.ui.review.ReviewScreen
import com.synapse.ui.review.ReviewViewModel

/**
 * Main app scaffold containing the Review screen with top bar and FAB.
 *
 * Features:
 * - Top app bar with title and settings navigation
 * - Badge showing pending chunk count
 * - FAB for starting overlay capture
 *
 * Layout:
 * ```
 * +-----------------------------------+
 * | Synapse                [Settings] |
 * +-----------------------------------+
 * |                                   |
 * |         Review Screen             |
 * |                                   |
 * |                                   |
 * |                                   |
 * |                              [FAB]|
 * +-----------------------------------+
 * ```
 *
 * @param navController Navigation controller for navigating to Settings
 * @param onStartOverlay Callback to start the overlay capture service
 * @param reviewViewModel ViewModel for the review screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    navController: NavHostController,
    onStartOverlay: () -> Unit,
    reviewViewModel: ReviewViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by reviewViewModel.uiState.collectAsState()

    // Calculate total pending chunks across all sessions
    val pendingChunkCount = uiState.sessions.sumOf { it.chunks.size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Synapse",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    // Settings button with optional badge for pending items
                    IconButton(
                        onClick = { navController.navigate(Screen.Settings.route) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            // FAB for starting capture overlay
            CaptureFloatingActionButton(
                onClick = onStartOverlay,
                pendingCount = pendingChunkCount
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            ReviewScreen(
                viewModel = reviewViewModel
            )
        }
    }
}

/**
 * Floating Action Button for starting the overlay capture.
 *
 * Shows a badge with the pending chunk count when there are items to review.
 *
 * @param onClick Callback when the FAB is clicked
 * @param pendingCount Number of pending chunks (displayed as badge)
 */
@Composable
private fun CaptureFloatingActionButton(
    onClick: () -> Unit,
    pendingCount: Int,
    modifier: Modifier = Modifier
) {
    BadgedBox(
        badge = {
            // Show badge only when there are pending chunks
            AnimatedVisibility(
                visible = pendingCount > 0,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ) {
                    Text(
                        text = if (pendingCount > 99) "99+" else pendingCount.toString(),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        modifier = modifier
    ) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 6.dp,
                pressedElevation = 12.dp
            ),
            shape = CircleShape
        ) {
            Icon(
                imageVector = Icons.Default.Draw,
                contentDescription = "Start Capture",
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
