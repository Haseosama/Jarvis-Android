package com.jarvis.android.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AlarmContactTest {
    @Test
    fun `alarm time is parsed and days are converted to calendar numbers`() {
        val (request, error) = parseAlarm("7:30", "Sport", "1, 7")
        assertNull(error)
        assertEquals(7, request!!.hour)
        assertEquals(30, request.minute)
        assertEquals("Sport", request.label)
        assertEquals(listOf(2, 1), request.calendarDays) // Monday = 2, Sunday = 1
    }

    @Test
    fun `bad alarm input is refused with a message`() {
        assertNotNull(parseAlarm("25:00", "", "").second)
        assertNotNull(parseAlarm("soir", "", "").second)
        assertNotNull(parseAlarm("07:30", "", "8").second)
        assertNotNull(parseAlarm("07:30", "", "lundi").second)
    }

    @Test
    fun `minutes until the next ring wrap to tomorrow`() {
        val now = LocalDateTime.of(2026, 9, 20, 22, 0)
        assertEquals(9 * 60L, minutesUntil(now, 7, 0))
        assertEquals(60L, minutesUntil(now, 23, 0))
    }

    private val rows = listOf(
        ContactRow(1, "Maman", "06 12 34 56 78", "Mobile"),
        ContactRow(1, "Maman", "0612345678", "Mobile"),
        ContactRow(1, "Maman", "01 23 45 67 89", "Domicile"),
        ContactRow(2, "Éléonore Martin", "07 00 00 00 01", "Mobile"),
        ContactRow(3, "Paul Martin", "07 00 00 00 02", "Mobile"),
    )

    @Test
    fun `contacts are matched without accents or case and the same number counts once`() {
        assertEquals(2, matchContacts(rows, "maman").size)
        val eleonore = matchContacts(rows, "eleonore")
        assertEquals(1, eleonore.size)
        assertEquals("Éléonore Martin", eleonore[0].name)
    }

    @Test
    fun `every word must be in the name and an exact name comes first`() {
        assertEquals(2, matchContacts(rows, "martin").size)
        assertEquals(1, matchContacts(rows, "paul martin").size)
        assertTrue(matchContacts(rows, "inconnu").isEmpty())
        assertTrue(matchContacts(rows, "   ").isEmpty())
    }

    @Test
    fun `a choice label never contains the number`() {
        val choice = matchContacts(rows, "maman")[0]
        assertTrue(choice.label.startsWith("Maman ("))
        assertTrue(choice.label.none { it.isDigit() })
    }
}
