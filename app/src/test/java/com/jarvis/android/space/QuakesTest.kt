package com.jarvis.android.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuakesTest {
    private val feed = """{"type":"FeatureCollection","metadata":{"count":2},"features":[
        {"type":"Feature","properties":{"mag":4.73,"place":"100 km W of Petrolia, CA","time":1790681797910,"url":"https://earthquake.usgs.gov/earthquakes/eventpage/nc75444322","felt":7,"alert":"green","tsunami":0},
         "geometry":{"type":"Point","coordinates":[-125.4598,40.433,2.28]},"id":"nc75444322"},
        {"type":"Feature","properties":{"mag":6.8,"place":"Kermadec Islands region","time":1790600000000,"url":"u","felt":null,"alert":"yellow","tsunami":1},
         "geometry":{"type":"Point","coordinates":[-177.5,-29.8,35.0]},"id":"us7000abcd"},
        {"type":"Feature","properties":{"mag":null,"place":"x","time":1},"geometry":{"type":"Point","coordinates":[0,0,0]},"id":"bad"}]}"""

    @Test fun `the USGS feed is read`() {
        val q = parseQuakes(feed)
        assertEquals(2, q.size)
        assertEquals(4.73, q[0].mag, 1e-9)
        assertEquals(40.433, q[0].lat, 1e-9)
        assertEquals(2.28, q[0].depthKm, 1e-9)
        assertEquals("green", q[0].alert)
        assertTrue(q[1].tsunami)
        assertTrue(parseQuakes("nope").isEmpty())
    }

    @Test fun `in French words`() {
        assertEquals("à 100 km à l’ouest de Petrolia, CA", placeWords("100 km W of Petrolia, CA"))
        assertEquals("à 12 km au nord-nord-est de Tokyo, Japan", placeWords("12 km NNE of Tokyo, Japan"))
        assertEquals("Kermadec Islands region", placeWords("Kermadec Islands region"))
        assertEquals("léger", magnitudeWords(4.73))
        assertEquals("fort", magnitudeWords(6.8))
        val q = parseQuakes(feed)
        val w = quakeWords(q[1], q[1].timeMs + 2 * 3_600_000L)
        assertEquals("séisme de magnitude 6,8 (fort) Kermadec Islands region, il y a 2 h, 35 km de profondeur, alerte tsunami émise, dégâts possibles (alerte jaune)", w)
    }

    @Test fun `told when felt near a watched place, the reach growing with the magnitude, once`() {
        val q = parseQuakes(feed)
        val eureka = WatchedPlace("Eureka (mes cousins)", 40.80, -124.16) // about 120 km from the M4.7
        val paris = WatchedPlace("vous", 48.85, 2.35)
        val tell = quakesToTell(q, listOf(paris, eureka), 4.0, emptySet())
        assertEquals(listOf("nc75444322" to "Eureka (mes cousins)"), tell.map { it.first.id to it.second.name })
        assertTrue(quakesToTell(q, listOf(paris), 4.0, emptySet()).isEmpty())
        assertTrue(quakesToTell(q, listOf(eureka), 5.0, emptySet()).isEmpty()) // under the magnitude asked
        assertTrue(quakesToTell(q, listOf(eureka), 4.0, setOf("nc75444322")).isEmpty()) // already told
        // a strong one is felt far: 6.8 reaches about 1,300 km
        val auckland = WatchedPlace("Auckland", -36.85, 174.76)
        assertFalse(quakesToTell(q, listOf(auckland), 4.0, emptySet()).isEmpty())
    }
}
