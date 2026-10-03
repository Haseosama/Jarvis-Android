package com.jarvis.android.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class OpeningHoursTest {
    // Saturday 3 October 2026
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, day, h, m)

    @Test
    fun `a pharmacy with a lunch break is open in the morning, closed at lunch, and says when it opens again`() {
        val hours = "Mo-Fr 08:30-12:30,14:00-19:30; Sa 09:00-12:30"
        assertEquals(OpenState(true, at(1, 12, 30)), openState(hours, at(1, 10))) // Thursday morning
        assertEquals(OpenState(false, at(1, 14)), openState(hours, at(1, 13))) // lunch
        assertEquals(OpenState(true, at(3, 12, 30)), openState(hours, at(3, 11))) // Saturday morning
        assertEquals(OpenState(false, at(5, 8, 30)), openState(hours, at(3, 13))) // Saturday afternoon: Monday 8:30
    }

    @Test
    fun `around the clock has no closing time`() {
        assertEquals(OpenState(true, null), openState("24/7", at(3, 3)))
        assertEquals(OpenState(true, null), openState("Mo-Su 00:00-24:00", at(3, 3)))
    }

    @Test
    fun `a bakery closed on Mondays, and the rules after a comma add to the ones before`() {
        val hours = "Tu-Sa 07:00-13:00,15:30-19:30, Su 07:00-12:30"
        assertEquals(OpenState(false, at(6, 7)), openState(hours, at(5, 10))) // Monday: Tuesday 7:00
        assertEquals(OpenState(true, at(4, 12, 30)), openState(hours, at(4, 8))) // Sunday
        assertEquals(OpenState(true, at(3, 19, 30)), openState(hours, at(3, 16)))
    }

    @Test
    fun `hours past midnight are open after midnight, from the day before`() {
        val hours = "Fr,Sa 18:00-02:00"
        assertEquals(OpenState(true, at(4, 2)), openState(hours, at(4, 1))) // Sunday 1 a.m., from Saturday
        assertEquals(OpenState(false, at(9, 18)), openState(hours, at(4, 3)))
    }

    @Test
    fun `a later rule replaces the days it names, and public holidays are known`() {
        val hours = "Mo-Su 08:00-20:00; Su,PH off"
        assertEquals(OpenState(false, at(5, 8)), openState(hours, at(4, 10))) // Sunday
        assertTrue(openState(hours, at(3, 10))!!.open)
        // 11 November 2026 is a Wednesday and a public holiday
        assertFalse(openState(hours, LocalDateTime.of(2026, 11, 11, 10, 0))!!.open)
        assertTrue(openState(hours, LocalDateTime.of(2026, 11, 12, 10, 0))!!.open)
    }

    @Test
    fun `months and closed days`() {
        val hours = "Jun-Sep 09:00-20:00; Oct-May Mo-Fr 10:00-17:00"
        assertFalse(openState(hours, at(3, 12))!!.open) // a Saturday in October
        assertTrue(openState(hours, at(2, 12))!!.open)
        assertTrue(openState(hours, LocalDateTime.of(2026, 7, 4, 19, 0))!!.open)
        assertEquals(OpenState(false, null), openState("closed", at(3, 12)))
    }

    @Test
    fun `what is not understood is unknown rather than guessed`() {
        assertNull(openState(null, at(3, 12)))
        assertNull(openState("", at(3, 12)))
        assertNull(openState("sunrise-sunset", at(3, 12)))
        assertNull(openState("Mo-Fr 08:00+", at(3, 12)))
        assertNull(openState("Mo[1] 09:00-12:00", at(3, 12)))
        assertNull(openState("sur rendez-vous", at(3, 12)))
        // the comment in quotes is left aside, the hours still read
        assertTrue(openState("Mo-Sa 09:00-19:00 \"pharmacie de garde le dimanche\"", at(3, 12))!!.open)
    }

    @Test
    fun `when it changes, in words`() {
        val now = at(3, 11)
        assertEquals("à 19 h 30", whenWords(at(3, 19, 30), now))
        assertEquals("demain à 8 h", whenWords(at(4, 8), now))
        assertEquals("lundi à 9 h", whenWords(at(5, 9), now))
        assertEquals("à minuit", whenWords(at(4, 0), now))
    }

    @Test
    fun `Easter and the holidays that follow it`() {
        assertEquals(LocalDate.of(2026, 4, 5), easterSunday(2026))
        assertEquals(LocalDate.of(2027, 3, 28), easterSunday(2027))
        assertTrue(isFrenchPublicHoliday(LocalDate.of(2026, 4, 6))) // Easter Monday
        assertTrue(isFrenchPublicHoliday(LocalDate.of(2026, 5, 14))) // Ascension
        assertTrue(isFrenchPublicHoliday(LocalDate.of(2026, 5, 25))) // Whit Monday
        assertTrue(isFrenchPublicHoliday(LocalDate.of(2026, 7, 14)))
        assertFalse(isFrenchPublicHoliday(LocalDate.of(2026, 10, 3)))
    }
}
