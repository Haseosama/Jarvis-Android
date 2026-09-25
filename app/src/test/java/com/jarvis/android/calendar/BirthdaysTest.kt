package com.jarvis.android.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.MonthDay

class BirthdaysTest {
    private val today = LocalDate.of(2026, 9, 25)

    @Test
    fun `the formats contacts apps use are all read, with or without a year`() {
        assertEquals(MonthDay.of(5, 12) to 1990, parseBirthday("1990-05-12"))
        assertEquals(MonthDay.of(5, 12) to null, parseBirthday("--05-12"))
        assertEquals(MonthDay.of(5, 12) to 1990, parseBirthday("19900512"))
        assertEquals(MonthDay.of(5, 12) to 1990, parseBirthday("12/05/1990"))
        assertEquals(MonthDay.of(5, 12) to null, parseBirthday("1604-05-12")) // "year unknown" in some apps
        assertNull(parseBirthday("demain"))
        assertNull(parseBirthday("1990-13-40"))
    }

    @Test
    fun `the next occurrence is this year or next, and 29 February falls on the 28th`() {
        assertEquals(LocalDate.of(2026, 9, 25), nextOccurrence(Birthday("A", MonthDay.of(9, 25), null), today))
        assertEquals(LocalDate.of(2027, 1, 3), nextOccurrence(Birthday("B", MonthDay.of(1, 3), null), today))
        assertEquals(LocalDate.of(2027, 2, 28), nextOccurrence(Birthday("C", MonthDay.of(2, 29), null), today))
    }

    @Test
    fun `descriptions say today, tomorrow or the date, with the age when the year is known`() {
        assertEquals("Paul : aujourd'hui (36 ans)", describeBirthday(Birthday("Paul", MonthDay.of(9, 25), 1990), today))
        assertEquals("Marie : demain", describeBirthday(Birthday("Marie", MonthDay.of(9, 26), null), today))
        assertEquals("Luc : le 12 octobre, dans 17 jours (40 ans)", describeBirthday(Birthday("Luc", MonthDay.of(10, 12), 1986), today))
    }

    @Test
    fun `upcoming ones are soonest first, and a name finds its contact`() {
        val all = listOf(Birthday("Luc Martin", MonthDay.of(10, 12), null), Birthday("Paul Durand", MonthDay.of(9, 26), null), Birthday("Zoé", MonthDay.of(3, 1), null))
        assertEquals(listOf("Paul Durand", "Luc Martin"), upcomingBirthdays(all, today, 30).map { it.name })
        assertEquals(listOf("Zoé"), birthdaysOf(all, "zoe").map { it.name })
        assertEquals(listOf("Luc Martin"), birthdaysOf(all, "Luc").map { it.name })
    }
}
