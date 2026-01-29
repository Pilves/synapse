package com.synapse.data.cost

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.synapse.model.UsageStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar
import java.util.TimeZone

class UsageTracker(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val TOTAL_COST_KEY = doublePreferencesKey("usage_total_cost")
        private val MONTHLY_COST_KEY = doublePreferencesKey("usage_monthly_cost")
        private val MONTHLY_SYNCS_KEY = intPreferencesKey("usage_monthly_syncs")
        private val MONTH_KEY = intPreferencesKey("usage_current_month")
    }

    val usageStats: Flow<UsageStats> = dataStore.data.map { prefs ->
        val currentMonth = Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(Calendar.MONTH)
        val storedMonth = prefs[MONTH_KEY] ?: currentMonth

        if (currentMonth != storedMonth) {
            checkMonthReset()
            UsageStats(
                totalCost = prefs[TOTAL_COST_KEY] ?: 0.0,
                monthlyCost = 0.0,
                monthlySyncs = 0
            )
        } else {
            UsageStats(
                totalCost = prefs[TOTAL_COST_KEY] ?: 0.0,
                monthlyCost = prefs[MONTHLY_COST_KEY] ?: 0.0,
                monthlySyncs = prefs[MONTHLY_SYNCS_KEY] ?: 0
            )
        }
    }

    /**
     * Checks if the month has changed and persists the reset of monthly counters.
     */
    suspend fun checkMonthReset() {
        dataStore.edit { prefs ->
            val currentMonth = Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(Calendar.MONTH)
            val storedMonth = prefs[MONTH_KEY] ?: currentMonth

            if (currentMonth != storedMonth) {
                prefs[MONTHLY_COST_KEY] = 0.0
                prefs[MONTHLY_SYNCS_KEY] = 0
                prefs[MONTH_KEY] = currentMonth
            }
        }
    }

    suspend fun recordSync(cost: Double) {
        dataStore.edit { prefs ->
            val currentMonth = Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(Calendar.MONTH)
            val storedMonth = prefs[MONTH_KEY] ?: currentMonth

            if (currentMonth != storedMonth) {
                prefs[MONTHLY_COST_KEY] = cost
                prefs[MONTHLY_SYNCS_KEY] = 1
                prefs[MONTH_KEY] = currentMonth
            } else {
                prefs[MONTHLY_COST_KEY] = (prefs[MONTHLY_COST_KEY] ?: 0.0) + cost
                prefs[MONTHLY_SYNCS_KEY] = (prefs[MONTHLY_SYNCS_KEY] ?: 0) + 1
            }

            prefs[TOTAL_COST_KEY] = (prefs[TOTAL_COST_KEY] ?: 0.0) + cost
        }
    }

    suspend fun resetMonthlyStats() {
        dataStore.edit { prefs ->
            prefs[MONTHLY_COST_KEY] = 0.0
            prefs[MONTHLY_SYNCS_KEY] = 0
            prefs[MONTH_KEY] = Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(Calendar.MONTH)
        }
    }
}
