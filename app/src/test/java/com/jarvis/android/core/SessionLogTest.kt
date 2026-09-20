package com.jarvis.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime

class SessionLogTest {
    private val zone = ZoneId.of("Europe/Paris")
    private fun at(h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(2026, 9, 20, h, m, s, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `an ended session gets its duration`() {
        val list = withEnded(withStarted(emptyList(), SessionRecord(at(10, 49, 26), SessionTrigger.WAKE_WORD)), at(10, 50, 4))
        assertEquals(38_000L, list.single().durationMs)
    }

    @Test
    fun `only the latest unfinished session is closed`() {
        var list = withStarted(emptyList(), SessionRecord(1_000, SessionTrigger.APP_BUTTON))
        list = withEnded(list, 5_000)
        list = withStarted(list, SessionRecord(10_000, SessionTrigger.WAKE_WORD))
        list = withEnded(list, 12_000)
        assertEquals(listOf(4_000L, 2_000L), list.map { it.durationMs })
        assertEquals(list, withEnded(list, 99_000))
    }

    @Test
    fun `the history is capped`() {
        var list = emptyList<SessionRecord>()
        repeat(MAX_SESSION_RECORDS + 5) { list = withStarted(list, SessionRecord(it.toLong(), SessionTrigger.UNKNOWN)) }
        assertEquals(MAX_SESSION_RECORDS, list.size)
        assertEquals(5L, list.first().startedAt)
    }

    @Test
    fun `records survive encoding and unknown triggers fall back`() {
        val list = listOf(SessionRecord(1, SessionTrigger.WAKE_WORD, 2_000), SessionRecord(3, SessionTrigger.APP_BUTTON))
        assertEquals(list, decodeSessions(encodeSessions(list)))
        assertEquals(SessionTrigger.UNKNOWN, decodeSessions("""[{"at":5,"trigger":"MARTIAN"}]""").single().trigger)
        assertTrue(decodeSessions("pas du json").isEmpty())
        assertTrue(decodeSessions(null).isEmpty())
        assertTrue(decodeSessions("""[{"trigger":"WAKE_WORD"}]""").isEmpty())
    }

    @Test
    fun `lines say when, what started the session and how long`() {
        assertEquals(
            "20/09 10:49 · mot d’activation · 38 s",
            formatSessionRecord(SessionRecord(at(10, 49, 26), SessionTrigger.WAKE_WORD, 38_000), zone),
        )
        assertEquals(
            "20/09 10:58 · bouton de l’appli · 2 min 5 s",
            formatSessionRecord(SessionRecord(at(10, 58), SessionTrigger.APP_BUTTON, 125_000), zone),
        )
        assertEquals(
            "20/09 11:00 · origine inconnue · en cours ou interrompue",
            formatSessionRecord(SessionRecord(at(11, 0), SessionTrigger.UNKNOWN), zone),
        )
    }

    @Test
    fun `the file log records starts and ends and can be cleared`() {
        val dir = Files.createTempDirectory("sessions").toFile()
        val log = SessionLog(File(dir, "s.json"))
        assertTrue(log.entries().isEmpty())
        log.started(SessionTrigger.WAKE_WORD, now = 1_000)
        log.ended(now = 4_000)
        log.started(SessionTrigger.APP_BUTTON, now = 9_000)
        val entries = SessionLog(File(dir, "s.json")).entries()
        assertEquals(listOf(SessionTrigger.WAKE_WORD, SessionTrigger.APP_BUTTON), entries.map { it.trigger })
        assertEquals(3_000L, entries[0].durationMs)
        assertNull(entries[1].durationMs)
        log.clear()
        assertTrue(log.entries().isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `a corrupt file reads as empty and can be overwritten`() {
        val dir = Files.createTempDirectory("sessions").toFile()
        val file = File(dir, "s.json").apply { writeText("{{{") }
        val log = SessionLog(file)
        assertTrue(log.entries().isEmpty())
        log.started(SessionTrigger.UNKNOWN, now = 1)
        assertEquals(1, log.entries().size)
        dir.deleteRecursively()
    }
}
