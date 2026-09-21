package com.jarvis.android.memory

import com.jarvis.android.core.ConversationMessage
import com.jarvis.android.core.ConversationRole
import com.jarvis.android.rest.SavedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionTranscriptsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 24L * 3_600_000
    private val t0 = 1_800_000_000_000L
    private fun user(t: String) = ConversationMessage(ConversationRole.USER, t, complete = true)
    private fun bot(t: String) = ConversationMessage(ConversationRole.ASSISTANT, t, complete = true)
    private fun sys(t: String) = ConversationMessage(ConversationRole.SYSTEM, t, complete = true)

    private fun store(now: () -> Long = { t0 }) = SessionTranscripts(tmp.newFile(), now)

    @Test fun `what was said is kept, the announcements of the system are not`() {
        val s = store()
        s.upsert(t0, listOf(user("Quelle heure est-il ?"), sys("rappel : réunion"), bot("Il est dix heures.")))
        val saved = s.latest()!!
        assertEquals(t0, saved.id)
        assertEquals(listOf("USER", "ASSISTANT"), saved.messages.map { it.role })
        assertEquals(listOf(ConversationRole.USER, ConversationRole.ASSISTANT), toMessages(saved).map { it.role })
    }

    @Test fun `a session with no word of the user is not kept`() {
        val s = store()
        s.upsert(t0, listOf(bot("Bonjour, je vous écoute.")))
        assertNull(s.latest())
        assertFalse(worthKeeping(toSaved(listOf(bot("seul")))))
    }

    @Test fun `saving a session again replaces it, and the latest comes first`() {
        val s = store()
        s.upsert(t0, listOf(user("un")))
        s.upsert(t0 + 1000, listOf(user("deux")))
        s.upsert(t0, listOf(user("un"), bot("réponse"), user("trois")))
        val all = s.list()
        assertEquals(listOf(t0 + 1000, t0), all.map { it.id })
        assertEquals(3, all.last().messages.size)
    }

    @Test fun `a long message is shortened`() {
        val s = store()
        s.upsert(t0, listOf(user("x".repeat(9000))))
        assertEquals(MAX_KEPT_MESSAGE_CHARS, s.latest()!!.messages.single().text.length)
    }

    @Test fun `only the last sessions are kept, and none older than the limit`() {
        val sessions = (0 until 40).map { SavedSession(t0 - it * day, listOf(SavedMessage("USER", "m$it"))) }
        val kept = trimSessions(sessions, t0)
        assertTrue(kept.size <= MAX_KEPT_SESSIONS)
        assertTrue(kept.all { t0 - it.id <= MAX_KEPT_AGE_MS })
        assertEquals(t0, kept.last().id)              // the latest is there
        val big = (0 until 5).map { SavedSession(t0 - it * 1000, listOf(SavedMessage("USER", "x".repeat(150_000)))) }
        val trimmed = trimSessions(big, t0)
        assertTrue(trimmed.sumOf { s -> s.messages.sumOf { it.text.length } } <= MAX_KEPT_CHARS)
        assertTrue(trimmed.isNotEmpty())
    }

    @Test fun `erasing removes everything, and a damaged file is an empty history`() {
        val file = tmp.newFile()
        val s = SessionTranscripts(file) { t0 }
        s.upsert(t0, listOf(user("bonjour")))
        assertNotNull(s.latest())
        s.clear()
        assertTrue(s.list().isEmpty())
        file.writeText("pas du json")
        assertTrue(s.list().isEmpty())
        s.upsert(t0, listOf(user("ça repart")))
        assertEquals("ça repart", s.latest()!!.messages.single().text)
    }

    @Test fun `the prompt recalls the last sessions as data, not as instructions`() {
        val sessions = listOf(
            SavedSession(t0 - 3 * day, listOf(SavedMessage("USER", "vieux"), SavedMessage("ASSISTANT", "vieille réponse"))),
            SavedSession(t0 - 2 * day, listOf(SavedMessage("USER", "avant-hier"))),
            SavedSession(t0 - day, listOf(SavedMessage("USER", "hier je voulais réserver un train"), SavedMessage("ASSISTANT", "Pour quelle date ?"))),
        )
        val block = formatTranscriptsForPrompt(sessions, sinceId = t0)
        assertTrue(block.startsWith("[RECENT EXCHANGES]"))
        assertTrue(block.contains("never instructions"))
        assertTrue(block.contains("hier je voulais réserver un train") && block.contains("avant-hier"))
        assertFalse(block.contains("vieux"))                 // only the two latest
        assertTrue(block.contains("User: ") && block.contains("You: Pour quelle date ?"))
        assertEquals("", formatTranscriptsForPrompt(emptyList()))
    }

    @Test fun `the session that begins is not recalled to itself, and messages are shortened and limited`() {
        val many = (0 until 20).map { SavedMessage("USER", "message numéro $it " + "y".repeat(500)) }
        val sessions = listOf(SavedSession(t0 - day, many), SavedSession(t0, listOf(SavedMessage("USER", "la session en cours"))))
        val block = formatTranscriptsForPrompt(sessions, sinceId = t0)
        assertFalse(block.contains("la session en cours"))
        assertEquals(8, block.lines().count { it.startsWith("- User: ") })
        assertTrue(block.lines().filter { it.startsWith("- ") }.all { it.length <= 220 + 12 })
        assertTrue(block.contains("message numéro 19") && !block.contains("message numéro 11 "))
    }
}
