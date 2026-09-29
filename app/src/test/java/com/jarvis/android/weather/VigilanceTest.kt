package com.jarvis.android.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class VigilanceTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val feed = """{"warnings":[
      {"alert":{"msgType":"Alert","info":[
        {"language":"fr-FR","event":"Vigilance orange orages","severity":"Severe","onset":"2026-09-29T14:00:00+02:00","expires":"2026-09-30T06:00:00+02:00",
         "instruction":"Limitez vos déplacements. Abritez-vous hors des zones boisées.",
         "parameter":[{"valueName":"awareness_level","value":"3; orange; Severe"},{"valueName":"awareness_type","value":"3; Thunderstorm"}],
         "area":[{"areaDesc":"Hérault"},{"areaDesc":"Gard"}]},
        {"language":"en-GB","event":"Severe thunderstorm warning","parameter":[{"valueName":"awareness_level","value":"3; orange; Severe"}],"area":[{"areaDesc":"Hérault"}]}]}},
      {"alert":{"msgType":"Update","info":[
        {"language":"fr-FR","event":"Vigilance jaune orages","onset":"2026-09-28T00:00:00+02:00","expires":"2026-09-28T00:00:00+02:00",
         "parameter":[{"valueName":"awareness_level","value":"1; green; Minor"}],"area":[{"areaDesc":"Gironde"}]}]}},
      {"alert":{"msgType":"Alert","info":[
        {"language":"fr-FR","event":"Vigilance jaune pluie-inondation","onset":"2026-09-29T12:00:00+02:00","expires":"2026-09-30T12:00:00+02:00",
         "parameter":[{"valueName":"awareness_level","value":"2; yellow; Moderate"}],"area":[{"areaDesc":"Hérault"}]}]}}]}"""

    @Test fun `MeteoAlarm's French warnings, for a département, in force`() {
        val all = parseMeteoAlarm(feed)
        assertEquals(3, all.size) // the French texts only
        val now = ms("2026-09-29T16:00:00Z")
        val herault = warningsFor(all, "Hérault", now)
        assertEquals(listOf("Vigilance orange orages", "Vigilance jaune pluie-inondation"), herault.map { it.event })
        assertTrue(warningsFor(all, "Gironde", now).isEmpty()) // back to green
        assertTrue(warningsFor(all, "Paris", now).isEmpty())
        val w = Vigilance.words("Hérault", herault, emptyList(), ZoneId.of("Europe/Paris"))
        assertEquals("Hérault : vigilance orange orages jusqu’à mercredi 06h ; vigilance jaune pluie-inondation jusqu’à mercredi 12h. Conseil : Limitez vos déplacements. Pas de vigilance crues sur les rivières proches.", w)
    }

    @Test fun `Vigicrues' sections and those near a place`() {
        val json = """{"type":"FeatureCollection","features":[
          {"properties":{"lbentcru":"Seine parisienne","NivInfViCr":2},"geometry":{"type":"MultiLineString","coordinates":[[[2.30,48.86],[2.35,48.85]],[[2.40,48.84],[2.45,48.83]]]}},
          {"properties":{"lbentcru":"Loire tourangelle","NivInfViCr":3},"geometry":{"type":"LineString","coordinates":[[0.68,47.39],[0.70,47.40]]}},
          {"properties":{"lbentcru":"Marne","NivInfViCr":1},"geometry":{"type":"LineString","coordinates":[[2.50,48.85]]}}]}"""
        val all = parseVigicrues(json)
        assertEquals(3, all.size)
        assertEquals(4, all[0].line.size)
        val near = floodsNear(all, 48.8566, 2.3522)
        assertEquals(listOf("Seine parisienne"), near.map { it.first.name }) // the Loire is far, the Marne is green
        assertTrue(near[0].second < 1.0)
        assertEquals(2, parseVigicrues(json, minLevel = 2).size)
        assertEquals("orange", levelColour(3))
    }
}
