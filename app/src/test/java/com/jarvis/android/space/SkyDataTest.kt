package com.jarvis.android.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyDataTest {
    // shapes of the real answers of adsb.lol and adsbdb
    private val adsb = """{"ac":[
        {"hex":"4CA760","type":"adsb_icao","flight":"RYR51SF ","r":"EI-EFE","t":"B738","alt_baro":36150,"alt_geom":37475,"gs":442.2,"track":234.94,"baro_rate":832,"squawk":"1000","emergency":"none","category":"A3","lat":48.7,"lon":2.2},
        {"hex":"39e406","flight":"","r":"F-HZAG","t":"LXR","alt_baro":"ground","gs":3.1,"lat":48.9,"lon":1.7},
        {"hex":"abcdef","flight":"TEST1","squawk":"7700","alt_baro":1200,"lat":48.8,"lon":2.3},
        {"hex":"nopos","flight":"NOPOS"}]}"""

    @Test fun `aircraft are read with their units converted, the ones on the ground and without a position told apart`() {
        val list = parseAdsbLol(adsb)
        assertEquals(3, list.size)
        with(list[0]) {
            assertEquals("RYR51SF", label)
            assertEquals("4ca760", hex)
            assertEquals(11_018.5, altitudeM!!, 1.0)
            assertEquals(819.0, speedKmh!!, 1.0)
            assertEquals(4.23, verticalMs!!, 0.01)
            assertEquals("avion de ligne", categoryWords(category))
            assertEquals("", emergency)
        }
        assertTrue(list[1].onGround)
        assertEquals("F-HZAG", list[1].label)                 // no flight number: the registration
        assertEquals("urgence déclarée", squawkWords(list[2].squawk))
        assertTrue(parseAdsbLol("oops").isEmpty())
    }

    @Test fun `a flight's route and an aircraft's model are read from adsbdb`() {
        val route = parseRoute("""{"response":{"flightroute":{"callsign":"AFR1234","airline":{"name":"Air France"},
            "origin":{"municipality":"Paris","name":"Charles de Gaulle International Airport","iata_code":"CDG"},
            "destination":{"municipality":"Berlin","name":"Berlin Brandenburg Airport","iata_code":"BER"}}}}""")!!
        assertEquals("Air France", route.airline)
        assertEquals("CDG", route.originCode)
        assertEquals("Berlin", route.destinationCity)
        assertNull(parseRoute("""{"response":"unknown callsign"}"""))
        val info = parseAircraftInfo("""{"response":{"aircraft":{"type":"737NG 8AS/W","manufacturer":"Boeing","registered_owner":"Ryanair",
            "registered_owner_country_name":"Ireland","url_photo_thumbnail":"https://airport-data.com/t.jpg"}}}""")!!
        assertEquals("Boeing", info.manufacturer)
        assertEquals("Ryanair", info.owner)
        assertEquals("https://airport-data.com/t.jpg", info.photo)
        assertEquals("", parseAircraftInfo("""{"response":{"aircraft":{"url_photo_thumbnail":"http://plain.example/t.jpg"}}}""")!!.photo)
    }

    @Test fun `an aircraft high and close is high in the sky, one far away is low`() {
        val paris = Observer(48.8566, 2.3522)
        fun plane(lat: Double, lon: Double, ft: Double) = Aircraft("x", "X", "", "", lat, lon, ft, false, null, null, null, "", "", "")
        assertTrue(lookAtAircraft(paris, plane(48.86, 2.36, 30_000.0)).elevationDeg > 80)
        assertTrue(lookAtAircraft(paris, plane(49.5, 2.35, 30_000.0)).elevationDeg < 15)
        assertEquals("ISS", shortLabel("La Station spatiale internationale (ISS)"))
        assertEquals("Hubble", shortLabel("Le télescope spatial Hubble"))
    }
}
