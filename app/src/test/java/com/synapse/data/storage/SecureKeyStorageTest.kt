package com.synapse.data.storage

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SecureKeyStorageTest {

    private lateinit var context: Context
    private lateinit var storage: SecureKeyStorage

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        storage = SecureKeyStorage(context)
    }

    @Test
    fun `getKey returns null for non-existent key`() {
        assertNull(storage.getKey("nonexistent"))
    }

    @Test
    fun `saveKey and getKey roundtrip`() {
        storage.saveKey("test_provider", "test-api-key-12345")
        assertEquals("test-api-key-12345", storage.getKey("test_provider"))
    }

    @Test
    fun `saveKey overwrites existing key`() {
        storage.saveKey("provider", "old-key")
        storage.saveKey("provider", "new-key")
        assertEquals("new-key", storage.getKey("provider"))
    }

    @Test
    fun `deleteKey removes stored key`() {
        storage.saveKey("provider", "key-to-delete")
        storage.deleteKey("provider")
        assertNull(storage.getKey("provider"))
    }

    @Test
    fun `deleteKey is safe for non-existent key`() {
        storage.deleteKey("nonexistent")
        // Should not throw
    }

    @Test
    fun `multiple providers can store keys independently`() {
        storage.saveKey("gemini", "gemini-key")
        storage.saveKey("claude", "claude-key")
        storage.saveKey("openai", "openai-key")

        assertEquals("gemini-key", storage.getKey("gemini"))
        assertEquals("claude-key", storage.getKey("claude"))
        assertEquals("openai-key", storage.getKey("openai"))
    }

    @Test
    fun `deleting one provider does not affect others`() {
        storage.saveKey("gemini", "gemini-key")
        storage.saveKey("claude", "claude-key")

        storage.deleteKey("gemini")

        assertNull(storage.getKey("gemini"))
        assertEquals("claude-key", storage.getKey("claude"))
    }

    @Test
    fun `keys persist across storage instances`() {
        storage.saveKey("test", "persistent-key")

        val storage2 = SecureKeyStorage(context)
        assertEquals("persistent-key", storage2.getKey("test"))
    }
}
