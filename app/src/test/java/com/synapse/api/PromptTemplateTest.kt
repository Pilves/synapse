package com.synapse.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplateTest {

    // --- buildPrompt ---

    @Test
    fun `buildPrompt substitutes cleanup_enabled true`() {
        val prompt = PromptTemplate.buildPrompt(cleanupEnabled = true, advancedFormatting = false)
        assertTrue(prompt.contains("true"))
        assertTrue(prompt.contains("false"))
        assertTrue(!prompt.contains("{cleanup_enabled}"))
        assertTrue(!prompt.contains("{advanced_formatting}"))
    }

    @Test
    fun `buildPrompt substitutes both variables`() {
        val prompt = PromptTemplate.buildPrompt(cleanupEnabled = false, advancedFormatting = true)
        // The order in the template: cleanup_enabled first, then advanced_formatting
        assertTrue(!prompt.contains("{cleanup_enabled}"))
        assertTrue(!prompt.contains("{advanced_formatting}"))
    }

    @Test
    fun `buildPrompt uses custom template when provided`() {
        val custom = "Cleanup: {cleanup_enabled}, Format: {advanced_formatting}"
        val prompt = PromptTemplate.buildPrompt(
            cleanupEnabled = true,
            advancedFormatting = false,
            customTemplate = custom
        )
        assertEquals("Cleanup: true, Format: false", prompt)
    }

    @Test
    fun `buildPrompt uses default template when custom is null`() {
        val prompt = PromptTemplate.buildPrompt(
            cleanupEnabled = true,
            advancedFormatting = true,
            customTemplate = null
        )
        assertTrue(prompt.contains("You transcribe handwritten notes"))
    }

    // --- buildChunkContext ---

    @Test
    fun `buildChunkContext with empty list returns no chunks message`() {
        val result = PromptTemplate.buildChunkContext(emptyList())
        assertEquals("No image chunks provided.", result)
    }

    @Test
    fun `buildChunkContext describes single chunk`() {
        val chunks = listOf(
            ChunkData(image = ByteArray(0), timestampSeconds = 5.0f, index = 0)
        )
        val result = PromptTemplate.buildChunkContext(chunks)
        assertTrue(result.contains("1 handwritten note chunk"))
        assertTrue(result.contains("Chunk 0"))
        assertTrue(result.contains("5.0s"))
    }

    @Test
    fun `buildChunkContext describes multiple chunks`() {
        val chunks = listOf(
            ChunkData(image = ByteArray(0), timestampSeconds = 1.0f, index = 0),
            ChunkData(image = ByteArray(0), timestampSeconds = 3.5f, index = 1),
            ChunkData(image = ByteArray(0), timestampSeconds = 7.2f, index = 2)
        )
        val result = PromptTemplate.buildChunkContext(chunks)
        assertTrue(result.contains("3 handwritten note chunk"))
        assertTrue(result.contains("Chunk 0"))
        assertTrue(result.contains("Chunk 1"))
        assertTrue(result.contains("Chunk 2"))
        assertTrue(result.contains("7.2s"))
    }

    // --- validateTemplate ---

    @Test
    fun `validateTemplate returns empty for valid template`() {
        val template = "Some text {cleanup_enabled} more text {advanced_formatting}"
        val errors = PromptTemplate.validateTemplate(template)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `validateTemplate detects missing cleanup_enabled`() {
        val template = "Only has {advanced_formatting}"
        val errors = PromptTemplate.validateTemplate(template)
        assertEquals(1, errors.size)
        assertEquals("{cleanup_enabled}", errors[0])
    }

    @Test
    fun `validateTemplate detects missing advanced_formatting`() {
        val template = "Only has {cleanup_enabled}"
        val errors = PromptTemplate.validateTemplate(template)
        assertEquals(1, errors.size)
        assertEquals("{advanced_formatting}", errors[0])
    }

    @Test
    fun `validateTemplate detects both missing placeholders`() {
        val template = "No placeholders at all"
        val errors = PromptTemplate.validateTemplate(template)
        assertEquals(2, errors.size)
        assertTrue("{cleanup_enabled}" in errors)
        assertTrue("{advanced_formatting}" in errors)
    }

    @Test
    fun `validateTemplate passes for default template`() {
        val errors = PromptTemplate.validateTemplate(PromptTemplate.DEFAULT_TEMPLATE)
        assertTrue(errors.isEmpty())
    }

    // --- Constants ---

    @Test
    fun `SYSTEM_PROMPT is not empty`() {
        assertTrue(PromptTemplate.SYSTEM_PROMPT.isNotBlank())
        assertTrue(PromptTemplate.SYSTEM_PROMPT.contains("handwriting"))
    }

    @Test
    fun `MINIMAL_TEMPLATE contains JSON structure`() {
        assertTrue(PromptTemplate.MINIMAL_TEMPLATE.contains("notes"))
        assertTrue(PromptTemplate.MINIMAL_TEMPLATE.contains("chunks_used"))
    }

    @Test
    fun `DEFAULT_TEMPLATE contains required JSON output format`() {
        assertTrue(PromptTemplate.DEFAULT_TEMPLATE.contains("\"notes\""))
        assertTrue(PromptTemplate.DEFAULT_TEMPLATE.contains("\"chunks_used\""))
    }
}
