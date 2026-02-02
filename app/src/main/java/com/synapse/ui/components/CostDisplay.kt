package com.synapse.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.synapse.model.CostEstimate
import com.synapse.model.UsageStats
import java.util.Locale

@Composable
fun SyncCostBanner(
    costEstimate: CostEstimate,
    modifier: Modifier = Modifier
) {
    if (costEstimate.estimatedCost > 0) {
        Text(
            text = "Estimated cost: $${String.format(Locale.US, "%.4f", costEstimate.estimatedCost)} (${costEstimate.model})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
    } else {
        Text(
            text = "Free (${costEstimate.model})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier
        )
    }
}

@Composable
fun UsageStatsCard(
    stats: UsageStats,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Usage", style = MaterialTheme.typography.titleMedium)

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("This month:", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "$${String.format(Locale.US, "%.2f", stats.monthlyCost)} (${stats.monthlySyncs} syncs)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("All time:", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "$${String.format(Locale.US, "%.2f", stats.totalCost)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
