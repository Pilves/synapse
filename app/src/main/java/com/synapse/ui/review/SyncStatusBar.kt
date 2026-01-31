package com.synapse.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synapse.model.SyncStatus
import com.synapse.ui.theme.SynapseTheme

/**
 * Status bar that shows sync progress and status
 */
@Composable
fun SyncStatusBar(
    syncStatus: SyncStatus,
    onDismiss: () -> Unit,
    syncedSessionIds: Set<String> = emptySet(),
    failedChunkIds: Set<String> = emptySet(),
    onClearSynced: (() -> Unit)? = null,
    onRetryFailed: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isVisible = syncStatus !is SyncStatus.Idle

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = getBackgroundColor(syncStatus),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            shadowElevation = 8.dp
        ) {
            when (syncStatus) {
                is SyncStatus.Idle -> {}
                is SyncStatus.Queued -> QueuedContent()
                is SyncStatus.InProgress -> InProgressContent(progress = syncStatus.progress)
                is SyncStatus.Success -> SuccessContent(
                    hasSyncedSessions = syncedSessionIds.isNotEmpty(),
                    onClearSynced = onClearSynced,
                    onDismiss = onDismiss
                )
                is SyncStatus.PartialSuccess -> PartialSuccessContent(
                    syncedCount = syncStatus.syncedCount,
                    failedCount = syncStatus.failedCount,
                    hasFailedChunks = failedChunkIds.isNotEmpty(),
                    onRetryFailed = onRetryFailed,
                    onDismiss = onDismiss
                )
                is SyncStatus.Error -> ErrorContent(
                    message = syncStatus.message,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun getBackgroundColor(status: SyncStatus): Color {
    return when (status) {
        is SyncStatus.Success -> MaterialTheme.colorScheme.primaryContainer
        is SyncStatus.Error -> MaterialTheme.colorScheme.errorContainer
        is SyncStatus.PartialSuccess -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
}

@Composable
private fun QueuedContent() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.HourglassTop,
            contentDescription = "Sync status: queued",
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Preparing to sync...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InProgressContent(progress: Float) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 300),
        label = "progress"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = Icons.Default.CloudSync,
                contentDescription = "Sync status: in progress",
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    progress < 0.1f -> "Preparing..."
                    progress < 0.9f -> "Processing... ${(progress * 100).toInt()}%"
                    progress < 1.0f -> "Writing to file..."
                    else -> "Finishing up..."
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            strokeCap = StrokeCap.Round
        )
    }
}

@Composable
private fun SuccessContent(
    hasSyncedSessions: Boolean = false,
    onClearSynced: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Sync status: synced",
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "All chunks synced successfully!",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f)
        )
        if (hasSyncedSessions && onClearSynced != null) {
            TextButton(onClick = onClearSynced) {
                Text("Clear")
            }
        }
        TextButton(onClick = onDismiss) {
            Text("Dismiss")
        }
    }
}

@Composable
private fun PartialSuccessContent(
    syncedCount: Int,
    failedCount: Int,
    hasFailedChunks: Boolean = false,
    onRetryFailed: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Sync status: partial success",
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.tertiary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Partial sync completed",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = "$syncedCount synced, $failedCount failed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
            )
        }
        if (hasFailedChunks && onRetryFailed != null) {
            TextButton(onClick = onRetryFailed) {
                Text("Retry Failed")
            }
        }
        TextButton(onClick = onDismiss) {
            Text("Dismiss")
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = "Sync status: error",
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Sync failed",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
            )
        }
        TextButton(onClick = onDismiss) {
            Text("Dismiss")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SyncStatusBarQueuedPreview() {
    SynapseTheme {
        SyncStatusBar(
            syncStatus = SyncStatus.Queued,
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SyncStatusBarInProgressPreview() {
    SynapseTheme {
        SyncStatusBar(
            syncStatus = SyncStatus.InProgress(0.65f),
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SyncStatusBarSuccessPreview() {
    SynapseTheme {
        SyncStatusBar(
            syncStatus = SyncStatus.Success,
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SyncStatusBarPartialPreview() {
    SynapseTheme {
        SyncStatusBar(
            syncStatus = SyncStatus.PartialSuccess(5, 2),
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SyncStatusBarErrorPreview() {
    SynapseTheme {
        SyncStatusBar(
            syncStatus = SyncStatus.Error("Network connection failed"),
            onDismiss = {}
        )
    }
}
