package com.synapse.util

/**
 * Sanitizes LLM output before writing to vault files.
 *
 * Strips/escapes dangerous content that could be interpreted by
 * Obsidian or other markdown renderers as executable or injected content.
 */
object OutputSanitizer {

    private val YAML_FRONTMATTER_REGEX = Regex("^---\\s*\\n[\\s\\S]*?\\n---\\s*\\n", RegexOption.MULTILINE)
    private val SCRIPT_TAG_REGEX = Regex("<script[^>]*>[\\s\\S]*?</script>", setOf(RegexOption.IGNORE_CASE))
    private val HTML_TAG_REGEX = Regex("</?[a-zA-Z][a-zA-Z0-9]*[^>]*>")
    private val DATAVIEW_REGEX = Regex("```dataview[\\s\\S]*?```", RegexOption.IGNORE_CASE)
    private val TEMPLATER_REGEX = Regex("<%[\\s\\S]*?%>")
    private val INLINE_JS_REGEX = Regex("```(javascript|js)\\s*\\n[\\s\\S]*?```", RegexOption.IGNORE_CASE)

    /**
     * Sanitize LLM output for safe vault writing.
     *
     * Removes:
     * - YAML frontmatter (could override note metadata)
     * - HTML script tags
     * - Inline HTML tags
     * - Dataview code blocks
     * - Templater expressions
     * - Inline JavaScript code blocks
     */
    fun sanitize(content: String): String {
        var result = content
        result = YAML_FRONTMATTER_REGEX.replace(result, "")
        result = SCRIPT_TAG_REGEX.replace(result, "")
        result = DATAVIEW_REGEX.replace(result, "")
        result = TEMPLATER_REGEX.replace(result, "")
        result = INLINE_JS_REGEX.replace(result, "")
        result = HTML_TAG_REGEX.replace(result, "")
        return result.trim()
    }
}
