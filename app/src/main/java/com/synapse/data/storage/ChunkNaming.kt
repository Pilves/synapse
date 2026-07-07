package com.synapse.data.storage

/**
 * Chunk filename generation and parsing for [ChunkStorage].
 *
 * Filename format: session_{timestamp}_chunk_{index}.webp
 */
object ChunkNaming {

    /** Chunk filename prefix */
    const val CHUNK_PREFIX = "session_"

    /** Chunk filename middle segment */
    const val CHUNK_MIDDLE = "_chunk_"

    /** Thumbnail suffix */
    const val THUMB_SUFFIX = "_thumb"

    /**
     * Generate the filename for a chunk.
     *
     * @param sessionTimestamp The session timestamp
     * @param index The chunk index
     * @return Filename in format: session_{timestamp}_chunk_{index}.webp
     */
    fun generateChunkFilename(sessionTimestamp: String, index: Int): String {
        return "$CHUNK_PREFIX${sessionTimestamp}$CHUNK_MIDDLE${index}$StorageHelper.WEBP_EXTENSION"
    }

    /**
     * Parse a chunk filename to extract session timestamp and index.
     *
     * @param filename The chunk filename
     * @return Pair of (sessionTimestamp, index), or null if parsing fails
     */
    fun parseChunkFilename(filename: String): Pair<String, Int>? {
        if (!filename.startsWith(CHUNK_PREFIX) || !filename.endsWith(StorageHelper.WEBP_EXTENSION)) {
            return null
        }

        // Handle thumb files
        if (filename.contains(THUMB_SUFFIX)) {
            return null
        }

        val withoutExtension = filename.removeSuffix(StorageHelper.WEBP_EXTENSION)
        val chunkIndex = withoutExtension.lastIndexOf(CHUNK_MIDDLE)

        if (chunkIndex == -1) return null

        val sessionTimestamp = withoutExtension.substring(CHUNK_PREFIX.length, chunkIndex)
        val indexStr = withoutExtension.substring(chunkIndex + CHUNK_MIDDLE.length)

        return try {
            sessionTimestamp to indexStr.toInt()
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Generate the thumbnail filename for a chunk.
     *
     * @param sessionId The session ID
     * @param index The chunk index
     * @return The thumbnail filename
     */
    fun thumbnailFilename(sessionId: String, index: Int): String {
        return "${CHUNK_PREFIX}${sessionId}${CHUNK_MIDDLE}${index}${THUMB_SUFFIX}$StorageHelper.WEBP_EXTENSION"
    }
}
