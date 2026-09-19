package com.jarvis.android.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReschedulePlanTest {
    private val now = 1_700_000_000_000L

    private fun record(id: Int, triggerAt: Long, status: ReminderStatus) = ReminderRecord(
        id = id,
        token = "123e4567-e89b-12d3-a456-426614174000",
        text = "Rappel $id",
        whenIso = "2030-01-01 09:00",
        zoneId = "UTC",
        triggerAt = triggerAt,
        status = status,
    )

    @Test
    fun `future scheduled and preparing records are reprogrammed`() {
        val records = listOf(
            record(1, now + 1, ReminderStatus.SCHEDULED),
            record(2, now + 60_000, ReminderStatus.PREPARING),
        )
        val plan = ReminderService.planReschedule(records, now)
        assertEquals(listOf(1, 2), plan.reprogram.map { it.id })
        assertTrue(plan.miss.isEmpty())
    }

    @Test
    fun `past and present records are missed`() {
        val records = listOf(
            record(1, now - 1, ReminderStatus.SCHEDULED),
            record(2, now, ReminderStatus.SCHEDULED),
            record(3, now - 3_600_000, ReminderStatus.PREPARING),
        )
        val plan = ReminderService.planReschedule(records, now)
        assertTrue(plan.reprogram.isEmpty())
        assertEquals(listOf(1, 2, 3), plan.miss.map { it.id })
    }

    @Test
    fun `terminal statuses are left untouched`() {
        val records = listOf(
            record(1, now + 60_000, ReminderStatus.DELIVERED),
            record(2, now + 60_000, ReminderStatus.FAILED),
            record(3, now + 60_000, ReminderStatus.BLOCKED),
            record(4, now + 60_000, ReminderStatus.DELIVERING),
            record(5, now - 60_000, ReminderStatus.DELIVERED),
        )
        val plan = ReminderService.planReschedule(records, now)
        assertTrue(plan.reprogram.isEmpty())
        assertTrue(plan.miss.isEmpty())
    }

    @Test
    fun `mixed records are partitioned correctly`() {
        val records = listOf(
            record(1, now + 60_000, ReminderStatus.SCHEDULED),
            record(2, now - 60_000, ReminderStatus.SCHEDULED),
            record(3, now + 60_000, ReminderStatus.DELIVERED),
            record(4, now + 120_000, ReminderStatus.PREPARING),
        )
        val plan = ReminderService.planReschedule(records, now)
        assertEquals(listOf(1, 4), plan.reprogram.map { it.id })
        assertEquals(listOf(2), plan.miss.map { it.id })
    }

    @Test
    fun `empty list yields empty plan`() {
        val plan = ReminderService.planReschedule(emptyList(), now)
        assertTrue(plan.reprogram.isEmpty())
        assertTrue(plan.miss.isEmpty())
    }
}
