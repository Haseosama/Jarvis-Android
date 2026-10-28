package com.jarvis.android.wearable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WatchProtocolTest {
    @Test fun `the watch's messages are read`() {
        assertEquals(WatchRequest.Hello, parseWatchMessage("HELLO"))
        val day = parseWatchMessage("P:day") as WatchRequest.Ask
        assertTrue(day.question.startsWith("Qu’est-ce que j’ai aujourd’hui"))
        assertEquals(WatchRequest.Ask("Il pleut ?"), parseWatchMessage(" Q: Il pleut ? "))
        assertNull(parseWatchMessage("P:inconnu"))
        assertNull(parseWatchMessage("Q:  "))
        assertNull(parseWatchMessage("bonjour"))
    }

    @Test fun `an answer goes to the watch in parts under the limit, without Markdown`() {
        val answer = "**Météo** : il fait beau à Paris, 18 °C. ".repeat(40)
        val messages = watchAnswerMessages(answer, maxBytes = 120, maxChars = 400)
        assertTrue(messages.size > 1)
        val n = messages.size
        val text = StringBuilder()
        messages.forEachIndexed { i, m ->
            val prefix = "A:${i + 1}/$n:"
            assertTrue(m, m.startsWith(prefix))
            val part = m.removePrefix(prefix)
            assertTrue(part.toByteArray(Charsets.UTF_8).size <= 120)
            text.append(part)
        }
        assertTrue(!text.contains("**"))
        assertTrue(text.endsWith("(La suite est sur le téléphone.)"))
        assertEquals(listOf("A:1/1:Court."), watchAnswerMessages("Court."))
    }

    @Test fun `a text is cut into UTF-8 pieces at spaces and never inside a character`() {
        val text = "é😀 ".repeat(50) + "fin"
        val parts = splitUtf8(text, 16)
        assertEquals(text, parts.joinToString(""))
        parts.forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 16) }
        assertEquals(listOf("abcd", "efgh"), splitUtf8("abcdefgh", 4))
        assertEquals(emptyList<String>(), splitUtf8("", 8))
    }

    @Test fun `the watch app's buttons are the phone's presets`() {
        val page = File("../watch/entry/src/main/js/default/pages/index/index.js").readText()
        val watchIds = Regex("""this\.ask\('(\w+)'\)""").findAll(page).map { it.groupValues[1] }.toSet()
        assertEquals(watchPresets().map { it.id }.toSet(), watchIds)
    }

    @Test fun `a signing fingerprint is upper-case hex without colons`() {
        val sha = hexSha256("abc".toByteArray())
        assertEquals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", sha)
    }
}
