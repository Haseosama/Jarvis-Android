package com.jarvis.android.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SkyEventsTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private val paris = ZoneId.of("Europe/Paris")

    @Test fun `the lunar eclipses of 2025 to 2029 are found with their kind`() {
        val found = lunarEclipses(ms("2025-01-01T00:00:00Z"), ms("2030-01-01T00:00:00Z"))
        val byDay = found.associate { Instant.ofEpochMilli(it.maxMs).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString() to it.kind }
        // the known list (NASA's catalogue): total, partial and penumbral
        val known = mapOf(
            "2025-03-14" to "totale", "2025-09-07" to "totale", "2026-03-03" to "totale", "2026-08-28" to "partielle",
            "2027-02-20" to "par la pénombre", "2027-08-17" to "par la pénombre", "2028-01-12" to "partielle", "2028-07-06" to "partielle",
            "2028-12-31" to "totale", "2029-06-26" to "totale", "2029-12-20" to "totale",
        )
        known.forEach { (day, kind) ->
            val d = LocalDate.parse(day)
            val got = listOf(d.minusDays(1), d, d.plusDays(1)).firstNotNullOfOrNull { byDay[it.toString()] }
            assertEquals("the eclipse of $day", kind, got)
        }
        // the middle of the 3 March 2026 eclipse is at 11:33 UTC (within the Moon's 0.3° of accuracy: a quarter of an hour)
        val march = found.first { Instant.ofEpochMilli(it.maxMs).toString().startsWith("2026-03-03") }
        assertTrue(kotlin.math.abs(march.maxMs - ms("2026-03-03T11:33:00Z")) < 20 * 60_000L)
        assertEquals(1.15, march.umbralMagnitude, 0.12)
    }

    @Test fun `full moons`() {
        val f = fullMoons(ms("2026-01-01T00:00:00Z"), ms("2026-02-15T00:00:00Z"))
        assertEquals(2, f.size)
        assertTrue(kotlin.math.abs(f[0] - ms("2026-01-03T10:03:00Z")) < 40 * 60_000L)
        assertTrue(kotlin.math.abs(f[1] - ms("2026-02-01T22:09:00Z")) < 40 * 60_000L)
    }

    @Test fun `Venus and Jupiter close together in the morning of 12 August 2025`() {
        val c = conjunctions(ms("2025-08-05T00:00:00Z"), ms("2025-08-20T00:00:00Z")).firstOrNull { setOf(it.a, it.b) == setOf("Vénus", "Jupiter") }
        assertNotNull(c)
        assertEquals(LocalDate.of(2025, 8, 12), Instant.ofEpochMilli(c!!.timeMs).atZone(paris).toLocalDate())
        assertEquals(0.9, c.separation, 0.25)
        assertTrue(!c.evening)
    }

    @Test fun `the Perseids, and the Moon at their peak`() {
        val e = showerEvents(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), paris)
        assertEquals(1, e.size)
        assertTrue(e[0].words, e[0].words.startsWith("Perséides, maximum la nuit du 12 au 13 août : jusqu’à 100 par heure"))
        assertTrue(e[0].words, "la Lune ne gêne pas" in e[0].words) // new moon on 12 August 2026
        assertTrue(showerEvents(LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 31), paris).isEmpty())
        assertEquals("du Lion", fromWords("le Lion"))
        assertEquals("de la Lyre", fromWords("la Lyre"))
        assertEquals("des Gémeaux", fromWords("les Gémeaux"))
        assertEquals("d’Orion", fromWords("Orion"))
        assertEquals("de Persée", fromWords("Persée"))
    }

    @Test fun `the solar eclipse of 12 August 2026 from Paris, as the US Naval Observatory gives it`() {
        // USNO: begins 17:22:12, maximum 18:17:16 UTC, 92.1 % hidden, the Sun at 7.6°; it sets at 19:09 before the end
        val e = localSolarEclipses(Observer(48.85, 2.35), ms("2026-08-01T00:00:00Z"), ms("2026-08-31T00:00:00Z")).single()
        assertTrue(kotlin.math.abs(e.beginMs - ms("2026-08-12T17:22:12Z")) < 4 * 60_000L)
        assertTrue(kotlin.math.abs(e.maxMs - ms("2026-08-12T18:17:16Z")) < 4 * 60_000L)
        assertEquals(0.921, e.obscuration, 0.03)
        assertEquals(7.6, e.sunAltitude, 1.0)
        assertEquals("partielle", e.kind)
        assertTrue(e.words(), e.words().startsWith("éclipse de Soleil partielle ici le mercredi 12 août 2026 : début 19h2"))
        // Burgos, in the path of totality, sees it total
        assertEquals("totale", localSolarEclipses(Observer(42.34, -3.70), ms("2026-08-01T00:00:00Z"), ms("2026-08-31T00:00:00Z")).single().kind)
        // and none from Sydney that month
        assertTrue(localSolarEclipses(Observer(-33.87, 151.21), ms("2026-08-01T00:00:00Z"), ms("2026-08-31T00:00:00Z")).isEmpty())
    }

    private fun LocalSolarEclipse.words() = solarWords(this, paris)

    @Test fun `the part of the Sun hidden`() {
        assertEquals(0.0, hiddenPart(0.26, 0.27, 0.6), 1e-9)
        assertEquals(1.0, hiddenPart(0.26, 0.27, 0.005), 1e-9)
        assertEquals((0.25 * 0.25) / (0.26 * 0.26), hiddenPart(0.26, 0.25, 0.005), 1e-9) // annular
        assertEquals(0.5, hiddenPart(0.26, 0.26, 0.2123), 0.02) // two equal discs overlapping by half
    }
}
