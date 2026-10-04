package com.jarvis.android.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.jarvis.android.location.MapData
import com.jarvis.android.location.greatCircle
import com.jarvis.android.location.latOf
import com.jarvis.android.location.mercX
import com.jarvis.android.location.mercY
import com.jarvis.android.location.nearestCity

class WorldMapTest {
    @Test fun `Web Mercator goes both ways`() {
        assertEquals(0.5, mercX(0.0), 1e-12)
        assertEquals(0.0, mercX(-180.0), 1e-12)
        assertEquals(0.5, mercY(0.0), 1e-12)
        assertTrue(mercY(60.0) < mercY(10.0))
        for (lat in listOf(-80.0, -33.9, 0.0, 48.8566, 71.0)) assertEquals(lat, latOf(mercY(lat)), 1e-9)
    }

    @Test fun `outlines are read from their rings of hundredths of a degree`() {
        val b = ByteBuffer.allocate(4 + 4 + 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(1).putInt(2).putShort(0).putShort(0).putShort(18000).putShort(-4500)
        val rings = MapData.rings(b.array())
        assertEquals(1, rings.size)
        assertEquals(0.5f, rings[0][0], 1e-6f)
        assertEquals(0.5f, rings[0][1], 1e-6f)
        assertEquals(1f, rings[0][2], 1e-6f)
        assertEquals(mercY(-45.0).toFloat(), rings[0][3], 1e-6f)
    }

    @Test fun `the Natural Earth assets load`() {
        val land = MapData.rings(File("src/main/assets/sky/land.bin").readBytes())
        assertTrue(land.size > 500)
        assertTrue(land.all { r -> r.all { it in 0f..1f } })
        val borders = MapData.rings(File("src/main/assets/sky/borders.bin").readBytes())
        assertTrue(borders.size > 100)
        val cities = MapData.parseCities(File("src/main/assets/sky/cities.tsv").readText())
        val paris = cities.first { it.name == "Paris" }
        assertTrue(paris.capital)
        assertEquals(48.86, paris.lat, 0.05)
        assertEquals("Paris", nearestCity(cities, 48.9, 2.4)!!.first.name)
        assertNull(nearestCity(cities, 0.0, -140.0, 300.0))
    }

    @Test fun `a great circle from Paris to New York goes north of both`() {
        val gc = greatCircle(48.85, 2.35, 40.71, -74.0)
        assertEquals(48.85, gc.first().first, 1e-9)
        assertEquals(-74.0, gc.last().second, 1e-9)
        assertTrue(gc.maxOf { it.first } > 51.0)
    }

    @Test fun `at noon in late September the north pole is dark and the night is on the other side`() {
        val t = java.time.Instant.parse("2026-09-29T12:00:00Z").toEpochMilli()
        val (sLat, sLon) = subSolar(t)
        assertEquals(-2.4, sLat, 0.5) // the Sun's declination six days after the equinox
        assertEquals(-2.5, sLon, 1.0) // solar noon at Greenwich is at 11:50 UTC
        val night = nightPolygon(t)
        assertEquals(85.0, night.last().second, 1e-9) // closed round the dark (north) pole
        val atGreenwich = night.first { it.first == 0.0 }.second
        val atDateLine = night.first { it.first == 180.0 }.second
        assertTrue(atGreenwich > 80) // at noon only the far north is dark
        assertTrue(atDateLine < -80) // at midnight almost everything is
    }

    @Test fun `a satellite's ground track is cut at the date line`() {
        val iss = parseTles(
            "ISS (ZARYA)\n1 25544U 98067A   26271.46476993  .00006013  00000+0  11848-3 0  9990\n2 25544  51.6312 148.9632 0007159 198.2260 161.8473 15.48680135587767\n",
        ).first()
        val track = groundTrack(Sgp4(iss), iss.epochMs, iss.epochMs + 3 * 93 * 60_000L, 60_000L)
        assertTrue(track.size >= 3)
        track.forEach { seg -> seg.zipWithNext().forEach { (a, b) -> assertTrue(kotlin.math.abs(a.second - b.second) < 180) } }
        assertTrue(track.flatten().all { kotlin.math.abs(it.first) <= 52.0 })
    }

    @Test fun `RainViewer's frames are read, past and forecast`() {
        val (host, frames) = parseRadarFrames(
            """{"version":"2.0","generated":1790000000,"host":"https://tilecache.rainviewer.com","radar":{"past":[{"time":1789993200,"path":"/v2/radar/1789993200"},
            {"time":1789993800,"path":"/v2/radar/1789993800"}],"nowcast":[{"time":1790000400,"path":"/v2/radar/nowcast_abc"}]},"satellite":{"infrared":[]}}""",
        )!!
        assertEquals("https://tilecache.rainviewer.com", host)
        assertEquals(3, frames.size)
        assertEquals(1789993200000L, frames[0].time)
        assertFalse(frames[1].forecast)
        assertTrue(frames[2].forecast)
        assertNull(parseRadarFrames("not json"))
    }

    @Test fun `a flight number is cleaned and its route read with its airports`() {
        assertEquals("AF1234", cleanFlight("af 1234"))
        assertEquals("EZY45AB", cleanFlight("ezy-45ab"))
        assertEquals(listOf("AMX45", "AMX045"), callsignForms("AMX45"))
        assertEquals(listOf("BAW468"), callsignForms("baw 468"))
        val r = parseFlightRoute(
            """{"response":{"flightroute":{"callsign":"AF1234","callsign_icao":"AFR1234","callsign_iata":"AF1234","airline":{"name":"Air France"},
            "origin":{"municipality":"Paris","name":"Charles de Gaulle International Airport","iata_code":"CDG","latitude":49.012798,"longitude":2.55},
            "destination":{"municipality":"Berlin","name":"Berlin Brandenburg Airport","iata_code":"BER","latitude":52.351389,"longitude":13.493889}}}}""",
        )!!
        assertEquals("AFR1234", r.callsign)
        assertEquals("BER", r.route.destinationCode)
        assertEquals(52.351389, r.destLat!!, 1e-9)
        assertNull(parseFlightRoute("""{"response":"unknown callsign"}"""))
    }

    private fun plane(lat: Double, lon: Double, ft: Double?, ground: Boolean = false, kt: Double? = 450.0) =
        Aircraft("39c4a1", "AFR1234", "F-HBXA", "E190", lat, lon, ft, ground, kt, 60.0, 0.0, "", "A3", "")

    @Test fun `the time left is the distance at the ground speed plus the landing`() {
        val route = FlightRoute("AFR1234", Route("Air France", "Paris", "", "CDG", "Berlin", "", "BER"), 49.0, 2.55, 52.35, 13.49)
        val a = plane(49.0, 2.55, 36_000.0)
        val km = com.jarvis.android.location.distanceKm(49.0, 2.55, 52.35, 13.49)
        assertEquals((km / (450 * 1.852) * 60 + 15).toInt(), minutesLeft(a, route))
        assertNull(minutesLeft(plane(49.0, 2.55, 0.0, ground = true, kt = 10.0), route))
    }

    @Test fun `a landing is told on the ground near the destination, or when it goes from view coming down close to it`() {
        val w = WatchedFlight("AF1234", "AFR1234", "Berlin (BER)", 52.35, 13.49, Long.MAX_VALUE)
        assertTrue(landed(w, plane(52.36, 13.50, null, ground = true, kt = 12.0)))
        assertFalse(landed(w, plane(49.0, 2.55, null, ground = true, kt = 12.0))) // still at the departure
        assertFalse(landed(w, plane(51.0, 11.0, 30_000.0)))
        assertFalse(landed(w, null)) // never seen
        val lost = w.copy(lastSeen = 1L, lastLat = 52.2, lastLon = 13.2, lastAltFt = 2_500.0, missed = 2)
        assertTrue(landed(lost, null))
        assertFalse(landed(lost.copy(missed = 1), null))
        assertFalse(landed(lost.copy(lastAltFt = 30_000.0), null)) // gone from view in cruise: out of reach, not landed
    }
}
