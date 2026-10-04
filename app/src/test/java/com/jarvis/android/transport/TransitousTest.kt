package com.jarvis.android.transport

import com.jarvis.android.offline.OfflineAction
import com.jarvis.android.offline.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TransitousTest {
    private val paris = ZoneId.of("Europe/Paris")

    // Trimmed from a MOTIS stoptimes answer: a late bus with real time, a train on time with its track, a cancelled tram.
    private val board = """{"place":{"name":"Rennes Gare","lat":48.10,"lon":-1.67},"previousPageCursor":"","nextPageCursor":"",
      "stopTimes":[
        {"place":{"name":"Gare Sud","lat":48.1,"lon":-1.67,"departure":"2026-10-04T17:44:00Z","scheduledDeparture":"2026-10-04T17:42:00Z"},
         "mode":"BUS","realTime":true,"headsign":"Cesson Centre","displayName":"C6","routeShortName":"C6","tripShortName":"",
         "cancelled":false,"tripCancelled":false},
        {"place":{"name":"Rennes","lat":48.1,"lon":-1.67,"departure":"2026-10-04T17:50:00Z","scheduledDeparture":"2026-10-04T17:50:00Z","track":"3"},
         "mode":"REGIONAL_RAIL","realTime":true,"headsign":"Saint-Malo","displayName":"TER 857300","cancelled":false,"tripCancelled":false},
        {"place":{"name":"Gare","lat":48.1,"lon":-1.67,"scheduledDeparture":"2026-10-04T17:41:00Z"},
         "mode":"SUBWAY","realTime":true,"headsign":"","tripTo":{"name":"J.F. Kennedy"},"displayName":"a","cancelled":false,"tripCancelled":true},
        {"place":{"name":"Gare"},"mode":"BUS","realTime":false,"headsign":"Sans heure"}
      ]}"""

    @Test
    fun `a stoptimes answer gives the departures in time order`() {
        val b = parseStopTimes(board)!!
        assertEquals("Rennes Gare", b.stop)
        assertEquals(listOf("a", "C6", "TER 857300"), b.departures.map { it.line })
        val metro = b.departures[0]
        assertTrue(metro.cancelled)
        assertEquals("J.F. Kennedy", metro.headsign)
        val bus = b.departures[1]
        assertEquals(Instant.parse("2026-10-04T17:44:00Z"), bus.time)
        assertEquals(Instant.parse("2026-10-04T17:42:00Z"), bus.planned)
        assertEquals("3", b.departures[2].track)
        assertNull(parseStopTimes("pas du json"))
        assertEquals(0, parseStopTimes("""{"stopTimes":[]}""")!!.departures.size)
    }

    @Test
    fun `each departure said with its wait, its delay and its track`() {
        val b = parseStopTimes(board)!!
        val now = Instant.parse("2026-10-04T17:40:00Z")
        assertEquals("19 h 41 (supprimé) Métro a vers J.F. Kennedy", describeLiveDeparture(b.departures[0], now, paris))
        assertEquals("19 h 44 (dans 4 min, retard 2 min) Bus C6 vers Cesson Centre", describeLiveDeparture(b.departures[1], now, paris))
        assertEquals("19 h 50 (dans 10 min) TER 857300 vers Saint-Malo, voie 3", describeLiveDeparture(b.departures[2], now, paris))
        // Further than an hour: the time alone.
        assertEquals("19 h 50 TER 857300 vers Saint-Malo, voie 3", describeLiveDeparture(b.departures[2], Instant.parse("2026-10-04T16:00:00Z"), paris))
    }

    @Test
    fun `the board keeps only the kind asked`() {
        val b = parseStopTimes(board)!!
        val now = Instant.parse("2026-10-04T17:40:00Z")
        val trains = describeBoard(b, TransitKind.TRAIN, now, paris)!!
        assertEquals("Prochains départs de Rennes Gare :\n19 h 50 (dans 10 min) TER 857300 vers Saint-Malo, voie 3", trains)
        val local = describeBoard(b, TransitKind.LOCAL, now, paris)!!
        assertEquals(3, local.lines().size)
        assertFalse(local.contains("TER"))
        assertEquals(4, describeBoard(b, TransitKind.ANY, now, paris)!!.lines().size)
        // All gone already.
        assertNull(describeBoard(b, TransitKind.ANY, Instant.parse("2026-10-04T19:00:00Z"), paris))
    }

    @Test
    fun `timetables without real time are said to be planned`() {
        val body = """{"place":{"name":"Mairie"},"stopTimes":[{"place":{"name":"Mairie","scheduledDeparture":"2026-10-04T08:00:00Z",
            "departure":"2026-10-04T08:00:00Z"},"mode":"BUS","realTime":false,"headsign":"Lycée","displayName":"12"}]}"""
        val said = describeBoard(parseStopTimes(body)!!, TransitKind.ANY, Instant.parse("2026-10-04T07:30:00Z"), paris)!!
        assertEquals("Prochains départs de Mairie (horaires prévus : pas de temps réel sur ce réseau) :\n10 h (dans 30 min) Bus 12 vers Lycée", said)
    }

    @Test
    fun `lines in words`() {
        assertEquals("Bus 12", lineWords("BUS", "12"))
        assertEquals("Tram A", lineWords("TRAM", "A"))
        assertEquals("Tram T2", lineWords("TRAM", "Tram T2"))
        assertEquals("Métro 1", lineWords("SUBWAY", "1"))
        assertEquals("RER B", lineWords("SUBURBAN", "RER B"))
        assertEquals("TGV INOUI 8704", lineWords("HIGHSPEED_RAIL", "TGV INOUI 8704"))
        assertEquals("Train K12", lineWords("REGIONAL_RAIL", "K12"))
        assertEquals("Train", lineWords("RAIL", ""))
        assertEquals("Navette", lineWords("OTHER", "Navette"))
    }

    @Test
    fun `the stops of a geocode answer, nearest first and those serving the kind asked before`() {
        val body = """[
          {"type":"STOP","id":"fr-bus_1","name":"République","lat":48.1100,"lon":-1.6790,"tokens":[],"areas":[],"score":1,"modes":["BUS"]},
          {"type":"ADDRESS","id":"a","name":"1 rue de la Gare","lat":48.1,"lon":-1.67,"tokens":[],"areas":[],"score":1},
          {"type":"STOP","id":"fr-sncf_87471003","name":"Rennes","lat":48.1035,"lon":-1.6722,"tokens":[],"areas":[],"score":1,"modes":["HIGHSPEED_RAIL","REGIONAL_RAIL"]},
          {"type":"STOP","id":"far","name":"Loin","lat":48.5,"lon":-1.67,"tokens":[],"areas":[],"score":1},
          {"type":"STOP","id":"fr-bus_2","name":"Gares","lat":48.1040,"lon":-1.6725,"tokens":[],"areas":[],"score":1}
        ]"""
        val stops = parseTransitStops(body)
        assertEquals(listOf("fr-bus_1", "fr-sncf_87471003", "far", "fr-bus_2"), stops.map { it.id })
        assertEquals(setOf("BUS"), stops[0].modes)
        // From near the station: the train station first for trains, the plain nearest for anything; "Loin" is too far.
        val trains = stopsToTry(stops, 48.1036, -1.6723, TransitKind.TRAIN, maxKm = 3.0)
        assertEquals(listOf("fr-sncf_87471003", "fr-bus_2", "fr-bus_1"), trains.map { it.id })
        val any = stopsToTry(stops, 48.1041, -1.6726, TransitKind.ANY, maxKm = 3.0)
        assertEquals(listOf("fr-bus_2", "fr-sncf_87471003", "fr-bus_1"), any.map { it.id })
        assertEquals(listOf("fr-bus_2", "fr-bus_1", "fr-sncf_87471003"), stopsToTry(stops, 48.1041, -1.6726, TransitKind.LOCAL, 3.0).map { it.id })
        assertTrue(parseTransitStops("{}").isEmpty())
    }

    @Test
    fun `offline, the next departures and the next bus`() {
        val near = interpret("Les prochains départs") as OfflineAction.ToolCall
        assertEquals("transport", near.name)
        assertEquals(mapOf("action" to "departures"), near.args)
        val named = interpret("Prochains départs de la gare de Brest") as OfflineAction.ToolCall
        assertEquals(mapOf("action" to "departures", "station" to "brest"), named.args)
        val bus = interpret("Quand passe mon bus ?") as OfflineAction.ToolCall
        assertEquals(mapOf("action" to "departures", "network" to "local"), bus.args)
        assertEquals(mapOf("action" to "departures", "network" to "local"), (interpret("Le prochain tram passe quand ?") as OfflineAction.ToolCall).args)
    }
}
