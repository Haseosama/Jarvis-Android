package com.jarvis.android.marine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

class TidesTest {
    private val m2 = 12.4206 * 3600 // principal lunar half-day, s
    private val s2 = 12.0 * 3600 // principal solar half-day, s
    private val start = LocalDateTime.of(2026, 9, 4, 0, 0).toEpochSecond(ZoneOffset.UTC)
    private val now = LocalDateTime.of(2026, 10, 5, 9, 0).toEpochSecond(ZoneOffset.UTC)
    private val times = (0 until 34 * 24).map { start + it * 3600L }

    /** A tide of two waves: their beat makes the spring and neap tides (ranges 5.4 m and 2.6 m). */
    private fun level(t: Long, a: Double = 2.0, b: Double = 0.7) =
        a * cos(2 * PI * (t - start) / m2) + b * cos(2 * PI * (t - start) / s2)

    private fun series(a: Double = 2.0, b: Double = 0.7) = times.map { level(it, a, b) }

    private fun json(vararg points: Triple<Double, Double, List<Double?>>) = points.joinToString(",", "[", "]") { (lat, lon, h) ->
        """{"latitude":$lat,"longitude":$lon,"hourly":{"time":[${times.joinToString(",")}],"sea_level_height_msl":[${h.joinToString(",")}]}}"""
    }

    @Test fun `high and low waters are found between the hourly values`() {
        val pure = times.map { 2.0 * cos(2 * PI * (it - start) / m2) }
        val ex = findExtremes(times, pure)
        val highs = ex.filter { it.high }
        assertTrue(highs.size > 60)
        // true high waters are at start + k × M2: the parabola puts them within ten minutes
        for (h in highs) {
            val k = Math.round((h.epochSec - start) / m2)
            assertTrue("off by ${h.epochSec - start - k * m2} s", abs(h.epochSec - start - k * m2) < 600)
            assertEquals(2.0, h.height, 0.05)
        }
        // they alternate high, low, high...
        assertTrue(ex.zipWithNext().all { (a, b) -> a.high != b.high })
    }

    @Test fun `missing values and a small wobble do not make extra tides`() {
        val h = series().toMutableList<Double?>()
        h[100] = null
        h[200] = h[200]!! + 0.02 // noise near a turning point
        val ex = findExtremes(times, h)
        assertTrue(ex.zipWithNext().all { (a, b) -> a.high != b.high })
    }

    @Test fun `coefficients follow spring and neap tides on the usual scale`() {
        val brest = findExtremes(times, series())
        val c = brestCoefficients(brest, now).map { it.second }
        // ranges 5.4 m and 2.6 m around a month's mean of about 4 m, which counts as 70
        assertTrue("max ${c.max()}", c.max() in 88..100)
        assertTrue("min ${c.min()}", c.min() in 40..52)
    }

    @Test fun `the model's own scale does not change the coefficients`() {
        val full = brestCoefficients(findExtremes(times, series()), now).map { it.second }
        val damped = brestCoefficients(findExtremes(times, series(1.6, 0.56)), now).map { it.second }
        assertEquals(full.size, damped.size)
        full.zip(damped).forEach { (a, b) -> assertTrue(abs(a - b) <= 1) }
    }

    @Test fun `without a month of history the Brest unit range is used`() {
        val short = times.filter { it > now - 3 * 86400 }
        val h = short.map { level(it) }
        val c = brestCoefficients(findExtremes(short, h), now)
        assertTrue(c.isNotEmpty())
        c.forEach { (_, v) -> assertTrue(v in 20..120) }
    }

    @Test fun `the answer gives today's and tomorrow's tides with coefficients`() {
        val body = json(Triple(48.64, -2.03, series(3.0, 1.0)), Triple(48.38, -4.49, series()))
        val text = formatTides(body, "Saint-Malo", now, ZoneId.of("Europe/Paris"))
        assertTrue(text, text.startsWith("Marées à Saint-Malo. Aujourd'hui : "))
        assertTrue(text, "Demain : " in text)
        assertTrue(text, "pleine mer" in text && "basse mer" in text)
        assertTrue(text, Regex("coefficient \\d+ puis \\d+").containsMatchIn(text))
        assertTrue(text, "marnage" in text)
        assertTrue(text, "SHOM" in text)
    }

    @Test fun `a Mediterranean port says its tide is tiny`() {
        val body = json(Triple(43.30, 5.36, series(0.1, 0.03)), Triple(48.38, -4.49, series()))
        val text = formatTides(body, "Marseille", now, ZoneId.of("Europe/Paris"))
        assertTrue(text, "marée très faible" in text)
        assertFalse(text, "marnage" in text)
    }

    @Test fun `nearest port within reach`() {
        assertEquals("Saint-Malo", nearestPort(48.65, -2.01)!!.first.name)
        assertEquals("La Rochelle", nearestPort(46.16, -1.15)!!.first.name)
        assertNull(nearestPort(45.76, 4.84)) // Lyon
        assertEquals(48.64 to -2.03, modelCell(json(Triple(48.64, -2.03, series()))))
    }

    @Test fun `marine weather in words`() {
        val marine = """{"latitude":48.6,"longitude":-2.0,"current":{"time":$now,"wave_height":1.8,"wave_direction":290,"wave_period":9.4,""" +
            """"sea_surface_temperature":16.6},"hourly":{"time":[$now,${now + 3600}],"wave_height":[1.8,2.6]}}"""
        val wind = """{"current":{"time":$now,"wind_speed_10m":18.2,"wind_direction_10m":250,"wind_gusts_10m":27},""" +
            """"hourly":{"time":[$now,${now + 3600}],"wind_gusts_10m":[27,35]}}"""
        val text = formatMarineWeather(marine, wind, "Saint-Malo", now)
        assertEquals(
            "Météo marine à Saint-Malo : vent d'ouest 18 nœuds (force 5), rafales 27 nœuds, mer peu agitée, vagues 1,8 m d'ouest, " +
                "période 9 s, eau à 17 °C. Dans les 12 heures : vagues jusqu'à 2,6 m, rafales jusqu'à 35 nœuds (force 8). Modèle Open-Meteo, " +
                "pas un bulletin officiel : pour sortir en mer, le bulletin côtier de Météo-France fait foi.",
            text,
        )
    }

    @Test fun `beaufort and sea state scales`() {
        assertEquals(0, beaufort(0.5))
        assertEquals(4, beaufort(12.0))
        assertEquals(12, beaufort(70.0))
        assertEquals("belle", seaState(0.8))
        assertEquals("agitée", seaState(3.0))
        assertEquals("du nord", windFrom(350.0))
        assertEquals("du sud-ouest", windFrom(225.0))
    }
}
