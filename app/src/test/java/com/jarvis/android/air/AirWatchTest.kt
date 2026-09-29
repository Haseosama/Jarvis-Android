package com.jarvis.android.air

import com.jarvis.android.actions.formatAirQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AirWatchTest {
    private fun p(key: String) = POLLENS.first { it.key == key }

    @Test fun `pollen levels`() {
        assertEquals(0, pollenLevel(p("grass_pollen"), 0.4))
        assertEquals(1, pollenLevel(p("grass_pollen"), 12.0))
        assertEquals(2, pollenLevel(p("grass_pollen"), 22.5))
        assertEquals(3, pollenLevel(p("grass_pollen"), 120.0))
        assertEquals(4, pollenLevel(p("grass_pollen"), 250.0))
        assertEquals(3, pollenLevel(p("ragweed_pollen"), 25.0)) // ragweed is high sooner
        assertEquals("très élevé", levelWords(4))
    }

    @Test fun `the pollens are told with their level, the notable ones only`() {
        val text = formatAirQuality("""{"current":{"european_aqi":34,"pm2_5":8.2,"grass_pollen":22.5,"birch_pollen":0.0,"mugwort_pollen":55.0,"ragweed_pollen":null}}""", "Lyon")
        assertTrue(text, text.contains("Pollens (grains/m³) : armoise 55,0 (élevé), graminées 22,5 (moyen)."))
        assertTrue(text, !text.contains("bouleau"))
        val none = formatAirQuality("""{"current":{"european_aqi":12,"grass_pollen":0.2}}""", "Paris")
        assertTrue(none, none.endsWith("Pas de pollen notable."))
    }

    @Test fun `the next days at their worst`() {
        val json = """{"hourly":{"time":["2026-09-29T06:00","2026-09-29T12:00","2026-09-29T18:00","2026-09-30T12:00","2026-09-30T23:00"],
            "european_aqi":[90,45,38,22,95],"grass_pollen":[0,60,10,5,300],"birch_pollen":[0,0,0,0,0]}}"""
        assertEquals(
            "Aujourd’hui : air dégradé au pire (indice 45), pollens : graminées élevé. Demain : air moyen au pire (indice 22), pas de pollen notable.",
            airForecastWords(json, LocalDate.of(2026, 9, 29)),
        ) // 6 h and 23 h are outside the day's hours
    }

    @Test fun `the watch speaks of bad air or high pollens, those asked for`() {
        val body = """{"current":{"european_aqi":72,"grass_pollen":80.0,"birch_pollen":120.0}}"""
        assertEquals("Air mauvais (indice 72), pollen de bouleau élevé (120 grains/m³), pollen de graminées élevé (80 grains/m³) à votre position.", airAlertWords(body, 60, emptySet()))
        assertEquals("Pollen de graminées élevé (80 grains/m³) à votre position.", airAlertWords(body, 80, setOf("grass_pollen")))
        assertNull(airAlertWords("""{"current":{"european_aqi":30,"grass_pollen":10.0}}""", 60, emptySet()))
    }

    @Test fun `the grid of many places`() {
        val cells = parseAirGrid("""[{"latitude":48.8,"longitude":2.4,"current":{"european_aqi":32}},{"latitude":49.1,"longitude":2.9,"current":{"european_aqi":null}}]""")
        assertEquals(listOf(AirCell(48.8, 2.4, 32)), cells)
    }
}
