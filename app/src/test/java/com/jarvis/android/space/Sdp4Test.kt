package com.jarvis.android.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Sdp4Test {
    // Vallado's verification satellites (SGP4-VER.TLE) and the positions of his reference output (tcppver.out), km and km/s
    private fun tle(l1: String, l2: String) = parseTle("", l1, l2)!!

    private fun check(sat: Sgp4, t: Double, x: Double, y: Double, z: Double, vx: Double, vy: Double, vz: Double) {
        val s = sat.propagate(t)!!
        assertEquals(x, s.x, 1e-3)
        assertEquals(y, s.y, 1e-3)
        assertEquals(z, s.z, 1e-3)
        assertEquals(vx, s.vx, 1e-6)
        assertEquals(vy, s.vy, 1e-6)
        assertEquals(vz, s.vz, 1e-6)
    }

    @Test fun `a GPS satellite (12 hours, nearly circular) is where Vallado puts it`() {
        val navstar = Sgp4(tle("1 28129U 03058A   06175.57071136 -.00000104  00000-0  10000-3 0   459",
            "2 28129  54.7298 324.8098 0048506 266.2640  93.1663  2.00562768 18443"))
        assertFalse(navstar.nearEarth)
        check(navstar, 0.0, 21707.46412351, -15318.61752390, 0.13551152, 1.304029214, 1.816904974, 3.161919976)
        check(navstar, 1440.0, 22002.20074562, -14879.72595593, 774.32827099, 1.191573619, 1.894561165, 3.159953047)
    }

    @Test fun `a geostationary satellite (24 hours, the one-day resonance) is where Vallado puts it`() {
        val geo = Sgp4(tle("1 28626U 05008A   06176.46683397 -.00000205  00000-0  10000-3 0  2190",
            "2 28626   0.0019 286.9433 0000335  13.7918  55.6504  1.00270176  4891"))
        assertFalse(geo.nearEarth)
        check(geo, 0.0, 42080.71852213, -2646.86387436, 0.81851294, 0.193105177, 3.068688251, 0.000438449)
        check(geo, 1440.0, 42119.96263499, -1925.77567263, -0.19827433, 0.140521206, 3.071541613, 0.000179561)
    }

    @Test fun `a Molniya orbit (12 hours, very eccentric, the half-day resonance) is where Vallado puts it`() {
        val molniya = Sgp4(tle("1 08195U 75081A   06176.33215444  .00000099  00000-0  11873-3 0   813",
            "2 08195  64.1586 279.0717 6877146 264.7651  20.2257  2.00491383225656"))
        check(molniya, 0.0, 2349.89483350, -14785.93811562, 0.02119378, 2.721488096, -3.256811655, 4.498416672)
        check(molniya, 120.0, 15223.91713658, -17852.95881713, 25280.39558224, 1.079041732, 0.875187372, 2.485682813)
        check(molniya, 2880.0, 3417.20931586, -16038.79510665, 1894.74934058, 2.585515864, -2.596818146, 4.456882556)
    }

    @Test fun `a deep-space orbit goes back in time too, near the equator (Lyddane's form)`() {
        // 11.5° of inclination, under the 0.2 rad where the Moon's and Sun's pulls are added another way
        val sat = Sgp4(tle("1 04632U 70093B   04031.91070959 -.00000084  00000-0  10000-3 0  9955",
            "2 04632  11.4628 273.1101 1450506 207.6000 143.9350  1.20231981 44145"))
        check(sat, 0.0, 2334.11450085, -41920.44035349, -0.03867437, 2.826321032, -0.065091664, 0.570936053)
        check(sat, -5184.0, -29020.02587128, 13819.84419063, -5713.33679183, -1.768068390, -3.235371192, -0.395206135)
        check(sat, -4896.0, -15129.94694545, -36907.74526221, -3487.56256701, 2.581167187, -1.524204737, 0.504805763)
    }

    @Test fun `seen from the ground a geostationary satellite stays put, a GPS one crosses the sky`() {
        val geoTle = tle("1 28626U 05008A   06176.46683397 -.00000205  00000-0  10000-3 0  2190",
            "2 28626   0.0019 286.9433 0000335  13.7918  55.6504  1.00270176  4891")
        val geo = Sgp4(geoTle)
        // under it, on the equator: it is straight up, 35 786 km high, all day long
        val start = geoTle.epochMs
        val (lat, lon, alt) = geo.at(start)!!.let { subPoint(temeToEcef(it.x, it.y, it.z, start)) }
        assertEquals(0.0, lat, 0.1)
        assertEquals(35_786.0, alt, 50.0)
        val under = Observer(0.0, lon)
        (0..24).forEach { h ->
            val at = start + h * 3_600_000L
            val s = geo.at(at)!!
            assertEquals(89.5, lookAt(under, temeToEcef(s.x, s.y, s.z, at)).elevationDeg, 0.6)
        }
        val navstar = Sgp4(tle("1 28129U 03058A   06175.57071136 -.00000104  00000-0  10000-3 0   459",
            "2 28129  54.7298 324.8098 0048506 266.2640  93.1663  2.00562768 18443"))
        val heights = (0..24).map { h ->
            val at = start + h * 3_600_000L
            val s = navstar.at(at)!!
            lookAt(under, temeToEcef(s.x, s.y, s.z, at)).elevationDeg
        }
        assertTrue(heights.any { it > 20 } && heights.any { it < -20 })
    }

    @Test fun `with no pass to tell, a geostationary satellite is said to stay put, or never to rise`() {
        val geoTle = tle("1 28626U 05008A   06176.46683397 -.00000205  00000-0  10000-3 0  2190",
            "2 28626   0.0019 286.9433 0000335  13.7918  55.6504  1.00270176  4891")
        val geo = Sgp4(geoTle)
        val now = geoTle.epochMs
        val lon = geo.at(now)!!.let { subPoint(temeToEcef(it.x, it.y, it.z, now)).second }
        assertTrue(passes(geo, Observer(0.0, lon), now, 24).isEmpty())
        assertTrue(SatelliteTool.noPass(geoTle, geo, Observer(0.0, lon), now).contains("presque immobile"))
        assertTrue(SatelliteTool.noPass(geoTle, geo, Observer(0.0, lon + 180), now).contains("ne se lève jamais"))
    }
}
