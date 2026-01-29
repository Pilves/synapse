package com.synapse.util

import com.synapse.model.CapturedContext
import com.synapse.api.TranscribedNote

object OutputFormatter {

    fun formatOutput(
        notes: List<TranscribedNote>,
        contexts: List<CapturedContext> = emptyList(),
        includeContextInOutput: Boolean = true
    ): String {
        val builder = StringBuilder()

        notes.forEach { note ->
            if (includeContextInOutput && note.contextsUsed.isNotEmpty()) {
                val usedContexts = contexts.filter { it.id in note.contextsUsed }

                usedContexts.forEach { context ->
                    when (context) {
                        is CapturedContext.AutoContext -> {
                            context.sourceUrl?.let { url ->
                                builder.appendLine("> Source: $url")
                            }
                        }
                        is CapturedContext.SelectedText -> {
                            context.sourceUrl?.let { url ->
                                builder.appendLine("> Source: $url")
                            }
                            builder.appendLine("> \"${context.text}\"")
                        }
                        is CapturedContext.RegionText -> {
                            builder.appendLine("> \"${context.text}\"")
                        }
                        is CapturedContext.RegionImage -> {
                            builder.appendLine("> [Image: ${context.description ?: "captured region"}]")
                        }
                    }
                }
                builder.appendLine()
            }

            builder.appendLine(note.text)
            builder.appendLine()
        }

        return builder.toString().trimEnd()
    }
}
