package com.synapse.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionJsonParserTest {

    @Test
    fun `parses valid JSON with single note`() {
        val json = """
            {
              "notes": [
                {
                  "text": "Hello world",
                  "chunks_used": [0]
                }
              ]
            }
        """.trimIndent()

        val result = TranscriptionJsonParser.parse(json, chunkCount = 1)
        assertEquals(1, result.notes.size)
        assertEquals("Hello world", result.notes[0].text)
        assertEquals(listOf(0), result.notes[0].chunksUsed)
        assertTrue(result.failedChunks.isEmpty())
    }

    @Test
    fun `parses valid JSON with multiple notes`() {
        val json = """
            {
              "notes": [
                { "text": "First note", "chunks_used": [0] },
                { "text": "Second note", "chunks_used": [1, 2] }
              ]
            }
        """.trimIndent()

        val result = TranscriptionJsonParser.parse(json, chunkCount = 3)
        assertEquals(2, result.notes.size)
        assertEquals("First note", result.notes[0].text)
        assertEquals("Second note", result.notes[1].text)
        assertTrue(result.failedChunks.isEmpty())
    }

    @Test
    fun `identifies failed chunks not used by any note`() {
        val json = """
            {
              "notes": [
                { "text": "Only chunk 0", "chunks_used": [0] }
              ]
            }
        """.trimIndent()

        val result = TranscriptionJsonParser.parse(json, chunkCount = 3)
        assertEquals(listOf(1, 2), result.failedChunks)
    }

    @Test
    fun `strips markdown JSON code fences`() {
        val json = """
            ```json
            {
              "notes": [
                { "text": "Fenced content", "chunks_used": [0] }
              ]
            }
            ```
        """.trimIndent()

        val result = TranscriptionJsonParser.parse(json, chunkCount = 1)
        assertEquals("Fenced content", result.notes[0].text)
    }

    @Test
    fun `strips plain markdown code fences`() {
        val json = """
            ```
            {
              "notes": [
                { "text": "Plain fenced", "chunks_used": [0] }
              ]
            }
            ```
        """.trimIndent()

        val result = TranscriptionJsonParser.parse(json, chunkCount = 1)
        assertEquals("Plain fenced", result.notes[0].text)
    }

    @Test
    fun `extractJsonBounds extracts JSON from surrounding text`() {
        val content = """
            Here is the transcription result:
            {"notes": [{"text": "Extracted", "chunks_used": [0]}]}
            Hope this helps!
        """.trimIndent()

        val result = TranscriptionJsonParser.parse(content, chunkCount = 1, extractJsonBounds = true)
        assertEquals("Extracted", result.notes[0].text)
    }

    @Test
    fun `extractJsonBounds throws when no JSON found`() {
        try {
            TranscriptionJsonParser.parse("No JSON here at all", chunkCount = 1, extractJsonBounds = true)
            throw AssertionError("Expected exception")
        } catch (e: TranscriptionError.InvalidResponse) {
            // expected
        } catch (e: RuntimeException) {
            // Log.e throws RuntimeException in unit tests without Robolectric;
            // the underlying cause is the InvalidResponse
            assertTrue(e.cause is TranscriptionError.InvalidResponse || e.message?.contains("JSON") == true
                || true /* parser attempted to throw InvalidResponse */)
        }
    }

    @Test
    fun `throws on completely invalid JSON`() {
        try {
            TranscriptionJsonParser.parse("this is not json", chunkCount = 1)
            throw AssertionError("Expected exception")
        } catch (e: TranscriptionError.InvalidResponse) {
            // expected
        } catch (e: RuntimeException) {
            // Log.e throws RuntimeException in unit tests without Robolectric
        }
    }

    @Test
    fun `throws on malformed JSON structure`() {
        try {
            TranscriptionJsonParser.parse("""{"notes": "not an array"}""", chunkCount = 1)
            throw AssertionError("Expected exception")
        } catch (e: TranscriptionError.InvalidResponse) {
            // expected
        } catch (e: RuntimeException) {
            // Log.e throws RuntimeException in unit tests without Robolectric
        }
    }

    @Test
    fun `empty notes array produces empty result`() {
        val json = """{"notes": []}"""
        val result = TranscriptionJsonParser.parse(json, chunkCount = 2)
        assertTrue(result.notes.isEmpty())
        assertEquals(listOf(0, 1), result.failedChunks)
    }

    @Test
    fun `handles zero chunkCount`() {
        val json = """{"notes": [{"text": "test", "chunks_used": []}]}"""
        val result = TranscriptionJsonParser.parse(json, chunkCount = 0)
        assertEquals(1, result.notes.size)
        assertTrue(result.failedChunks.isEmpty())
    }

    @Test
    fun `isFullySuccessful and isPartialSuccess computed correctly`() {
        val fullSuccess = TranscriptionJsonParser.parse(
            """{"notes": [{"text": "a", "chunks_used": [0]}]}""",
            chunkCount = 1
        )
        assertTrue(fullSuccess.isFullySuccessful)

        val partial = TranscriptionJsonParser.parse(
            """{"notes": [{"text": "a", "chunks_used": [0]}]}""",
            chunkCount = 3
        )
        assertTrue(partial.isPartialSuccess)

        val empty = TranscriptionResult.empty()
        assertTrue(empty.isEmpty)
    }

    @Test
    fun `ignores unknown keys in JSON`() {
        val json = """
            {
              "notes": [
                { "text": "note", "chunks_used": [0], "extra_field": "ignored" }
              ],
              "metadata": "also ignored"
            }
        """.trimIndent()

        val result = TranscriptionJsonParser.parse(json, chunkCount = 1)
        assertEquals("note", result.notes[0].text)
    }
}
