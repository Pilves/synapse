package com.synapse.data.storage

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ChunkStorageTest {

    private lateinit var context: Context
    private lateinit var storage: ChunkStorage

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        storage = ChunkStorage(context)
    }

    // --- Filename generation ---

    @Test
    fun `generateChunkFilename produces non-empty filename`() {
        val filename = storage.generateChunkFilename("sess123", 0)
        assertTrue(filename.isNotBlank())
        assertTrue(filename.contains("sess123"))
    }

    @Test
    fun `generateChunkFilename handles different indices`() {
        val f0 = storage.generateChunkFilename("s1", 0)
        val f1 = storage.generateChunkFilename("s1", 1)
        val f5 = storage.generateChunkFilename("s1", 5)

        // Each filename should be unique for different indices
        assertTrue(f0 != f1)
        assertTrue(f1 != f5)
    }

    // --- Filename parsing ---

    @Test
    fun `parseChunkFilename returns valid data for known format`() {
        // Use the canonical filename format directly
        val result = storage.parseChunkFilename("session_sess123_chunk_3.webp")
        assertNotNull(result)
        assertEquals("sess123", result!!.first)
        assertEquals(3, result.second)
    }

    @Test
    fun `parseChunkFilename returns null for invalid format`() {
        assertNull(storage.parseChunkFilename("invalid_file.webp"))
    }

    @Test
    fun `parseChunkFilename returns null for wrong extension`() {
        assertNull(storage.parseChunkFilename("session_s1_chunk_0.png"))
    }

    // --- Path generation ---

    @Test
    fun `getChunkPath returns path containing session ID`() {
        val path = storage.getChunkPath("s1", 0)
        assertTrue(path.contains("s1"))
        assertTrue(path.isNotBlank())
    }

    @Test
    fun `getChunkPath with chunkId matches index variant`() {
        val path1 = storage.getChunkPath("s1", 0)
        val path2 = storage.getChunkPath("s1", "s1_0")
        assertEquals(path1, path2)
    }

    // --- Corruption marking ---

    @Test
    fun `isMarkedAsCorrupted returns false for clean file`() {
        val chunksDir = File(context.cacheDir, "chunks")
        chunksDir.mkdirs()
        val file = File(chunksDir, "test.webp")
        file.createNewFile()
        assertTrue(!storage.isMarkedAsCorrupted(file.absolutePath))
    }

    @Test
    fun `isMarkedAsCorrupted returns true when marker exists`() {
        val chunksDir = File(context.cacheDir, "chunks")
        chunksDir.mkdirs()
        val file = File(chunksDir, "test.webp")
        file.createNewFile()
        File(file.absolutePath + ".corrupted").createNewFile()
        assertTrue(storage.isMarkedAsCorrupted(file.absolutePath))
    }

    // --- Cleanup ---

    @Test
    fun `cleanupSessionMutex does not throw`() {
        storage.cleanupSessionMutex("nonexistent")
    }
}
