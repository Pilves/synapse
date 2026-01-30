package com.synapse.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputSanitizerTest {

    @Test
    fun `sanitize preserves plain text`() {
        val input = "Hello world, this is a simple note."
        assertEquals(input, OutputSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize preserves markdown formatting`() {
        val input = "# Heading\n\n- bullet\n- **bold**\n\n`code`"
        assertEquals(input, OutputSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize strips YAML frontmatter`() {
        val input = "---\ntitle: Injected\ntags: [hack]\n---\nActual content"
        val result = OutputSanitizer.sanitize(input)
        assertFalse(result.contains("---"))
        assertFalse(result.contains("title:"))
        assertTrue(result.contains("Actual content"))
    }

    @Test
    fun `sanitize strips script tags`() {
        val input = "Normal text <script>alert('xss')</script> more text"
        val result = OutputSanitizer.sanitize(input)
        assertFalse(result.contains("script"))
        assertFalse(result.contains("alert"))
        assertTrue(result.contains("Normal text"))
        assertTrue(result.contains("more text"))
    }

    @Test
    fun `sanitize strips HTML tags`() {
        val input = "Text with <div class=\"evil\">html</div> inside"
        val result = OutputSanitizer.sanitize(input)
        assertFalse(result.contains("<div"))
        assertFalse(result.contains("</div>"))
        assertTrue(result.contains("html"))
    }

    @Test
    fun `sanitize strips dataview blocks`() {
        val input = "Before\n```dataview\nLIST FROM #tag\n```\nAfter"
        val result = OutputSanitizer.sanitize(input)
        assertFalse(result.contains("dataview"))
        assertFalse(result.contains("LIST FROM"))
        assertTrue(result.contains("Before"))
        assertTrue(result.contains("After"))
    }

    @Test
    fun `sanitize strips templater expressions`() {
        val input = "Note: <% tp.file.title %> is here"
        val result = OutputSanitizer.sanitize(input)
        assertFalse(result.contains("<%"))
        assertFalse(result.contains("tp.file"))
        assertTrue(result.contains("Note:"))
    }

    @Test
    fun `sanitize strips inline JavaScript code blocks`() {
        val input = "Code:\n```javascript\nconsole.log('evil')\n```\nEnd"
        val result = OutputSanitizer.sanitize(input)
        assertFalse(result.contains("console.log"))
        assertTrue(result.contains("Code:"))
        assertTrue(result.contains("End"))
    }

    @Test
    fun `sanitize strips JS shorthand code blocks`() {
        val input = "```js\nalert(1)\n```"
        val result = OutputSanitizer.sanitize(input)
        assertFalse(result.contains("alert"))
    }

    @Test
    fun `sanitize trims whitespace`() {
        val input = "  \n  content  \n  "
        assertEquals("content", OutputSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize handles empty input`() {
        assertEquals("", OutputSanitizer.sanitize(""))
    }

    @Test
    fun `sanitize handles combined injection attempts`() {
        val input = """---
title: hacked
---
<script>alert('xss')</script>
Normal content
<% tp.file.rename('malware') %>
```dataview
TABLE file.name FROM "/"
```
<div onclick="evil()">click me</div>
""".trimIndent()

        val result = OutputSanitizer.sanitize(input)
        assertFalse(result.contains("script"))
        assertFalse(result.contains("title: hacked"))
        assertFalse(result.contains("tp.file"))
        assertFalse(result.contains("dataview"))
        assertFalse(result.contains("onclick"))
        assertTrue(result.contains("Normal content"))
        assertTrue(result.contains("click me"))
    }
}
