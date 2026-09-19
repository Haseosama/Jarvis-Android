package com.jarvis.android.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReminderDatesTest {
    private val now = Instant.parse("2024-01-01T00:00:00Z")
    private val utc = ZoneId.of("UTC")

    @Test
    fun `invalid calendar dates and non strict formats are rejected`() {
        listOf("31/02/2025", "2025-02-31 09:00", "2025-02-29 09:00",
            "2025-01-01 24:00", "2025-1-01 09:00", "2025-01-01 09:00junk",
            " 2025-01-01 09:00", "0000-01-01 09:00").forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) {
                ReminderDates.parse(value, utc, now)
            }
        }
    }

    @Test
    fun `leap day resolves to expected instant`() {
        assertEquals(Instant.parse("2024-02-29T09:00:00Z").toEpochMilli(),
            ReminderDates.parse("2024-02-29 09:00", utc, now))
    }

    @Test
    fun `past and present are rejected`() {
        listOf("2023-12-31 23:59", "2024-01-01 00:00").forEach {
            assertThrows(IllegalArgumentException::class.java) { ReminderDates.parse(it, utc, now) }
        }
    }

    @Test
    fun `daylight saving gap and overlap are rejected`() {
        val paris = ZoneId.of("Europe/Paris")
        listOf("2025-03-30 02:30", "2025-10-26 02:30").forEach {
            assertThrows(IllegalArgumentException::class.java) { ReminderDates.parse(it, paris, now) }
        }
        assertEquals(Instant.parse("2025-03-30T01:30:00Z").toEpochMilli(),
            ReminderDates.parse("2025-03-30 03:30", paris, now))
    }
}
