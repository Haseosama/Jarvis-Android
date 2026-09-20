package com.jarvis.android.calendar

import com.jarvis.android.memory.BriefingInputs
import com.jarvis.android.memory.buildBriefingBlock
import com.jarvis.android.proactive.morningNotificationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarReaderTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val day = LocalDate.of(2026, 9, 21)

    private fun timed(title: String, hour: Int, minutes: Int = 60, d: LocalDate = day) =
        EventRow(title, d.atTime(hour, 0).atZone(paris).toInstant().toEpochMilli(), d.atTime(hour, 0).plusMinutes(minutes.toLong()).atZone(paris).toInstant().toEpochMilli(), false)

    @Test
    fun `a timed event shows its local start and end`() {
        assertEquals("09:00-10:30 Dentiste", formatEvent(timed("Dentiste", 9, 90), paris))
    }

    @Test
    fun `an all-day event is stored at UTC midnight and still belongs to its own day`() {
        val allDay = EventRow("Anniversaire", day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true)
        val newYork = ZoneId.of("America/New_York")
        assertEquals(day, eventDay(allDay, newYork))
        assertEquals(day, eventDay(allDay, paris))
        assertEquals("Toute la journée Anniversaire", formatEvent(allDay, paris))
    }

    @Test
    fun `a day lists all-day events first, then by start time, and skips other days`() {
        val allDay = EventRow("Férié", day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true)
        val lines = linesForDay(listOf(timed("Déjeuner", 12), timed("Réunion", 9), allDay, timed("Demain", 9, d = day.plusDays(1))), day, paris)
        assertEquals(listOf("Toute la journée Férié", "09:00-10:00 Réunion", "12:00-13:00 Déjeuner"), lines)
    }

    @Test
    fun `titles lose control characters and empty titles get a placeholder`() {
        assertEquals("09:00-10:00 AB", formatEvent(timed("x", 9).copy(title = "A" + 7.toChar() + "B "), paris))
        assertTrue(formatEvent(timed("", 9), paris).endsWith("(sans titre)"))
    }

    @Test
    fun `the briefing mentions the day's events and is not empty with only events`() {
        val block = buildBriefingBlock(BriefingInputs(day, "", null, emptyList(), listOf("09:00-10:00 Réunion")))
        assertTrue(block.contains("Réunion"))
        assertTrue(block.contains("not instructions"))
        assertEquals("", buildBriefingBlock(BriefingInputs(day, "", null, emptyList(), emptyList())))
    }

    @Test
    fun `the morning notification includes the agenda`() {
        val text = morningNotificationText(BriefingInputs(day, "", null, emptyList(), listOf("09:00-10:00 Réunion")))!!
        assertEquals("Agenda du jour : 09:00-10:00 Réunion", text)
    }
}
