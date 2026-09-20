package com.jarvis.android.memory

import com.jarvis.android.core.ConversationMessage
import com.jarvis.android.core.ConversationRole
import com.jarvis.android.reminders.ReminderRecord
import com.jarvis.android.reminders.ReminderStatus
import com.jarvis.android.rest.RestChatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class BriefingTest {
    private fun msg(role: ConversationRole, text: String) = ConversationMessage(role, text, true)
    private val zone = ZoneId.of("Europe/Paris")
    private fun at(h: Int, m: Int = 0, day: Int = 19) =
        ZonedDateTime.of(2026, 9, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private fun reminder(id: Int, text: String, trigger: Long, status: ReminderStatus = ReminderStatus.SCHEDULED) =
        ReminderRecord(id, "t$id", text, "", zone.id, trigger, true, status)

    @Test
    fun `only real exchanges are summarized`() {
        val one = listOf(msg(ConversationRole.USER, "Salut"), msg(ConversationRole.ASSISTANT, "Bonjour"))
        val two = one + msg(ConversationRole.USER, "Quelle heure ?")
        assertFalse(worthSummarizing(one))
        assertTrue(worthSummarizing(two))
        assertFalse(worthSummarizing(listOf(msg(ConversationRole.SYSTEM, "x"), msg(ConversationRole.USER, " "), msg(ConversationRole.USER, "a"))))
    }

    @Test
    fun `transcript skips system lines and keeps roles`() {
        val text = transcriptForSummary(
            listOf(msg(ConversationRole.SYSTEM, "Action : x"), msg(ConversationRole.USER, "Bonjour"), msg(ConversationRole.ASSISTANT, "Salut\nmonsieur"))
        )
        assertEquals("Utilisateur : Bonjour\nJarvis : Salut monsieur", text)
    }

    @Test
    fun `long transcripts keep their end`() {
        val text = transcriptForSummary(listOf(msg(ConversationRole.USER, "a".repeat(10_000) + "FIN")))
        assertEquals(MAX_TRANSCRIPT_CHARS, text.length)
        assertTrue(text.endsWith("FIN"))
    }

    @Test
    fun `summary request needs a transcript`() {
        assertTrue(buildSummaryRequest("Utilisateur : x").toString().contains("Résume cette conversation"))
        try {
            buildSummaryRequest("  ")
            fail("erreur attendue")
        } catch (_: RestChatException) {
        }
    }

    @Test
    fun `only pending reminders of today still to come are listed, in order`() {
        val now = at(8)
        val list = remindersToday(
            listOf(
                reminder(1, "Appeler maman", at(18, 30)),
                reminder(2, "Médicament", at(9)),
                reminder(3, "Hier", at(7)),
                reminder(4, "Demain", at(9, 0, 20)),
                reminder(5, "Déjà fait", at(10), ReminderStatus.DELIVERED),
            ),
            now, zone,
        )
        assertEquals(listOf("09:00 Médicament", "18:30 Appeler maman"), list)
    }

    private val session = SessionEntry("2026-09-18", "Il a préparé son voyage à Lyon.")

    @Test
    fun `the block lists the recap and the reminders`() {
        val block = buildBriefingBlock(BriefingInputs(LocalDate.of(2026, 9, 19), "2026-09-18", session, listOf("09:00 Médicament")))
        assertTrue(block.contains("first session of the day"))
        assertTrue(block.contains("LAST SESSION (2026-09-18): Il a préparé son voyage à Lyon."))
        assertTrue(block.contains("09:00 Médicament"))
    }

    @Test
    fun `no block when already given today or nothing to say`() {
        assertEquals("", buildBriefingBlock(BriefingInputs(LocalDate.of(2026, 9, 19), "2026-09-19", session, listOf("x"))))
        assertEquals("", buildBriefingBlock(BriefingInputs(LocalDate.of(2026, 9, 19), "", null, emptyList())))
    }

    @Test
    fun `a block is built with only reminders or only a recap`() {
        assertTrue(buildBriefingBlock(BriefingInputs(LocalDate.of(2026, 9, 19), "", null, listOf("09:00 x"))).contains("REMINDERS"))
        assertFalse(buildBriefingBlock(BriefingInputs(LocalDate.of(2026, 9, 19), "", session, emptyList())).contains("REMINDERS"))
    }
}
