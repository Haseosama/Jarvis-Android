package com.jarvis.android.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Sgp4Test {
    // Vallado's verification satellite (SGP4-VER.TLE), and the positions of his reference output (tcppver.out)
    private val vanguard = parseTle(
        "00005",
        "1 00005U 58002B   00179.78495062  .00000023  00000-0  28098-4 0  4753",
        "2 00005  34.2682 348.7242 1859667 331.7664  19.3264 10.82419157413667",
    )!!

    @Test fun `an element set is read as it is written`() {
        assertEquals(5, vanguard.number)
        assertEquals(0.28098e-4, vanguard.bstar, 1e-12)
        assertEquals(0.1859667, vanguard.eccentricity, 1e-9)
        assertEquals(133.0, vanguard.periodMin, 0.1)
        val list = parseTles("ISS (ZARYA)\n1 25544U 98067A   26271.46476993  .00006013  00000+0  11848-3 0  9990\n2 25544  51.6312 148.9632 0007159 198.2260 161.8473 15.48680135587767\ngarbage\n")
        assertEquals(listOf("ISS (ZARYA)"), list.map { it.name })
        assertEquals(0.11848e-3, list[0].bstar, 1e-12)
    }

    @Test fun `SGP4 gives Vallado's reference positions`() {
        val sgp4 = Sgp4(vanguard)
        assertTrue(sgp4.nearEarth)
        val s0 = sgp4.propagate(0.0)!!
        assertEquals(7022.46529266, s0.x, 1e-3)
        assertEquals(-1400.08296755, s0.y, 1e-3)
        assertEquals(0.03995155, s0.z, 1e-3)
        assertEquals(1.893841015, s0.vx, 1e-6)
        assertEquals(6.405893759, s0.vy, 1e-6)
        assertEquals(4.534807250, s0.vz, 1e-6)
        val s360 = sgp4.propagate(360.0)!!
        assertEquals(-7154.03120202, s360.x, 1e-3)
        assertEquals(-3783.17682504, s360.y, 1e-3)
        assertEquals(-3536.19412294, s360.z, 1e-3)
        assertEquals(4.741887409, s360.vx, 1e-6)
    }

    @Test fun `the sky from a place, overhead is up, the Sun is up at noon and down at midnight`() {
        val paris = Observer(48.8566, 2.3522)
        val (x, y, z) = paris.ecef()
        // straight up is along the ellipsoid's normal, not the Earth's radius
        val lat = Math.toRadians(paris.latDeg)
        val lon = Math.toRadians(paris.lonDeg)
        val above = Triple(x + 400 * kotlin.math.cos(lat) * kotlin.math.cos(lon), y + 400 * kotlin.math.cos(lat) * kotlin.math.sin(lon), z + 400 * kotlin.math.sin(lat))
        assertEquals(90.0, lookAt(paris, above).elevationDeg, 0.01)
        assertEquals(400.0, lookAt(paris, above).rangeKm, 0.01)
        // 2026-06-21 at solar noon in Paris (11:52 UTC: 2.35° east, and the equation of time), and at midnight
        val noon = java.time.Instant.parse("2026-06-21T11:52:00Z").toEpochMilli()
        val midnight = java.time.Instant.parse("2026-06-21T22:00:00Z").toEpochMilli()
        assertEquals(64.5, sunElevation(paris, noon), 2.0)
        assertTrue(sunElevation(paris, midnight) < -6)
        assertNotNull(Sgp4(vanguard).at(vanguard.epochMs))
    }

    @Test fun `the ISS passes over Paris several times in three days, each one rising, culminating and setting in order`() {
        val iss = parseTles("ISS (ZARYA)\n1 25544U 98067A   26271.46476993  .00006013  00000+0  11848-3 0  9990\n2 25544  51.6312 148.9632 0007159 198.2260 161.8473 15.48680135587767\n")[0]
        val list = passes(Sgp4(iss), Observer(48.8566, 2.3522), iss.epochMs)
        assertTrue(list.size in 4..30)
        list.forEach { p ->
            assertTrue(p.riseMs < p.maxMs && p.maxMs < p.setMs)
            assertTrue(p.maxElevation in 10.0..90.0)
            assertTrue(p.setMs - p.riseMs in 60_000L..15 * 60_000L)          // a low orbit crosses the sky in minutes
            if (p.visible) assertFalse(p.daylight)
        }
        // passes come about every 90 minutes of an orbit, never twice in the same minutes
        assertTrue(list.zipWithNext().all { (a, b) -> b.riseMs - a.setMs > 30 * 60_000L })
    }

    @Test fun `directions are said as in French`() {
        assertEquals("nord", compass(359.0))
        assertEquals("nord-est", compass(40.0))
        assertEquals("de l’ouest", fromDirection(270.0))
        assertEquals("du sud-est", fromDirection(135.0))
        assertEquals("vers l’est", towardDirection(92.0))
        assertEquals("vers le sud", towardDirection(181.0))
        assertEquals("la Station spatiale internationale (ISS)", friendlyName("ISS (ZARYA)"))
    }

    @Test fun `a satellite in the Earth's shadow is not lit, and a deep-space orbit is told apart`() {
        val t = java.time.Instant.parse("2026-03-20T12:00:00Z").toEpochMilli()
        val (sx, sy, sz) = sunPosition(t)
        val d = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz)
        val behind = StateVector(-sx / d * 6800, -sy / d * 6800, -sz / d * 6800, 0.0, 0.0, 0.0)
        val facing = StateVector(sx / d * 6800, sy / d * 6800, sz / d * 6800, 0.0, 0.0, 0.0)
        assertFalse(sunlit(behind, t))
        assertTrue(sunlit(facing, t))
        val geo = parseTle("GEO", "1 28626U 05008A   06176.46683397 -.00000205  00000-0  10000-3 0  2190",
            "2 28626   0.0019 286.9433 0000335  13.7918  55.6504  1.00270176  4891")!!
        assertFalse(Sgp4(geo).nearEarth)
    }
}
