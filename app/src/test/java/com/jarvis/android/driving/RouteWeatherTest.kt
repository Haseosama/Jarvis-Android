package com.jarvis.android.driving

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class RouteWeatherTest {
    private val paris = ZoneId.of("Europe/Paris")

    @Test fun `what a driver is told`() {
        assertEquals(listOf("orage"), roadHazards(95, 6.0, 0.0, 18.0, 40.0, 20_000.0))
        assertEquals(listOf("neige", "risque de verglas"), roadHazards(73, 0.5, 1.2, -1.0, 20.0, 5_000.0))
        assertEquals(listOf("pluie verglaçante"), roadHazards(66, 0.8, 0.0, 0.0, 10.0, 8_000.0))
        assertEquals(listOf("brouillard givrant", "risque de verglas"), roadHazards(48, 0.0, 0.0, -2.0, 5.0, 300.0))
        assertEquals(listOf("forte pluie", "rafales à 85 km/h"), roadHazards(65, 5.5, 0.0, 12.0, 85.0, 9_000.0))
        assertEquals(listOf("pluie"), roadHazards(61, 1.2, 0.0, 14.0, 30.0, 20_000.0))
        assertEquals(listOf("pluie faible"), roadHazards(3, 0.3, 0.0, 16.0, 25.0, 24_000.0))
        assertEquals(listOf("averses faibles"), roadHazards(80, 0.1, 0.0, 16.0, 25.0, 24_000.0))
        assertEquals(listOf("averses"), roadHazards(81, 2.0, 0.0, 16.0, 25.0, 24_000.0))
        assertTrue(roadHazards(2, 0.1, 0.0, 16.0, 25.0, 24_000.0).isEmpty())
    }

    @Test fun `points every 40 km with the time the car is there`() {
        // a straight line north along the meridian, about 111 km a degree: 3 degrees, 3 hours
        val line = (0..300).map { 2.0 to 45.0 + it / 100.0 }
        val pts = samplePoints(line, 3 * 3600.0, 0L)
        assertTrue(pts.size in 8..10)
        assertEquals(0.0, pts.first().km, 1e-9)
        assertEquals(48.0, pts.last().lat, 1e-9)
        assertEquals(3 * 3_600_000L, pts.last().etaMs)
        assertTrue(pts.zipWithNext().all { (a, b) -> b.km - a.km in 39.0..45.0 || b == pts.last() })
        // a long drive is cut in 15 points at most
        assertTrue(samplePoints((0..2000).map { 2.0 to 40.0 + it / 100.0 }, 36_000.0, 0L).size <= 16)
    }

    @Test fun `the forecast of several places is read at each one's hour`() {
        val pts = listOf(RoutePoint(48.85, 2.35, 0.0, java.time.Instant.parse("2026-09-29T14:20:00Z").toEpochMilli()), RoutePoint(47.3, 3.5, 160.0, java.time.Instant.parse("2026-09-29T16:05:00Z").toEpochMilli()))
        fun place(h: String) = """{"latitude":1,"hourly":{"time":["2026-09-29T14:00","2026-09-29T15:00","2026-09-29T16:00"],"precipitation":[0.0,0.3,$h],"weather_code":[1,61,95],"temperature_2m":[18.0,17.0,16.0],"wind_gusts_10m":[20,30,50],"visibility":[24000,20000,9000],"snowfall":[0,0,0]}}"""
        val w = parseRouteForecast("[${place("0.0")},${place("7.0")}]", pts) { if (it.km == 0.0) "Paris" else "Auxerre" }
        assertEquals(2, w.size)
        assertTrue(w[0].hazards.isEmpty()) // 14h: dry
        assertEquals(listOf("orage"), w[1].hazards) // 16h: storm
        assertEquals("Auxerre", w[1].place)
    }

    @Test fun `the report in words`() {
        val t0 = java.time.ZonedDateTime.of(2026, 9, 29, 14, 0, 0, 0, paris).toInstant().toEpochMilli()
        fun pw(km: Double, min: Long, code: Int, rain: Double, place: String?) = PointWeather(RoutePoint(0.0, 0.0, km, t0 + min * 60_000L), place, code, rain, 0.0, 15.0, 20.0, 20_000.0)
        val r = RouteReport("Paris", "Lyon", emptyList(), 466.0, 17_880.0, listOf(pw(0.0, 0, 1, 0.0, "Paris"), pw(160.0, 95, 61, 1.5, "Auxerre"), pw(200.0, 120, 61, 1.8, null), pw(390.0, 230, 95, 5.0, "Mâcon")))
        val w = routeWords(r, paris)
        assertEquals("Trajet Paris → Lyon : 466 km, 4 h 58 sans les bouchons. Pluie vers Auxerre vers 15 h 30 ; orage vers Mâcon vers 17 h 30. Ailleurs, rien de gênant. Températures de 15 à 15 °C.", w)
        assertTrue(routeWords(r.copy(points = listOf(pw(0.0, 0, 1, 0.0, "Paris"))), paris).contains("Pas de pluie ni de danger"))
    }

    @Test fun `the departure time asked for`() {
        val now = java.time.ZonedDateTime.of(2026, 9, 29, 14, 0, 0, 0, paris).toInstant().toEpochMilli()
        assertEquals(now + 2 * 3_600_000L, departureMs("dans 2 h", now, paris))
        assertEquals(now + 45 * 60_000L, departureMs("dans 45 minutes", now, paris))
        assertEquals(java.time.ZonedDateTime.of(2026, 9, 29, 18, 30, 0, 0, paris).toInstant().toEpochMilli(), departureMs("18h30", now, paris))
        assertEquals(java.time.ZonedDateTime.of(2026, 9, 30, 7, 0, 0, 0, paris).toInstant().toEpochMilli(), departureMs("7h", now, paris)) // tomorrow
        assertEquals(now, departureMs("", now, paris))
    }
}
