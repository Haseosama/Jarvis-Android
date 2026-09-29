package com.jarvis.android.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraTest {
    /** An OVATION answer with a band of 30 % at 60–65° N, and 50 % at 62° N, 2° E. */
    private fun ovation(): String {
        val sb = StringBuilder("""{"Observation Time": "2026-09-29T11:33:00Z", "Forecast Time": "2026-09-29T13:03:00Z", "Data Format": "[Longitude, Latitude, Aurora]", "coordinates": [""")
        var first = true
        for (lon in 0 until 360) for (lat in -90..90) {
            val v = when { lon == 2 && lat == 62 -> 50; lat in 60..65 -> 30; else -> 0 }
            if (!first) sb.append(", ")
            sb.append("[$lon, $lat, $v]")
            first = false
        }
        return sb.append("]}").toString()
    }

    @Test fun `the OVATION map is read, degree by degree`() {
        val g = parseOvation(ovation())!!
        assertEquals(java.time.Instant.parse("2026-09-29T13:03:00Z").toEpochMilli(), g.forecastMs)
        assertEquals(50, g.at(62, 2))
        assertEquals(30, g.at(61, -100)) // -100 is 260
        assertEquals(0, g.at(48, 2))
        assertNull(parseOvation("{}"))
    }

    @Test fun `from Paris a band 1,300 km north is too far, from Oslo it is overhead or near`() {
        val g = parseOvation(ovation())!!
        val paris = auroraFrom(g, 48.86, 2.35)
        assertEquals(0, paris.percent)
        val oslo = auroraFrom(g, 59.9, 10.75)
        assertTrue(oslo.percent in 35..42) // the 50 % spot 500 km north-west, low on the horizon, beats the 30 % overhead
        val trondheim = auroraFrom(g, 63.4, 10.4)
        assertEquals(30, trondheim.percent)
        assertFalse(trondheim.towardPole)
        val bergenSouth = auroraFrom(g, 55.0, 2.0)
        assertTrue(bergenSouth.towardPole)
        assertTrue(bergenSouth.percent in 1..49)
    }

    @Test fun `geomagnetic latitude and the Kp needed`() {
        assertEquals(50.5, geomagneticLatitude(48.86, 2.35), 1.5) // Paris
        assertEquals(54.7, geomagneticLatitude(45.5, -73.6), 1.5) // Montréal: further "north" magnetically than Paris
        assertEquals(6.0, kpNeeded(geomagneticLatitude(48.86, 2.35)), 1.0)
        assertTrue(kpNeeded(geomagneticLatitude(69.65, 18.96)) < 1.5) // Tromsø
        assertEquals("G3 (forte)", stormScale(7.3))
        assertEquals("pas de tempête", stormScale(3.0))
    }

    @Test fun `Kp now and forecast are read`() {
        assertEquals(1.33, parseKpNow("""[{"time_tag":"2026-09-29T11:38:00","kp_index":1,"estimated_kp":1.00,"kp":"1Z"},{"time_tag":"2026-09-29T11:39:00","kp_index":1,"estimated_kp":1.33,"kp":"1P"}]""")!!, 1e-9)
        val f = parseKpForecast("""[{"time_tag":"2026-09-22T00:00:00","kp":2.00,"observed":"observed","noaa_scale":null},{"time_tag":"2026-10-02T00:00:00","kp":5.33,"observed":"predicted","noaa_scale":"G1"}]""")
        assertEquals(2, f.size)
        assertTrue(f[1].predicted)
        assertEquals("G1", f[1].scale)
        assertEquals(java.time.Instant.parse("2026-10-02T00:00:00Z").toEpochMilli(), f[1].timeMs)
        assertTrue(parseKpForecast("nope").isEmpty())
    }

    @Test fun `the forecast is told night by night, dark hours only, nights to come`() {
        val paris = java.time.ZoneId.of("Europe/Paris")
        fun at(s: String) = java.time.Instant.parse(s).toEpochMilli()
        val slots = listOf("2026-09-28T18:00:00Z", "2026-09-29T09:00:00Z", "2026-09-29T15:00:00Z", "2026-09-29T18:00:00Z", "2026-09-30T00:00:00Z", "2026-09-30T12:00:00Z")
            .mapIndexed { i, t -> KpSlot(at(t), i.toDouble(), true, null) }
        val nights = nightlyKp(slots, at("2026-09-29T09:50:00Z"), paris)
        assertEquals(listOf(java.time.LocalDate.of(2026, 9, 29)), nights.keys.toList()) // the night of the 28th is over; 11h, 17h and 14h are daylight
        assertEquals(listOf(3.0, 4.0), nights.values.first().map { it.kp }) // 20h and 2h Paris time
        assertEquals("nuit du 29 au 30 septembre", nightWords(java.time.LocalDate.of(2026, 9, 29)))
        assertEquals("nuit du 30 septembre au 1er octobre", nightWords(java.time.LocalDate.of(2026, 9, 30)))
    }

    @Test fun `what can be seen, in words`() {
        val w = auroraWords(AuroraView(35, 500.0, true), 6.7, 6.0, true, 20)
        assertTrue(w, w.startsWith("Kp actuel 6,7 (G2 (modérée)"))
        assertTrue(w, "bas sur l’horizon vers le pôle (35 %" in w)
        assertTrue(w, "ciel plutôt dégagé" in w)
        assertNotNull(auroraWords(null, null, 6.0, false, null).takeIf { "pas assez nuit" in it })
    }
}
