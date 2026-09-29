package com.jarvis.android.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class LaunchesTest {
    private val answer = """{"count":2,"results":[
        {"id":"7d1afb26","name":"Starship | Flight 14","net":"2026-09-28T12:48:59Z","net_precision":{"abbrev":"SEC"},
         "status":{"abbrev":"Success"},"launch_service_provider":{"name":"SpaceX"},
         "rocket":{"configuration":{"full_name":"Starship V3"}},
         "pad":{"name":"Orbital Launch Pad 2","latitude":25.99677,"longitude":-97.15799,"location":{"name":"SpaceX Starbase, TX, USA"}},
         "mission":{"name":"Flight 14","description":"Test flight.","orbit":{"name":"Low Earth Orbit"}},
         "vid_urls":[{"priority":9,"url":"https://www.youtube.com/watch?v=Dun8yJ6qvmA"}],"webcast_live":false,"image":null},
        {"id":"a2b3","name":"Falcon 9 Block 5 | Crew-13","net":"2026-10-01T15:10:06Z","net_precision":{"abbrev":"SEC"},
         "status":{"abbrev":"Go"},"launch_service_provider":{"name":"SpaceX"},"probability":90,
         "rocket":{"configuration":{"full_name":"Falcon 9 Block 5"}},
         "pad":{"name":"Space Launch Complex 40","latitude":"28.56194122","longitude":"-80.57735736","location":{"name":"Cape Canaveral SFS, FL, USA"}},
         "mission":null,"vid_urls":[{"priority":10,"url":"https://x.com/i/broadcasts/1qx"},{"priority":2,"url":"http://insecure"}],
         "image":{"image_url":"https://img/full.jpg","thumbnail_url":"https://img/t.jpg"}}]}"""

    @Test fun `launches are read with their webcast, place and status`() {
        val list = parseLaunches(answer)
        assertEquals(2, list.size)
        val star = list[0]
        assertTrue(star.done)
        assertEquals("Dun8yJ6qvmA", star.youtube)
        assertEquals("Starship V3", star.rocket)
        val crew = list[1]
        assertEquals("SpaceX Starbase, TX, USA", star.place)
        assertEquals(90, crew.probability)
        assertEquals(28.56194122, crew.padLat!!, 1e-9)
        assertEquals(listOf("https://x.com/i/broadcasts/1qx"), crew.videos) // https only
        assertNull(crew.youtube)
        assertEquals("https://img/t.jpg", crew.image)
        assertEquals("", crew.orbit)
        assertTrue(parseLaunches("oops").isEmpty())
    }

    @Test fun `a launch is found by its words`() {
        val list = parseLaunches(answer)
        assertEquals("a2b3", Launches.find(list, "Crew-13")!!.id)
        assertEquals("7d1afb26", Launches.find(list, "")!!.id)
        assertEquals("7d1afb26", Launches.find(list, "starship")!!.id)
        assertEquals("a2b3", Launches.find(list, "cape canaveral")!!.id)
        assertNull(Launches.find(list, "Ariane 6"))
    }

    @Test fun `countdowns in words and figures`() {
        assertEquals("dans 2 j 3 h", countdownWords((2 * 86_400 + 3 * 3600 + 59) * 1000L))
        assertEquals("dans 3 h 20 min", countdownWords((3 * 3600 + 20 * 60) * 1000L))
        assertEquals("dans 12 min", countdownWords(12 * 60_000L + 30_000))
        assertEquals("dans 40 s", countdownWords(40_000L))
        assertEquals("il y a 5 min", countdownWords(-5 * 60_000L))
        assertEquals("T-02:13:45", tMinus((2 * 3600 + 13 * 60 + 45) * 1000L))
        assertEquals("T-1 j 00:00:05", tMinus((86_400 + 5) * 1000L))
        assertEquals("T+00:01:00", tMinus(-60_000L))
    }

    @Test fun `when it goes, as precisely as known`() {
        val crew = parseLaunches(answer)[1]
        val paris = ZoneId.of("Europe/Paris")
        assertEquals("jeudi 1 octobre à 17h10", launchWhen(crew, paris))
        assertEquals("le jeudi 1 octobre (heure pas encore fixée)", launchWhen(crew.copy(precision = "DAY"), paris))
        assertEquals("en octobre", launchWhen(crew.copy(precision = "M"), paris))
        assertEquals("confirmé", launchStatusWords("Go"))
        assertEquals("à confirmer", launchStatusWords("TBC"))
    }
}
