package com.jarvis.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The pure part of the microphone and speaker tests (core/AudioTest.kt). */
class AudioCheckTest {
    @Test
    fun `loudness is measured in decibels below full scale`() {
        assertEquals(-90.0, dbfs(ShortArray(100), 100), 1e-9)
        assertEquals(-90.0, dbfs(ShortArray(0), 0), 1e-9)
        val full = ShortArray(100) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        assertTrue(abs(dbfs(full, 100)) < 0.01)
        val tenth = ShortArray(100) { if (it % 2 == 0) 3277 else -3277 }
        assertEquals(-20.0, dbfs(tenth, 100), 0.1)
    }

    @Test
    fun `the meter runs from sixty decibels down to full scale`() {
        assertEquals(0f, meterOf(-90.0))
        assertEquals(0.5f, meterOf(-30.0), 1e-6f)
        assertEquals(1f, meterOf(3.0))
    }

    @Test
    fun `the verdict tells silence, a whisper and a voice apart`() {
        assertTrue(micVerdict(-80.0).startsWith("Rien entendu"))
        assertTrue(micVerdict(-45.0).startsWith("Très faible (crête -45 dB)"))
        assertTrue(micVerdict(-20.0).startsWith("Le micro vous entend bien"))
    }

    @Test
    fun `the chime is two notes, faded so it does not click`() {
        val s = chimeSamples(16_000, 900)
        assertEquals(14_400, s.size)
        assertTrue(abs(s.first().toInt()) < 200 && abs(s.last().toInt()) < 200)
        assertTrue(s.maxOf { it.toInt() } in 10_000..12_000)
    }
}
