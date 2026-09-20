package com.jarvis.android.routines

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RoutineDueTest {
    // 2026-09-21 is a Monday.
    private fun at(h: Int, m: Int, day: Int = 21) = LocalDateTime.of(2026, 9, day, h, m)
    private val morning = Routine(1, "07:00", "météo")

    @Test
    fun `not due before its time`() = assertFalse(isDue(morning, at(6, 59)))

    @Test
    fun `due once its time has passed`() = assertTrue(isDue(morning, at(7, 10)))

    @Test
    fun `not due twice the same day`() = assertFalse(isDue(morning.copy(lastRunDay = "2026-09-21"), at(7, 30)))

    @Test
    fun `too late a run is skipped`() {
        assertTrue(isDue(morning, at(9, 59)))
        assertFalse(isDue(morning, at(10, 30)))
    }

    @Test
    fun `days filter applies`() {
        val weekend = morning.copy(days = listOf(6, 7))
        assertFalse(isDue(weekend, at(7, 5))) // Monday
        assertTrue(isDue(weekend, at(7, 5, day = 26))) // Saturday
    }

    @Test
    fun `bad time is never due and parses leniently`() {
        assertFalse(isDue(morning.copy(time = "soir"), at(20, 0)))
        assertEquals("07:05", parseTime("7:05").toString())
        assertNull(parseTime("25:00"))
    }

    @Test
    fun `day names are in French`() {
        assertEquals("tous les jours", dayNames(emptyList()))
        assertEquals("lundi, dimanche", dayNames(listOf(7, 1)))
    }
}
