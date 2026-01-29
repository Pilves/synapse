package com.synapse.data.destination

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.synapse.model.Destination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class DestinationRepository(
    private val dataStore: DataStore<Preferences>,
    private val destinations: Map<String, Destination>
) {
    companion object {
        private val MAIN_DESTINATION_KEY = stringPreferencesKey("main_destination")
    }

    private val _mainDestination = MutableStateFlow("clipboard")
    val mainDestination: StateFlow<String> = _mainDestination

    suspend fun init() {
        val saved = dataStore.data.map { prefs ->
            prefs[MAIN_DESTINATION_KEY] ?: "clipboard"
        }.first()
        _mainDestination.value = saved
    }

    fun getDestination(id: String): Destination? = destinations[id]

    fun getAllDestinations(): List<Destination> = destinations.values.toList()

    fun getConfiguredDestinations(): List<Destination> {
        return destinations.values.filter { !it.requiresAuth }
    }

    suspend fun setMainDestination(id: String) {
        dataStore.edit { prefs ->
            prefs[MAIN_DESTINATION_KEY] = id
        }
        _mainDestination.value = id
    }
}
