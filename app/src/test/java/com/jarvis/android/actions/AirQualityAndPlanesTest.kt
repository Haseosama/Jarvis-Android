package com.jarvis.android.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AirQualityAndPlanesTest {
    // ── air quality ─────────────────────────────────────────────────────────

    @Test
    fun `the european index is put into its official bands`() {
        assertEquals("bon", describeEuropeanAqi(15))
        assertEquals("moyen", describeEuropeanAqi(40))
        assertEquals("dégradé", describeEuropeanAqi(55))
        assertEquals("mauvais", describeEuropeanAqi(70))
        assertEquals("très mauvais", describeEuropeanAqi(100))
        assertEquals("extrêmement mauvais", describeEuropeanAqi(130))
    }

    @Test
    fun `a report names the index, particles and pollen`() {
        val body = """{"current":{"european_aqi":34,"pm2_5":8.2,"pm10":14.0,"birch_pollen":0.0,"grass_pollen":22.5,"ragweed_pollen":null}}"""
        val text = formatAirQuality(body, "Lyon")
        assertTrue(text, text.startsWith("Qualité de l'air à Lyon : indice européen 34 (moyen)"))
        assertTrue(text.contains("PM2.5 8,2 µg/m³"))
        assertTrue(text.contains("graminées 22,5"))
        assertFalse("a null pollen is left out, not reported as zero", text.contains("ambroisie"))
    }

    @Test
    fun `outside Europe, with no pollen at all, the pollen sentence is dropped`() {
        val text = formatAirQuality("""{"current":{"european_aqi":12}}""", "Tokyo")
        assertEquals("Qualité de l'air à Tokyo : indice européen 12 (bon).", text)
    }

    @Test
    fun `a payload without the index is refused rather than reported as clean air`() {
        assertThrows(Exception::class.java) { formatAirQuality("""{"current":{"pm10":3}}""", "X") }
        assertThrows(Exception::class.java) { formatAirQuality("not json", "X") }
    }

    // ── planes ──────────────────────────────────────────────────────────────

    private val openSky = """
        {"time":1700000000,"states":[
          ["3c6444","DLH4AB  ","Germany",1700000000,1700000000,2.40,48.90,10668.0,false,230.0,90.0,0.0,null,11000.0,"1000",false,0],
          ["4ca1f2","RYR12   ","Ireland",1700000000,1700000000,2.36,48.86,3048.0,false,150.0,180.0,-5.0,null,3100.0,"2000",false,0],
          ["39de4f","AFR1    ","France",1700000000,1700000000,2.55,49.01,null,true,0.0,0.0,0.0,null,null,"3000",false,0],
          ["aaaaaa","        ","",1700000000,1700000000,null,null,5000.0,false,100.0,0.0,0.0,null,null,null,false,0]
        ]}
    """.trimIndent()

    @Test
    fun `OpenSky's positional arrays are read, and a row without a position is skipped`() {
        val planes = parseOpenSkyStates(openSky)
        assertEquals(3, planes.size)
        assertEquals("DLH4AB", planes[0].callsign)
        assertEquals("Germany", planes[0].country)
        assertTrue(planes[2].onGround)
    }

    @Test
    fun `an empty sky comes back as states null, not an error`() {
        assertTrue(parseOpenSkyStates("""{"time":1,"states":null}""").isEmpty())
    }

    @Test
    fun `only airborne aircraft are listed, nearest first`() {
        val text = formatPlanes(parseOpenSkyStates(openSky), 48.8566, 2.3522, 50, "Paris")
        assertTrue(text, text.startsWith("2 avion(s) en vol"))
        assertTrue("the nearer Ryanair comes before the Lufthansa", text.indexOf("RYR12") < text.indexOf("DLH4AB"))
        assertFalse("a plane on the ground is not flying overhead", text.contains("AFR1"))
        assertTrue(text.contains("10600 m"))
    }

    @Test
    fun `nothing within the radius says so plainly`() {
        val text = formatPlanes(parseOpenSkyStates(openSky), 43.3, 5.4, 20, "Marseille")
        assertTrue(text.startsWith("Aucun avion en vol"))
    }

    @Test
    fun `the bounding box widens in longitude as latitude grows`() {
        val equator = boundingBox(0.0, 0.0, 111.0)
        val north = boundingBox(60.0, 0.0, 111.0)
        assertEquals(1.0, equator[2] - 0.0, 0.01)
        assertTrue(north[3] - north[1] > equator[3] - equator[1])
    }

    @Test
    fun `distance is a real great-circle one`() {
        assertEquals(344.0, distanceKm(48.8566, 2.3522, 51.5074, -0.1278), 5.0) // Paris - London
    }
}
