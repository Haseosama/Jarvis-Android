package com.jarvis.android.air

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AtmoTest {
    private val today = LocalDate.of(2026, 10, 5)

    private fun feature(day: String, qual: Int, lib: String, o3: Int = 1, no2: Int = 1, pm10: Int = 1, pm25: Int = 1, so2: Int = 1, zone: String = "69123") =
        """{"type":"Feature","properties":{"code_zone":"$zone","lib_zone":"Lyon","date_ech":"$day","code_qual":$qual,"lib_qual":"$lib","coul_qual":"#50CCAA",""" +
            """"code_no2":$no2,"code_o3":$o3,"code_pm10":$pm10,"code_pm25":$pm25,"code_so2":$so2,"source":"Atmo AuRA","type_zone":"commune"}}"""

    private fun collection(vararg f: String) = """{"type":"FeatureCollection","features":[${f.joinToString(",")}]}"""

    @Test fun `today's index is picked among the days published`() {
        val json = collection(feature("2026-10-04", 3, "Dégradé", o3 = 3), feature("2026-10-05Z", 2, "Moyen", o3 = 2, pm25 = 2), feature("2026-10-06", 1, "Bon"))
        val d = parseAtmo(json, "69123", today)!!
        assertEquals(2, d.code)
        assertEquals("moyen", d.label)
        assertEquals(listOf("de l’ozone", "des particules fines PM2,5"), d.pollutants)
        assertEquals("air du jour : moyen (indice Atmo 2 sur 6)", atmoBriefingWords(d))
    }

    @Test fun `from degraded on, the pollutants responsible are named`() {
        val d = parseAtmo(collection(feature("2026-10-05", 4, "Mauvais", o3 = 4, no2 = 4, pm10 = 2)), "69123", today)!!
        assertEquals("air du jour : mauvais (indice Atmo 4 sur 6, à cause de l’ozone et du dioxyde d’azote)", atmoBriefingWords(d))
        val one = parseAtmo(collection(feature("2026-10-05", 3, "Dégradé", pm10 = 3)), "69123", today)!!
        assertEquals("air du jour : dégradé (indice Atmo 3 sur 6, à cause des particules PM10)", atmoBriefingWords(one))
    }

    @Test fun `no index when not published, another commune, or no data`() {
        assertNull(parseAtmo(collection(feature("2026-10-04", 2, "Moyen")), "69123", today))
        assertNull(parseAtmo(collection(feature("2026-10-05", 2, "Moyen", zone = "75056")), "69123", today))
        assertNull(parseAtmo(collection(feature("2026-10-05", 0, "Absent")), "69123", today))
        assertNull(parseAtmo(collection(feature("2026-10-05", 7, "Evénement")), "69123", today))
        assertNull(parseAtmo("""{"type":"FeatureCollection","features":[]}""", "69123", today))
        assertEquals("bon", parseAtmo(collection(feature("2026-10-05", 1, "")), "69123", today)!!.label)
    }

    @Test fun `the day of a date given as an instant is the day in Paris`() {
        assertEquals(today, atmoDate("2026-10-04T22:00:00Z"))
        assertEquals(today, atmoDate("2026-10-05"))
        assertEquals(today, atmoDate("2026-10-05Z"))
        assertNull(atmoDate("demain"))
    }

    @Test fun `the commune's code and the query asked`() {
        assertEquals("69123", parseCommuneCode("""[{"code":"69123","nom":"Lyon"}]"""))
        assertNull(parseCommuneCode("[]"))
        val url = atmoUrl("69123")
        assertTrue(url, url.startsWith("https://data.atmo-france.org/geoserver/ind/ows?"))
        assertTrue(url, url.endsWith("CQL_FILTER=code_zone%3D%2769123%27"))
        assertTrue(atmoUrl("69'; x").endsWith("%2769x%27"))
    }
}
