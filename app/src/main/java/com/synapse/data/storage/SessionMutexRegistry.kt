package com.synapse.data.storage

import kotlinx.coroutines.sync.Mutex

/**
 * Per-session mutex bookkeeping for [ChunkStorage].
 *
 * Holds a bounded map of per-session mutexes. Unlike an LRU with
 * removeEldestEntry, this never auto-evicts mutexes; instead cleanup happens
 * after operations complete via [prune]. All access to the map is guarded by a
 * single monitor so lock granularity matches the original inline implementation.
 */
class SessionMutexRegistry(private val maxEntries: Int = 100) {

    /** Mutex map for per-session locking. Cleaned up manually to avoid race conditions. */
    private val sessionMutexes = LinkedHashMap<String, Mutex>(16, 0.75f, true)
    private val mutexMapLock = Any()

    /**
     * Get or create a mutex for a specific session.
     */
    fun getOrCreate(sessionId: String): Mutex {
        synchronized(mutexMapLock) {
            return sessionMutexes.getOrPut(sessionId) { Mutex() }
        }
    }

    /**
     * Prune unlocked mutexes when the map exceeds [maxEntries].
     * Only removes mutexes that are not currently locked, iterating from
     * eldest to newest (LinkedHashMap access order) until we are at the limit.
     */
    fun prune() {
        synchronized(mutexMapLock) {
            if (sessionMutexes.size <= maxEntries) return
            val iterator = sessionMutexes.entries.iterator()
            while (iterator.hasNext() && sessionMutexes.size > maxEntries) {
                val entry = iterator.next()
                if (!entry.value.isLocked) {
                    iterator.remove()
                }
            }
        }
    }

    /**
     * Removes the session mutex from the map if it is present and not currently
     * locked. Returns true if a mutex was removed.
     */
    fun removeIfUnlocked(sessionId: String): Boolean {
        synchronized(mutexMapLock) {
            val mutex = sessionMutexes[sessionId]
            if (mutex != null && !mutex.isLocked) {
                sessionMutexes.remove(sessionId)
                return true
            }
            return false
        }
    }

    /**
     * Unconditionally removes the session mutex from the map.
     */
    fun remove(sessionId: String) {
        synchronized(mutexMapLock) { sessionMutexes.remove(sessionId) }
    }
}
