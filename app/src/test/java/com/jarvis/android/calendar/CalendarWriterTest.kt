package com.jarvis.android.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarWriterTest {
    private fun row(
        id: Long, name: String, access: Int = CAL_ACCESS_CONTRIBUTOR, account: String = "com.google",
        primary: Boolean = false, sync: Boolean = true,
    ) = CalendarRow(id, name, access, account, primary, sync)

    @Test
    fun `nothing is picked when no calendar allows writing`() {
        assertNull(pickWritableCalendar(emptyList()))
        assertNull(pickWritableCalendar(listOf(row(1, "Lecture seule", access = 200))))
    }

    @Test
    fun `an unsynced calendar is never picked even with full access`() {
        assertNull(pickWritableCalendar(listOf(row(1, "Caché", sync = false))))
    }

    @Test
    fun `the account's own primary calendar wins over a secondary one`() {
        val secondary = row(1, "Partagé")
        val primary = row(2, "perso@gmail.com", primary = true)
        assertEquals(primary, pickWritableCalendar(listOf(secondary, primary)))
    }

    @Test
    fun `a real synced account is preferred over a purely local calendar`() {
        val local = row(1, "Local", account = "LOCAL")
        val google = row(2, "Google")
        assertEquals(google, pickWritableCalendar(listOf(local, google)))
    }

    @Test
    fun `ties fall back to name then id, so the result never depends on database row order`() {
        val b = row(2, "B")
        val a = row(1, "A")
        assertEquals(a, pickWritableCalendar(listOf(b, a)))
        assertEquals(a, pickWritableCalendar(listOf(a, b)))
    }
}
