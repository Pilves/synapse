package com.synapse.data.storage

import kotlinx.serialization.json.Json

/**
 * Shared Json instance for storage serialization.
 */
object StorageJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
