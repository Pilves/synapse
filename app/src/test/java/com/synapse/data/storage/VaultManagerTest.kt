package com.synapse.data.storage

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VaultManagerTest {

    private lateinit var context: Context
    private lateinit var vaultManager: VaultManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        vaultManager = VaultManager(context)
    }

    @Test
    fun `getVaultRoot returns null when no vault configured`() {
        assertNull(vaultManager.getVaultRoot())
    }

    @Test
    fun `hasVaultConfigured returns false when no vault set`() {
        assertFalse(vaultManager.hasVaultConfigured())
    }

    @Test
    fun `hasValidPermission returns false when no vault configured`() {
        assertFalse(vaultManager.hasValidPermission())
    }

    @Test
    fun `sanitizeFilename removes illegal characters`() {
        // Test via appendToFile path — sanitizeFilename is private, but we can test
        // the public behavior indirectly. For direct tests, use reflection.
        val method = VaultManager::class.java.getDeclaredMethod("sanitizeFilename", String::class.java)
        method.isAccessible = true

        val result = method.invoke(vaultManager, "test<>:\"|?*.md") as String
        assertFalse(result.contains("<"))
        assertFalse(result.contains(">"))
        assertFalse(result.contains(":"))
        assertFalse(result.contains("\""))
        assertFalse(result.contains("|"))
        assertFalse(result.contains("?"))
        assertFalse(result.contains("*"))
        assertTrue(result.endsWith(".md"))
    }

    @Test
    fun `sanitizeFilename collapses consecutive dots`() {
        val method = VaultManager::class.java.getDeclaredMethod("sanitizeFilename", String::class.java)
        method.isAccessible = true

        val result = method.invoke(vaultManager, "test...file.md") as String
        assertFalse(result.contains(".."))
    }

    @Test
    fun `sanitizeFilename trims leading dots`() {
        val method = VaultManager::class.java.getDeclaredMethod("sanitizeFilename", String::class.java)
        method.isAccessible = true

        val result = method.invoke(vaultManager, ".hidden.md") as String
        assertFalse(result.startsWith("."))
    }

    @Test
    fun `sanitizeFilename returns untitled for blank input`() {
        val method = VaultManager::class.java.getDeclaredMethod("sanitizeFilename", String::class.java)
        method.isAccessible = true

        val result = method.invoke(vaultManager, "   ") as String
        assertEquals("untitled.md", result)
    }

    @Test
    fun `sanitizeFilename truncates long filenames preserving extension`() {
        val method = VaultManager::class.java.getDeclaredMethod("sanitizeFilename", String::class.java)
        method.isAccessible = true

        val longName = "a".repeat(300) + ".md"
        val result = method.invoke(vaultManager, longName) as String
        assertTrue(result.length <= 200)
        assertTrue(result.endsWith(".md"))
    }
}
