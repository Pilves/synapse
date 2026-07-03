package com.synapse.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.synapse.model.CostEstimate
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
