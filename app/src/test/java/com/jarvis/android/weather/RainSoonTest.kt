package com.jarvis.android.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class RainSoonTest {
    // Shape of the real Open-Meteo answer (minutely_15, timezone=auto).
    private fun body(values: List<Double>) = """
        {"utc_offset_seconds":7200,"timezone":"Europe/Paris","minutely_15":{"time":[${
            (0 until values.size).joinToString(",") { "\"" + LocalDateTime.of(2026, 9, 25, 14, 0).plusMinutes(15L * it) + "\"" }
        }],"precipitation":[${values.joinToString(",")}]}}
    """.trimIndent()

    private val now = LocalDateTime.of(2026, 9, 25, 14, 5)

    @Test
    fun `a dry forecast says so`() {
        assertEquals("Pas de pluie prévue dans les deux prochaines heures.", describeRain(parseRainSlots(body(List(9) { 0.0 })), now))
    }

    @Test
    fun `rain on its way gives the time, the delay and how hard`() {
        val slots = parseRainSlots(body(listOf(0.0, 0.0, 0.3, 0.8, 0.2, 0.0, 0.0, 0.0, 0.0)))
        assertEquals("Pluie modérée attendue vers 14 h 30, dans environ 25 minutes.", describeRain(slots, now))
    }

    @Test
    fun `rain now says until when`() {
        val slots = parseRainSlots(body(listOf(0.8, 0.4, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))) // 0.8 mm / 15 min = 3.2 mm/h
        assertEquals("Il pleut en ce moment (pluie modérée), jusqu'à environ 14 h 30.", describeRain(slots, now))
        assertEquals("forte", rainIntensity(2.5))
        assertEquals("faible", rainIntensity(0.2))
    }

    @Test
    fun `the watcher warns once, only for rain that is coming soon and not already there`() {
        val soon = parseRainSlots(body(listOf(0.0, 0.0, 0.3, 0.3, 0.0, 0.0, 0.0, 0.0, 0.0)))
        val later = parseRainSlots(body(listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.3, 0.0, 0.0, 0.0)))
        val already = parseRainSlots(body(listOf(0.3, 0.3, 0.3, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))
        val t = 10 * RAIN_ALERT_COOLDOWN_MS
        assertTrue(shouldAlertRain(soon, now, 0, t))
        assertFalse("75 minutes away is too early", shouldAlertRain(later, now, 0, t))
        assertFalse("raining already: no 'rain is coming' alert", shouldAlertRain(already, now, 0, t))
        assertFalse("one alert per spell", shouldAlertRain(soon, now, t - 1_000, t))
    }

    @Test
    fun `now is read on the forecast's own clock`() {
        val utc = Instant.parse("2026-09-25T12:05:00Z")
        assertEquals(LocalDateTime.of(2026, 9, 25, 14, 5), forecastNow(body(listOf(0.0)), utc))
    }
}
