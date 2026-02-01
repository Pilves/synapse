package com.synapse.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.synapse.R
import com.synapse.model.QueueStatus

@Composable
fun SyncStatusIndicator(
    status: QueueStatus?,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    when (status) {
        QueueStatus.PENDING -> {
            CircularProgressIndicator(
                modifier = modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        }
        QueueStatus.QUEUED -> {
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Default.CloudOff,
                    contentDescription = stringResource(R.string.cd_queued_offline),
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "Queued",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        QueueStatus.SYNCING -> {
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    "Syncing...",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        QueueStatus.SUCCESS -> {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = stringResource(R.string.cd_synced),
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier.size(24.dp)
            )
        }
        QueueStatus.FAILED -> {
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = stringResource(R.string.cd_failed),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                if (onRetry != null) {
                    TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("Retry", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        null -> {
            Icon(
                Icons.Default.CloudUpload,
                contentDescription = stringResource(R.string.cd_ready_to_sync),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun SyncQueueSummary(
    pendingCount: Int,
    queuedCount: Int,
    failedCount: Int,
    modifier: Modifier = Modifier
) {
    if (pendingCount == 0 && queuedCount == 0 && failedCount == 0) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            if (pendingCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("$pendingCount syncing", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (queuedCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudOff, contentDescription = "Queued offline", modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(4.dp))
                    Text("$queuedCount queued", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (failedCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, contentDescription = stringResource(R.string.cd_sync_failed), modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("$failedCount failed", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
