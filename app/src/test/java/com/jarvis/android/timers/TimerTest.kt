package com.jarvis.android.timers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerDurationsTest {
    @Test
    fun `compact and spelled forms parse to seconds`() {
        mapOf(
            "45s" to 45L,
            "45 secondes" to 45L,
            "45 seconde" to 45L,
            "10m" to 600L,
            "10 min" to 600L,
            "10 minutes" to 600L,
            "1h" to 3600L,
            "1h30" to 5400L,
            "2h15m" to 8100L,
            "1 heure" to 3600L,
            "2 heures" to 7200L,
            "1 heure 30" to 5400L,
            "1 heure 30 minutes" to 5400L,
            "90" to 5400L,
        ).forEach { (input, expected) ->
            assertEquals(input, expected, TimerDurations.parse(input))
        }
    }

    @Test
    fun `case and spacing are tolerated`() {
        assertEquals(600L, TimerDurations.parse("  10 MINUTES  "))
        assertEquals(5400L, TimerDurations.parse("1H30"))
    }

    @Test
    fun `zero out of range and garbage are rejected`() {
        listOf("", "0", "0 minute", "25 heures", "1h75", "demain", "10 pommes", "-5 minutes").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { TimerDurations.parse(it) }
        }
    }

    @Test
    fun `format renders hours minutes seconds`() {
        assertEquals("45 s", TimerDurations.format(45))
        assertEquals("10 min", TimerDurations.format(600))
        assertEquals("1 h 30 min", TimerDurations.format(5400))
        assertEquals("2 h", TimerDurations.format(7200))
    }
}

class TimerPlanTest {
    private val now = 1_700_000_000_000L

    private fun record(id: Int, triggerAt: Long) = TimerRecord(
        id = id,
        token = "123e4567-e89b-12d3-a456-426614174000",
        label = "Minuteur $id",
        durationSeconds = 600,
        createdAt = triggerAt - 600_000,
        triggerAt = triggerAt,
    )

    @Test
    fun `future timers reprogram past timers miss fired untouched`() {
        val records = listOf(
            record(1, now + 60_000),
            record(2, now - 1_000),
            record(3, now + 60_000).copy(status = TimerStatus.FIRED),
            record(4, now - 1_000).copy(status = TimerStatus.CANCELLED),
        )
        val plan = TimerService.planReschedule(records, now)
        assertEquals(listOf(1), plan.reprogram.map { it.id })
        assertEquals(listOf(2), plan.miss.map { it.id })
    }

    @Test
    fun `empty list yields empty plan`() {
        val plan = TimerService.planReschedule(emptyList(), now)
        assertTrue(plan.reprogram.isEmpty())
        assertTrue(plan.miss.isEmpty())
    }
}
