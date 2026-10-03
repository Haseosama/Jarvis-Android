package com.jarvis.android.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NearbyPlacesTest {
    // Saturday 3 October 2026, 13:00, place Bellecour in Lyon
    private val now = LocalDateTime.of(2026, 10, 3, 13, 0)
    private val lat = 45.7578
    private val lon = 4.8320
    private fun direction(bearing: Double) = "cap ${bearing.toInt()}"

    @Test
    fun `what is looked for is understood the ways people say it`() {
        assertEquals(PlaceKind.PHARMACY, parsePlaceKind("la pharmacie la plus proche"))
        assertEquals(PlaceKind.BAKERY, parsePlaceKind("où acheter du pain"))
        assertEquals(PlaceKind.BAKERY, parsePlaceKind("Boulangerie"))
        assertEquals(PlaceKind.ATM, parsePlaceKind("un DAB"))
        assertEquals(PlaceKind.ATM, parsePlaceKind("retirer de l'argent"))
        assertEquals(PlaceKind.TOILETS, parsePlaceKind("des WC"))
        assertEquals(PlaceKind.TOILETS, parsePlaceKind("toilettes publiques"))
        assertEquals(PlaceKind.CHARGER, parsePlaceKind("une borne de recharge pour la voiture"))
        assertEquals(PlaceKind.CHARGER, parsePlaceKind("recharger ma voiture électrique"))
        assertNull(parsePlaceKind("un restaurant"))
    }

    @Test
    fun `the query asks for every element of the kind with its centre, then the streets without their tags`() {
        val q = overpassQuery(PlaceKind.ATM, lat, lon, 1000, 800)
        assertTrue(q, q.startsWith("[out:json][timeout:20];("))
        assertTrue(q, q.contains("nwr[\"amenity\"=\"atm\"](around:1000,45.757800,4.832000);"))
        assertTrue(q, q.contains("nwr[\"amenity\"=\"bank\"][\"atm\"=\"yes\"](around:1000,45.757800,4.832000);"))
        assertTrue(q, q.contains(");out center tags;way[\"highway\"~"))
        assertTrue(q, q.endsWith("(around:800,45.757800,4.832000);out skel geom qt;"))
        assertFalse(overpassQuery(PlaceKind.PHARMACY, lat, lon, 1000, 0).contains("highway"))
    }

    // Shape of a real Overpass answer: a node, a building with its centre, a private one, a street with its geometry.
    private val body = """
        {"version": 0.6, "elements": [
          {"type": "node", "id": 1, "lat": 45.7590, "lon": 4.8330, "tags": {"amenity": "pharmacy", "name": "Pharmacie Bellecour", "addr:housenumber": "12", "addr:street": "Rue de la Charité", "opening_hours": "Mo-Sa 09:00-19:30"}},
          {"type": "way", "id": 2, "center": {"lat": 45.7560, "lon": 4.8320}, "tags": {"amenity": "pharmacy", "name": "Pharmacie des Jacobins", "opening_hours": "Mo-Fr 09:00-12:00,14:00-19:00"}},
          {"type": "node", "id": 3, "lat": 45.7579, "lon": 4.8321, "tags": {"amenity": "pharmacy", "access": "private", "name": "Pharmacie de l'hôpital"}},
          {"type": "node", "id": 4, "lat": 45.7700, "lon": 4.8400, "tags": {"amenity": "pharmacy", "name": "Grande Pharmacie"}},
          {"type": "way", "id": 5, "geometry": [{"lat": 45.757, "lon": 4.831}, {"lat": 45.758, "lon": 4.833}]}
        ]}
    """.trimIndent()

    @Test
    fun `places and streets are read apart, buildings by their centre`() {
        val r = parseOverpass(body)
        assertEquals(listOf("node/1", "way/2", "node/3", "node/4"), r.places.map { it.id })
        assertEquals(45.7560, r.places[1].lat, 1e-9)
        assertEquals("12 Rue de la Charité", r.places[0].address)
        assertEquals(1, r.roads.size)
        assertEquals(listOf(45.757 to 4.831, 45.758 to 4.833), r.roads[0])
        assertTrue(parseOverpass("""{"elements": []}""").places.isEmpty())
    }

    @Test
    fun `nearest first, private ones left out, and open now when asked`() {
        val places = parseOverpass(body).places
        val all = rankPlaces(places, lat, lon, now, onlyOpen = false)
        assertEquals(listOf("node/1", "way/2", "node/4"), all.map { it.place.id })
        assertTrue(all[0].state!!.open)
        assertFalse(all[1].state!!.open) // Saturday: closed
        assertNull(all[2].state)
        assertEquals(listOf("node/1"), rankPlaces(places, lat, lon, now, onlyOpen = true).map { it.place.id })
        // due north is 0°, due east 90°
        assertEquals(0.0, bearingDeg(45.0, 4.0, 45.1, 4.0), 0.5)
        assertEquals(90.0, bearingDeg(45.0, 4.0, 45.0, 4.1), 0.5)
    }

    @Test
    fun `the answer gives distance, direction, address and whether it is open`() {
        val hits = rankPlaces(parseOverpass(body).places, lat, lon, now, onlyOpen = false)
        val text = formatNearby(PlaceKind.PHARMACY, hits, "votre position (Lyon)", 1500, now, onlyOpen = false, direction = ::direction)
        assertTrue(text, text.startsWith("Pharmacies les plus proches de votre position (Lyon), 3 à moins de 1,5 km :\n"))
        assertTrue(text, text.contains("1. Pharmacie Bellecour, 12 Rue de la Charité — à 150 m cap 30 ; ouverte, ferme à 19 h 30."))
        assertTrue(text, text.contains("2. Pharmacie des Jacobins — à 200 m cap 180 ; fermée, ouvre lundi à 9 h."))
        assertTrue(text, text.contains("3. Grande Pharmacie — à 1,5 km cap 24 ; horaires non renseignés."))
        assertTrue(text, text.contains("OpenStreetMap"))
    }

    @Test
    fun `the nearest open one is pointed out when the nearest is closed, and the night pharmacy number when none is open`() {
        val hits = rankPlaces(parseOverpass(body).places, lat, lon, now, onlyOpen = false)
        val text = formatNearby(PlaceKind.PHARMACY, hits.drop(1).reversed() + hits.first(), "Lyon", 1500, now, onlyOpen = false, direction = ::direction)
        assertTrue(text, text.contains("La plus proche ouverte d’après les horaires : la n° 3."))
        val closed = formatNearby(PlaceKind.PHARMACY, hits.drop(1), "Lyon", 1500, now, onlyOpen = false, direction = ::direction)
        assertTrue(closed, closed.contains("3237"))
        assertEquals(
            "Aucun distributeur de billets ouvert maintenant d’après les horaires connus à moins de 3 km de Lyon dans OpenStreetMap.",
            formatNearby(PlaceKind.ATM, emptyList(), "Lyon", 3000, now, onlyOpen = true, direction = ::direction),
        )
    }

    @Test
    fun `toilets and chargers say what matters about them`() {
        assertEquals(listOf("gratuites", "accessibles en fauteuil"), detailWords(PlaceKind.TOILETS, mapOf("fee" to "no", "wheelchair" to "yes")))
        assertEquals(
            listOf("2 × CCS, Type 2", "jusqu’à 150 kW", "4 points de charge"),
            detailWords(PlaceKind.CHARGER, mapOf("socket:type2_combo" to "2", "socket:type2" to "yes", "socket:type2_combo:output" to "150 kW", "socket:type2:output" to "22 kW", "capacity" to "4")),
        )
        assertEquals("à 1,2 km", distanceWords(1234.0))
        assertEquals("à 350 m", distanceWords(347.0))
        assertEquals("à 3 km", distanceWords(3000.0))
        assertTrue(wantsOpenOnly("oui"))
        assertTrue(wantsOpenOnly("ouverte maintenant"))
        assertFalse(wantsOpenOnly(""))
    }
}
