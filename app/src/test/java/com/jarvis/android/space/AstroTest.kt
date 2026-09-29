package com.jarvis.android.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AstroTest {
    private val t = java.time.Instant.parse("2026-09-29T00:00:00Z").toEpochMilli()

    /** The angle between two directions given as right ascension and declination, degrees. */
    private fun separation(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        fun r(d: Double) = Math.toRadians(d)
        val c = kotlin.math.sin(r(a.second)) * kotlin.math.sin(r(b.second)) +
            kotlin.math.cos(r(a.second)) * kotlin.math.cos(r(b.second)) * kotlin.math.cos(r(a.first - b.first))
        return Math.toDegrees(kotlin.math.acos(c.coerceIn(-1.0, 1.0)))
    }

    // JPL Horizons, geocentric apparent RA and Dec of date, 2026-09-29 00:00 UTC
    private val horizons = mapOf(
        Planet.MERCURY to (205.44434 to -12.16427), Planet.VENUS to (213.40519 to -20.71318), Planet.MARS to (122.94652 to 20.99677),
        Planet.JUPITER to (141.84395 to 15.61723), Planet.SATURN to (11.84973 to 2.14434), Planet.URANUS to (63.67583 to 21.07881),
        Planet.NEPTUNE to (3.24114 to -0.14320),
    )

    @Test fun `the planets are where JPL puts them, within a quarter of a degree`() {
        horizons.forEach { (p, ref) ->
            val sep = separation(raDec(planetVector(p, t)), ref)
            assertTrue("${p.french} is $sep° off", sep < 0.25)
        }
    }

    @Test fun `the Moon and the Sun too, and the Moon's distance and phase are right`() {
        // Meeus's lunar theory: within a few hundredths of a degree of JPL (apparent: nutation and aberration make the rest)
        assertTrue(separation(raDec(moonVector(t)), 31.52194 to 17.80008) < 0.05)
        assertTrue(separation(raDec(sunPosition(t)), 185.39302 to -2.33310) < 0.1)
        val m = moonVector(t)
        val km = kotlin.math.sqrt(m.first * m.first + m.second * m.second + m.third * m.third)
        assertEquals(0.00248860682143 * 149_597_870.7, km, 50.0)
        // 29 September 2026: the Moon is two days past full (JPL: elongation about 154°)
        val phase = moonPhase(t)
        assertTrue(phase.lit in 0.85..0.97)
        assertTrue(!phase.waxing)
        assertEquals("gibbeuse décroissante", phase.name)
    }

    @Test fun `Meeus's example 47a, the Moon on 12 April 1992`() {
        val (lon, lat, r) = moonEcliptic(java.time.Instant.parse("1992-04-12T00:00:00Z").toEpochMilli())
        assertEquals(133.162655, lon, 0.00002)
        assertEquals(-3.229126, lat, 0.00002)
        assertEquals(368409.7, r, 0.2)
    }

    @Test fun `the catalogue's stars are read, named, and the Pole Star stays near the pole from Paris`() {
        val stars = parseStars(File("src/main/assets/sky/stars.tsv").readText())
        assertTrue(stars.size > 800)
        val byBayer = stars.associateBy { it.bayer }
        assertEquals("Sirius", byBayer.getValue("Alp CMa").name)
        assertEquals("Rigil Kentaurus", byBayer.getValue("Alp Cen").name)          // written "Alp1Cen" in the catalogue
        val polaris = byBayer.getValue("Alp UMi")
        val look = lookAtSky(Observer(48.8566, 2.3522), starVector(polaris, t), t)
        assertEquals(48.86, look.elevationDeg, 1.0)                                 // the pole's height is the latitude
        assertTrue(look.azimuthDeg < 2 || look.azimuthDeg > 358)
        // every star of the figures is in the catalogue
        val missing = FIGURES.flatten().filter { it !in byBayer }
        assertTrue("missing: $missing", missing.isEmpty())
    }
}
