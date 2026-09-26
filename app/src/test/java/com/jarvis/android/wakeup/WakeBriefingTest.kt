package com.jarvis.android.wakeup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class WakeBriefingTest {
    private val zone = ZoneId.of("Europe/Paris")
    private fun ms(h: Int, m: Int, day: Int = 26) = LocalDateTime.of(2026, 9, day, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `the morning alarm stopped for good is a wake-up, not a snooze, an evening alarm or a second time`() {
        val rang = ms(7, 0)
        val tomorrow = ms(7, 0, 27)
        assertTrue(isWakeUp(rang, tomorrow, ms(7, 2), "", zone))
        assertTrue(isWakeUp(rang, 0, ms(7, 30), "2026-09-25", zone))
        // Snoozed: the next alarm is in 9 minutes.
        assertFalse(isWakeUp(rang, ms(7, 11), ms(7, 2), "", zone))
        // The snoozed alarm itself, stopped at 7 h 12: that one is the wake-up.
        assertTrue(isWakeUp(ms(7, 11), tomorrow, ms(7, 12), "", zone))
        assertFalse(isWakeUp(rang, tomorrow, ms(7, 2), "2026-09-26", zone))
        assertFalse(isWakeUp(ms(21, 0), tomorrow, ms(21, 1), "", zone))
        assertFalse(isWakeUp(rang, tomorrow, ms(9, 0), "", zone))
        // A new alarm set for later: the previous one has not rung.
        assertFalse(isWakeUp(ms(8, 0), ms(7, 30), ms(6, 50), "", zone))
        assertFalse(isWakeUp(0, tomorrow, ms(7, 2), "", zone))
    }

    @Test
    fun `the briefing says only what there is`() {
        assertEquals(
            "Bonjour ! À Brest : 12 °C, nuageux. Aujourd'hui : 9 h réunion ; anniversaire de Paul. Rappels : 18 h appeler le garage. " +
                "Prélèvement Netflix 13,49 € aujourd'hui. Cette nuit : 7 h 20 de sommeil, de 23 h 10 à 6 h 55.",
            composeWakeBriefing(7, "À Brest : 12 °C, nuageux.", listOf("9 h réunion", "anniversaire de Paul"), listOf("18 h appeler le garage"),
                "7 h 20 de sommeil, de 23 h 10 à 6 h 55", listOf("prélèvement Netflix 13,49 € aujourd'hui")),
        )
        assertEquals("Bonjour ! Rien de particulier au programme aujourd'hui.", composeWakeBriefing(6, null, emptyList(), emptyList(), null))
    }
}
