package com.synapse.util

import com.synapse.api.LlmProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyValidatorTest {

    @Test
    fun `validateFormat rejects short keys`() {
        val result = ApiKeyValidator.validateFormat("short")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("too short"))
    }

    @Test
    fun `validateFormat rejects keys with spaces`() {
        val result = ApiKeyValidator.validateFormat("sk-ant-api03-this has spaces in it for sure")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("spaces"))
    }

    @Test
    fun `validateFormat accepts valid Claude key`() {
        val result = ApiKeyValidator.validateFormat("sk-ant-api03-" + "a".repeat(40))
        assertTrue(result.isValid)
    }

    @Test
    fun `validateFormat rejects short Claude key`() {
        val result = ApiKeyValidator.validateFormat("sk-ant-api03-shortkey")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("incomplete"))
    }

    @Test
    fun `validateFormat accepts valid OpenAI key`() {
        val result = ApiKeyValidator.validateFormat("sk-" + "a".repeat(45))
        assertTrue(result.isValid)
    }

    @Test
    fun `validateFormat rejects short OpenAI key`() {
        val result = ApiKeyValidator.validateFormat("sk-" + "a".repeat(20))
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("incomplete"))
    }

    @Test
    fun `validateFormat accepts valid Gemini-style key`() {
        val key = "AIza" + "a".repeat(30)
        val result = ApiKeyValidator.validateFormat(key)
        assertTrue(result.isValid)
    }

    @Test
    fun `validateFormat accepts generic 20+ char key`() {
        val result = ApiKeyValidator.validateFormat("a".repeat(20))
        assertTrue(result.isValid)
    }

    @Test
    fun `detectProvider identifies Claude`() {
        assertEquals("claude", ApiKeyValidator.detectProvider("sk-ant-api03-abc123"))
    }

    @Test
    fun `detectProvider identifies OpenAI`() {
        assertEquals("openai", ApiKeyValidator.detectProvider("sk-abc123def456"))
    }

    @Test
    fun `detectProvider identifies Gemini`() {
        assertEquals("gemini", ApiKeyValidator.detectProvider("AIzaSyABC123"))
    }

    @Test
    fun `detectProvider defaults to gemini for unknown prefix`() {
        assertEquals("gemini", ApiKeyValidator.detectProvider("unknown-key-format"))
    }

    @Test
    fun `validateForProvider allows blank key for Ollama`() {
        val result = ApiKeyValidator.validateForProvider("", LlmProvider.OLLAMA)
        assertTrue(result.isValid)
    }

    @Test
    fun `validateForProvider rejects blank key for Claude`() {
        val result = ApiKeyValidator.validateForProvider("", LlmProvider.CLAUDE)
        assertFalse(result.isValid)
    }

    @Test
    fun `validateForProvider validates Claude prefix`() {
        val result = ApiKeyValidator.validateForProvider("wrong-prefix", LlmProvider.CLAUDE)
        assertFalse(result.isValid)
    }

    @Test
    fun `validateForProvider validates OpenAI prefix`() {
        val result = ApiKeyValidator.validateForProvider("wrong-prefix", LlmProvider.OPENAI)
        assertFalse(result.isValid)
    }
}
