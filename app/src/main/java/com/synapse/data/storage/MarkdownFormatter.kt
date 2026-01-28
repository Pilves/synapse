package com.synapse.data.storage

import com.synapse.model.Note
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formats transcription results as clean markdown for Obsidian vault storage.
 *
 * Produces clean markdown without frontmatter, suitable for appending to existing notes.
 * Notes are separated by blank lines for readability.
 */
object MarkdownFormatter {

    private const val SYNC_SEPARATOR = "---"
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /**
     * Formats a list of notes as markdown.
     *
     * Each note is output as a paragraph, with notes separated by blank lines.
     * Optionally includes a timestamp header showing when the notes were synced.
     *
     * @param notes The list of notes to format
     * @param includeTimestamp Whether to include a timestamp header (default: true)
     * @return Formatted markdown string
     */
    fun formatNotes(notes: List<Note>, includeTimestamp: Boolean = true): String {
        if (notes.isEmpty()) {
            return ""
        }

        val builder = StringBuilder()

        // Add timestamp header if requested
        if (includeTimestamp) {
            val timestamp = timestampFormat.format(Date())
            builder.append("## $timestamp")
            builder.append("\n\n")
        }

        // Format each note as a paragraph
        notes.forEachIndexed { index, note ->
            builder.append(note.text.trim())

            // Add blank line between notes, but not after the last one
            if (index < notes.size - 1) {
                builder.append("\n\n")
            }
        }

        return builder.toString()
    }

    /**
     * Formats notes with a horizontal rule separator.
     *
     * Useful for visually separating sync batches in the markdown file.
     * The separator appears before the notes, making it clear where a new
     * sync batch begins.
     *
     * @param notes The list of notes to format
     * @return Formatted markdown string with separator
     */
    fun formatWithSeparator(notes: List<Note>): String {
        if (notes.isEmpty()) {
            return ""
        }

        val builder = StringBuilder()

        // Add horizontal rule separator
        builder.append(SYNC_SEPARATOR)
        builder.append("\n\n")

        // Add the formatted notes with timestamp
        builder.append(formatNotes(notes, includeTimestamp = true))

        return builder.toString()
    }

    /**
     * Formats a single note as markdown.
     *
     * @param note The note to format
     * @return Formatted markdown string
     */
    fun formatSingleNote(note: Note): String {
        return note.text.trim()
    }

    /**
     * Formats notes as a bullet list.
     *
     * Each note becomes a list item. Useful for quick capture notes.
     *
     * @param notes The list of notes to format
     * @param includeTimestamp Whether to include a timestamp header (default: true)
     * @return Formatted markdown string with bullet points
     */
    fun formatAsBulletList(notes: List<Note>, includeTimestamp: Boolean = true): String {
        if (notes.isEmpty()) {
            return ""
        }

        val builder = StringBuilder()

        // Add timestamp header if requested
        if (includeTimestamp) {
            val timestamp = timestampFormat.format(Date())
            builder.append("## $timestamp")
            builder.append("\n\n")
        }

        // Format each note as a bullet point
        notes.forEachIndexed { index, note ->
            // Handle multi-line notes by indenting continuation lines
            val lines = note.text.trim().lines()
            builder.append("- ${lines.first()}")

            // Indent any additional lines
            lines.drop(1).forEach { line ->
                builder.append("\n  $line")
            }

            // Add newline between items, but not after the last one
            if (index < notes.size - 1) {
                builder.append("\n")
            }
        }

        return builder.toString()
    }

    /**
     * Formats notes with chunk reference comments.
     *
     * Includes HTML comments showing which chunks were used for each note.
     * Useful for debugging and tracing note sources.
     *
     * @param notes The list of notes to format
     * @return Formatted markdown string with chunk references
     */
    fun formatWithChunkReferences(notes: List<Note>): String {
        if (notes.isEmpty()) {
            return ""
        }

        val builder = StringBuilder()
        val timestamp = timestampFormat.format(Date())
        builder.append("## $timestamp")
        builder.append("\n\n")

        notes.forEachIndexed { index, note ->
            // Add chunk reference as HTML comment (invisible in rendered markdown)
            val chunkRef = note.chunksUsed.joinToString(", ")
            builder.append("<!-- chunks: $chunkRef -->")
            builder.append("\n")
            builder.append(note.text.trim())

            if (index < notes.size - 1) {
                builder.append("\n\n")
            }
        }

        return builder.toString()
    }

    /**
     * Estimates the character count of formatted output.
     *
     * Useful for checking if content will fit within size limits.
     *
     * @param notes The list of notes to estimate
     * @param includeTimestamp Whether timestamp header is included
     * @return Estimated character count
     */
    fun estimateLength(notes: List<Note>, includeTimestamp: Boolean = true): Int {
        if (notes.isEmpty()) return 0

        var length = 0

        // Timestamp header
        if (includeTimestamp) {
            length += 22 // "## yyyy-MM-dd HH:mm\n\n"
        }

        // Notes content plus separators
        notes.forEach { note ->
            length += note.text.trim().length
        }

        // Add separators between notes (2 newlines = 2 chars)
        length += (notes.size - 1) * 2

        return length
    }
}
