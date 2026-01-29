package com.synapse.api

import android.util.Log
import kotlinx.serialization.json.Json

/**
 * Shared parser for LLM transcription JSON responses.
 *
 * All four LLM services (Gemini, Claude, OpenAI, Ollama) return the same JSON structure.
 * This extracts the common parsing logic.
 */
object TranscriptionJsonParser {

    private const val TAG = "TranscriptionJsonParser"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parses the JSON transcription output from an LLM.
     *
     * @param content Raw text from the LLM (may include markdown fences)
     * @param chunkCount Total number of chunks sent, used to compute failed indices
     * @param extractJsonBounds If true, searches for the outermost `{…}` in the cleaned
     *   content before parsing. Useful for models (e.g. Ollama/llava) that sometimes
     *   embed JSON inside prose.
     */
    fun parse(
        content: String,
        chunkCount: Int,
        extractJsonBounds: Boolean = false
    ): TranscriptionResult {
        try {
            // Clean up markdown code fencing
            var cleanedContent = content
                .replace(Regex("^```json\\s*", RegexOption.MULTILINE), "")
                .replace(Regex("^```\\s*", RegexOption.MULTILINE), "")
                .replace(Regex("```$", RegexOption.MULTILINE), "")
                .trim()

            if (extractJsonBounds) {
                val jsonStart = cleanedContent.indexOf('{')
                val jsonEnd = cleanedContent.lastIndexOf('}')
                if (jsonStart == -1 || jsonEnd == -1 || jsonEnd < jsonStart) {
                    throw TranscriptionError.InvalidResponse("No valid JSON found in response")
                }
                cleanedContent = cleanedContent.substring(jsonStart, jsonEnd + 1)
            }

            val transcriptionResponse = json.decodeFromString<TranscriptionResponse>(cleanedContent)

            val notes = transcriptionResponse.notes.map { noteResponse ->
                Note(
                    text = noteResponse.text,
                    chunksUsed = noteResponse.chunksUsed
                )
            }

            val usedChunks = notes.flatMap { it.chunksUsed }.toSet()
            val failedChunks = (0 until chunkCount).filter { it !in usedChunks }

            return TranscriptionResult(notes, failedChunks)
        } catch (e: TranscriptionError) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse transcription JSON (${content.length} chars)", e)
            throw TranscriptionError.InvalidResponse("Invalid JSON from LLM: ${e.message}", e)
        }
    }
}
