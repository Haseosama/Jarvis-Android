package com.jarvis.android.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FuelPriceToolTest {
    private val now = Instant.parse("2026-09-25T12:00:00Z")

    @Test
    fun `fuels are understood the ways people say them`() {
        assertEquals(Fuel.GAZOLE, parseFuel("diesel"))
        assertEquals(Fuel.GAZOLE, parseFuel("Gazole"))
        assertEquals(Fuel.GAZOLE, parseFuel("gazole_prix"))
        assertEquals(Fuel.SP98, parseFuel("sans plomb 98"))
        assertEquals(Fuel.SP95, parseFuel("SP95"))
        assertEquals(Fuel.E10, parseFuel("SP95-E10"))
        assertEquals(Fuel.E85, parseFuel("superéthanol"))
        assertEquals(Fuel.GPLC, parseFuel("GPL"))
        assertNull(parseFuel("kérosène"))
    }

    // Shape of the real feed (data.economie.gouv.fr, prix-des-carburants-en-france-flux-instantane-v2).
    private val body = """
        {"total_count": 5, "results": [
          {"adresse": "1 RUE A", "ville": "Chazelles-sur-Lyon", "cp": "42140", "geom": {"lon": 4.38, "lat": 45.64}, "carburants_indisponibles": null, "gazole_prix": 2.10, "gazole_maj": "2026-09-25T08:00:00+00:00"},
          {"adresse": "2 RUE B", "ville": "Lyon", "cp": "69007", "geom": {"lon": 4.83, "lat": 45.73}, "carburants_indisponibles": ["Gazole"], "gazole_prix": 2.15, "gazole_maj": "2026-09-25T08:00:00+00:00"},
          {"adresse": "3 RUE C", "ville": "Lyon", "cp": "69008", "geom": {"lon": 4.87, "lat": 45.73}, "carburants_indisponibles": ["SP95"], "gazole_prix": 2.20, "gazole_maj": "2026-09-01T08:00:00+00:00"},
          {"adresse": "4 RUE D", "ville": "LYON", "cp": "69003", "geom": {"lon": 4.85, "lat": 45.76}, "carburants_indisponibles": [], "gazole_prix": 2.25, "gazole_maj": "2026-09-25T09:00:00+00:00"},
          {"adresse": "5 RUE E", "ville": "Lyon", "cp": "69009", "geom": {"lon": 4.80, "lat": 45.77}, "gazole_prix": 2.30, "gazole_maj": "2026-09-24T09:00:00+00:00"}
        ]}
    """.trimIndent()

    @Test
    fun `another town sharing the name, a station out of the fuel and a stale price are all left out`() {
        val ranked = rankStations(parseStations(body, Fuel.GAZOLE), Fuel.GAZOLE, now, "Lyon", null)
        assertEquals(listOf("4 RUE D", "5 RUE E"), ranked.map { it.address })
    }

    @Test
    fun `the answer gives the price with three decimals, the address and how old the price is`() {
        val ranked = rankStations(parseStations(body, Fuel.GAZOLE), Fuel.GAZOLE, now, "Lyon", null)
        val text = formatStations(ranked, Fuel.GAZOLE, "à Lyon", now, null)
        assertTrue(text, text.startsWith("Gazole le moins cher à Lyon :\n1. 2,250 €/L — 4 RUE D, 69003 LYON (prix mis à jour il y a 3 h)"))
        assertTrue(text.contains("2. 2,300 €/L — 5 RUE E, 69009 Lyon (prix mis à jour il y a 27 h)"))
    }

    @Test
    fun `around the phone, the distance is given and the city is not filtered`() {
        val origin = 45.76 to 4.85
        val ranked = rankStations(parseStations(body, Fuel.GAZOLE), Fuel.GAZOLE, now, null, origin)
        assertEquals("1 RUE A", ranked.first().address) // cheapest; no city filter when searching by distance
        val text = formatStations(ranked.drop(1), Fuel.GAZOLE, "près de vous", now, origin)
        assertTrue(text, text.contains("4 RUE D, 69003 LYON, à 0,0 km"))
    }

    @Test
    fun `nothing left says so rather than listing unavailable stations`() {
        assertEquals("Aucune station avec du E85 disponible et un prix récent à Lyon.", formatStations(emptyList(), Fuel.E85, "à Lyon", now, null))
        assertTrue(parseStations("""{"total_count":0,"results":[]}""", Fuel.E85).isEmpty())
    }
}
