package com.jarvis.android.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateToolTest {
    @Test
    fun `the prompt names the target language and, when given, the source`() {
        val p = buildTranslatePrompt("Bonjour", "anglais", "")
        assertTrue(p.contains("en anglais"))
        assertTrue(p.contains("Bonjour"))
        assertTrue(!p.contains("depuis"))
        val withSource = buildTranslatePrompt("Bonjour", "anglais", "français")
        assertTrue(withSource.contains("depuis le français"))
    }

    @Test
    fun `the response text is extracted and stray quotes trimmed`() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"\"Hello\""}]}}]}"""
        assertEquals("Hello", parseTranslateResponse(body))
    }

    @Test
    fun `a response with no candidate yields no translation`() {
        assertNull(parseTranslateResponse("""{"candidates":[]}"""))
        assertNull(parseTranslateResponse("{}"))
    }
}
