package com.synapse.data.repository

import com.synapse.model.CapturedContext
import com.synapse.model.Chunk

/**
 * Represents a time-ordered item in a session: either a chunk or a context.
 */
sealed class SessionItem(val timestamp: Long) {
    class ChunkItem(val chunk: Chunk, ts: Long) : SessionItem(ts)
    class ContextItem(val context: CapturedContext, ts: Long) : SessionItem(ts)
}

/**
 * A segment is a logical grouping of session items separated by context boundaries.
 * - Context-only segments: formatted via LLM when available, otherwise blockquotes
 * - Chunk-only segments: transcribed and written
 * - Context + chunk segments: Q&A flow (context is the reference, chunks are the question)
 */
data class Segment(
    val contexts: List<CapturedContext> = emptyList(),
    val chunks: List<Chunk> = emptyList()
)

/**
 * Segments a session's flat lists of chunks and contexts into logical groups
 * based on timestamp ordering. Each new context starts a new segment.
 */
object SessionSegmenter {

    fun segmentSession(
        chunks: List<Chunk>,
        contexts: List<CapturedContext>
    ): List<Segment> {
        if (chunks.isEmpty() && contexts.isEmpty()) return emptyList()

        // Merge into a single timeline sorted by timestamp
        val items = mutableListOf<SessionItem>()
        chunks.forEach { items.add(SessionItem.ChunkItem(it, it.createdAt)) }
        contexts.forEach { items.add(SessionItem.ContextItem(it, it.timestamp)) }
        items.sortBy { it.timestamp }

        val segments = mutableListOf<Segment>()
        var currentContexts = mutableListOf<CapturedContext>()
        var currentChunks = mutableListOf<Chunk>()

        for (item in items) {
            when (item) {
                is SessionItem.ContextItem -> {
                    // A new context starts a new segment if we have accumulated content
                    if (currentContexts.isNotEmpty() || currentChunks.isNotEmpty()) {
                        segments.add(Segment(currentContexts, currentChunks))
                        currentContexts = mutableListOf()
                        currentChunks = mutableListOf()
                    }
                    currentContexts.add(item.context)
                }
                is SessionItem.ChunkItem -> {
                    currentChunks.add(item.chunk)
                }
            }
        }

        // Don't forget the last segment
        if (currentContexts.isNotEmpty() || currentChunks.isNotEmpty()) {
            segments.add(Segment(currentContexts, currentChunks))
        }

        return segments
    }
}
