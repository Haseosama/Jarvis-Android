package com.jarvis.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveModelsTest {
    private fun page(vararg names: String, total: Int = names.size, next: String? = null) =
        LiveModelPage(names.toList(), total, next)

    @Test
    fun `models from every page are collected in order`() {
        val pages = mapOf(
            null to page("models/a", total = 3, next = "p2"),
            "p2" to page("models/b", "models/c", total = 4),
        )
        val result = collectLiveModels { token -> pages.getValue(token) }
        assertEquals(LiveModelsResult.Found(listOf("models/a", "models/b", "models/c"), partial = false), result)
    }

    @Test
    fun `duplicates across pages are listed once`() {
        val pages = mapOf(
            null to page("models/a", next = "p2"),
            "p2" to page("models/a", "models/b"),
        )
        val result = collectLiveModels { token -> pages.getValue(token) } as LiveModelsResult.Found
        assertEquals(listOf("models/a", "models/b"), result.names)
    }

    @Test
    fun `a project with no Live model reports how many models were checked`() {
        val pages = mapOf(
            null to page(total = 5, next = "p2"),
            "p2" to page(total = 2),
        )
        assertEquals(LiveModelsResult.NoneFound(7), collectLiveModels { token -> pages.getValue(token) })
    }

    @Test
    fun `a repeated page token stops the loop and marks the list partial`() {
        var calls = 0
        val result = collectLiveModels {
            calls++
            page("models/a", next = "same")
        } as LiveModelsResult.Found
        assertTrue(result.partial)
        assertEquals(listOf("models/a"), result.names)
        assertEquals(2, calls)
    }

    @Test
    fun `an endless list is cut at the page limit`() {
        var calls = 0
        val result = collectLiveModels { token ->
            calls++
            page("models/m$calls", next = "t$calls")
        } as LiveModelsResult.Found
        assertTrue(result.partial)
        assertEquals(MAX_MODEL_PAGES, calls)
        assertEquals(MAX_MODEL_PAGES, result.names.size)
    }

    @Test
    fun `a partial result can be empty`() {
        val result = collectLiveModels { page(next = "same") } as LiveModelsResult.Found
        assertTrue(result.partial)
        assertTrue(result.names.isEmpty())
    }

    @Test
    fun `a page keeps only models that support the Live method`() {
        val body = """
            {"models":[
              {"name":"models/gemini-live","supportedGenerationMethods":["bidiGenerateContent","countTokens"]},
              {"name":"models/gemini-text","supportedGenerationMethods":["generateContent"]},
              {"name":"models/no-methods"}
            ],"nextPageToken":"abc"}
        """.trimIndent()
        val parsed = parseLiveModelPage(body)
        assertEquals(listOf("models/gemini-live"), parsed.names)
        assertEquals(3, parsed.total)
        assertEquals("abc", parsed.nextPageToken)
    }

    @Test
    fun `an empty or missing page token means there is no next page`() {
        assertNull(parseLiveModelPage("""{"models":[]}""").nextPageToken)
        assertNull(parseLiveModelPage("""{"models":[],"nextPageToken":""}""").nextPageToken)
    }

    @Test
    fun `an API key with a space or a non printable character is rejected`() {
        assertEquals("AbC-123_x", validatedApiKey("  AbC-123_x \n"))
        assertNull(validatedApiKey("ab cd"))
        assertNull(validatedApiKey("clé-accentuée"))
        assertNull(validatedApiKey("   "))
        assertFalse(validatedApiKey("a".repeat(513)) != null)
    }
}
