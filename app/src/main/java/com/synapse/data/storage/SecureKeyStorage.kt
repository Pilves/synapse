package com.synapse.data.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.first

/**
 * Stores API keys in EncryptedSharedPreferences backed by AES256-GCM.
 *
 * Keys are indexed by provider name (e.g. "gemini", "claude", "openai").
 */
class SecureKeyStorage(context: Context) {

    companion object {
        private const val TAG = "SecureKeyStorage"
        private const val PREFS_NAME = "synapse_secure_keys"
        private const val KEY_PREFIX = "api_key_"
        private const val MIGRATION_DONE_KEY = "migration_done"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Save an API key for the given provider.
     */
    fun saveKey(provider: String, key: String) {
        prefs.edit { putString("$KEY_PREFIX$provider", key) }
    }

    /**
     * Retrieve the API key for the given provider, or null if absent.
     */
    fun getKey(provider: String): String? {
        return prefs.getString("$KEY_PREFIX$provider", null)
    }

    /**
     * Delete the API key for the given provider.
     */
    fun deleteKey(provider: String) {
        prefs.edit { remove("$KEY_PREFIX$provider") }
    }

    /**
     * One-time migration: reads API keys from the plain DataStore,
     * writes them into encrypted storage, then removes the old entries.
     *
     * Call this on app start. It is idempotent.
     */
    suspend fun migrateFromDataStore(dataStore: DataStore<Preferences>) {
        if (prefs.getBoolean(MIGRATION_DONE_KEY, false)) return

        try {
            val snapshot = dataStore.data.first()

            // Migrate keys and remove plaintext originals immediately.
            // Note: JVM strings are immutable so we cannot zero the in-memory
            // copies, but removing them from DataStore eliminates the on-disk risk.
            snapshot[stringPreferencesKey("api_key")]
                ?.takeIf { it.isNotBlank() }
                ?.let { saveKey("legacy", it) }

            snapshot[stringPreferencesKey("transcription_api_key")]
                ?.takeIf { it.isNotBlank() }
                ?.let { saveKey("transcription", it) }

            snapshot[stringPreferencesKey("answering_api_key")]
                ?.takeIf { it.isNotBlank() }
                ?.let { saveKey("answering", it) }

            // Remove old plain-text keys from DataStore
            dataStore.edit { prefs ->
                prefs.remove(stringPreferencesKey("api_key"))
                prefs.remove(stringPreferencesKey("transcription_api_key"))
                prefs.remove(stringPreferencesKey("answering_api_key"))
            }

            prefs.edit { putBoolean(MIGRATION_DONE_KEY, true) }
            Log.d(TAG, "API key migration to encrypted storage completed")
        } catch (e: Exception) {
            Log.e(TAG, "API key migration failed", e)
        }
    }
}
