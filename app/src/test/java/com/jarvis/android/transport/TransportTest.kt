package com.jarvis.android.transport

import com.jarvis.android.offline.OfflineAction
import com.jarvis.android.offline.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TransportTest {
    @Test
    fun `the stop area of a places answer`() {
        val body = """{"places":[{"id":"admin:fr:35238","embedded_type":"administrative_region","name":"Rennes"},
            {"id":"stop_area:SNCF:87471003","embedded_type":"stop_area","name":"Rennes (Rennes)"}]}"""
        assertEquals(Stop("stop_area:SNCF:87471003", "Rennes (Rennes)"), parseStopArea(body))
        assertEquals("Brest", parseStopArea("""{"places_nearby":[{"id":"stop_area:SNCF:87474007","embedded_type":"stop_area","name":"Brest"}]}""")?.name)
        assertNull(parseStopArea("""{"places":[]}"""))
        assertNull(parseStopArea("pas du json"))
    }

    @Test
    fun `journeys give the trains, the changes and the delay`() {
        val body = """{"journeys":[
          {"departure_date_time":"20260926T070500","arrival_date_time":"20260926T083500","nb_transfers":0,"status":"",
           "sections":[{"type":"street_network"},{"type":"public_transport","base_departure_date_time":"20260926T070500","departure_date_time":"20260926T070500",
             "display_informations":{"commercial_mode":"TGV INOUI","headsign":"8704","direction":"Paris Montparnasse"}}]},
          {"departure_date_time":"20260926T074200","arrival_date_time":"20260926T100100","nb_transfers":1,"status":"SIGNIFICANT_DELAYS",
           "sections":[{"type":"public_transport","base_departure_date_time":"20260926T073500","departure_date_time":"20260926T074200",
             "display_informations":{"commercial_mode":"TER","headsign":"857412","direction":"Nantes"}},{"type":"transfer"},
             {"type":"public_transport","display_informations":{"commercial_mode":"OUIGO","headsign":"7810"}}]}]}"""
        val j = parseJourneys(body)
        assertEquals(2, j.size)
        assertEquals("7 h 05 → 8 h 35 (1 h 30, direct, TGV INOUI 8704)", describeJourney(j[0]))
        assertEquals("7 h 42 → 10 h 01 (2 h 19, 1 correspondance, TER 857412 puis OUIGO 7810, retard de 7 min)", describeJourney(j[1]))
        assertTrue(j[1].disrupted)
        assertTrue(parseJourneys("{}").isEmpty())
    }

    @Test
    fun `departures with their delay`() {
        val body = """{"departures":[{"stop_date_time":{"departure_date_time":"20260926T071700","base_departure_date_time":"20260926T071200"},
            "display_informations":{"commercial_mode":"TER","headsign":"857300","direction":"Quimper (Quimper)"}},
            {"stop_date_time":{"departure_date_time":"20260926T073000"},"display_informations":{"commercial_mode":"Bus","code":"C1","direction":"Brest Port"}}]}"""
        val d = parseDepartures(body)
        assertEquals("7 h 17 (retard 5 min) TER 857300 vers Quimper", describeDeparture(d[0]))
        assertEquals("7 h 30 Bus C1 vers Brest Port", describeDeparture(d[1]))
        assertEquals("20260926T071200", toNavitia(LocalDateTime.of(2026, 9, 26, 7, 12)))
    }

    @Test
    fun `offline, the next train to a city`() {
        val a = interpret("Quel est le prochain train pour Rennes ?") as OfflineAction.ToolCall
        assertEquals("transport", a.name)
        assertEquals(mapOf("action" to "journey", "to" to "rennes"), a.args)
    }
}
