package com.jarvis.android.proactive

import com.jarvis.android.memory.BriefingInputs
import com.jarvis.android.memory.SessionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ProactiveRulesTest {
    private val good = DeviceStatus(80, false, 50_000_000_000L, 128_000_000_000L)
    private fun at(h: Int, day: Int = 19) = LocalDateTime.of(2026, 9, day, h, 0)

    @Test
    fun `nothing to report when all is well`() {
        val (alerts, state) = evaluateProactive(good, ProactiveState(), at(14), null)
        assertTrue(alerts.isEmpty())
        assertEquals(ProactiveState(), state)
    }

    @Test
    fun `low battery is reported once until it recovers`() {
        val low = good.copy(batteryPercent = 12)
        val (first, s1) = evaluateProactive(low, ProactiveState(), at(14), null)
        assertEquals(listOf(ProactiveAlert.Battery(12)), first)
        assertTrue(s1.batteryAlerted)
        val (again, s2) = evaluateProactive(low.copy(batteryPercent = 10), s1, at(14), null)
        assertTrue(again.isEmpty())
        assertTrue(s2.batteryAlerted)
        val (_, s3) = evaluateProactive(good.copy(batteryPercent = 45), s2, at(15), null)
        assertFalse(s3.batteryAlerted)
        val (third, _) = evaluateProactive(low, s3, at(16), null)
        assertEquals(1, third.size)
    }

    @Test
    fun `a charging phone is never reported and re-arms the alert`() {
        val (alerts, state) = evaluateProactive(good.copy(batteryPercent = 5, charging = true), ProactiveState(batteryAlerted = true), at(14), null)
        assertTrue(alerts.isEmpty())
        assertFalse(state.batteryAlerted)
    }

    @Test
    fun `low storage is reported once a day`() {
        val full = good.copy(freeBytes = 500_000_000L)
        val (first, s1) = evaluateProactive(full, ProactiveState(), at(10), null)
        assertEquals(listOf(ProactiveAlert.Storage(500)), first)
        assertTrue(evaluateProactive(full, s1, at(18), null).first.isEmpty())
        assertEquals(1, evaluateProactive(full, s1, at(10, day = 20), null).first.size)
    }

    @Test
    fun `storage is judged by ratio too and unknown totals are ignored`() {
        val tight = DeviceStatus(80, false, 4_000_000_000L, 128_000_000_000L)
        assertEquals(1, evaluateProactive(tight, ProactiveState(), at(10), null).first.size)
        assertTrue(evaluateProactive(DeviceStatus(80, false, 0, 0), ProactiveState(), at(10), null).first.isEmpty())
    }

    @Test
    fun `the morning summary comes once, only in the morning`() {
        val (early, _) = evaluateProactive(good, ProactiveState(), at(6), "x")
        assertTrue(early.isEmpty())
        val (morning, s) = evaluateProactive(good, ProactiveState(), at(8), "Rappels : x")
        assertEquals(listOf(ProactiveAlert.Morning("Rappels : x")), morning)
        assertTrue(evaluateProactive(good, s, at(9), "Rappels : x").first.isEmpty())
        assertTrue(evaluateProactive(good, ProactiveState(), at(12), "x").first.isEmpty())
        assertTrue(evaluateProactive(good, ProactiveState(), at(8), null).first.isEmpty())
    }

    @Test
    fun `morning text lists the recap and the reminders`() {
        val text = morningNotificationText(
            BriefingInputs(LocalDate.of(2026, 9, 19), "", SessionEntry("2026-09-18", "Voyage à Lyon."), listOf("09:00 Médicament"))
        )!!
        assertTrue(text.contains("Dernière session (2026-09-18) : Voyage à Lyon."))
        assertTrue(text.contains("Rappels du jour : 09:00 Médicament"))
        assertNull(morningNotificationText(BriefingInputs(LocalDate.of(2026, 9, 19), "", null, emptyList())))
    }
}
