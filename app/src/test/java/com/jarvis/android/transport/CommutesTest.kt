package com.jarvis.android.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CommutesTest {
    @Test fun `days in words`() {
        assertEquals(listOf(1, 2, 3, 4, 5), parseDays("en semaine"))
        assertEquals(listOf(1, 2, 3, 4, 5), parseDays(""))
        assertEquals(listOf(1, 2, 3, 4, 5), parseDays("du lundi au vendredi"))
        assertEquals(listOf(1, 2, 3, 4, 5), parseDays("lun-ven"))
        assertEquals((1..7).toList(), parseDays("tous les jours"))
        assertEquals(listOf(6, 7), parseDays("le week-end"))
        assertEquals(listOf(1, 3, 5), parseDays("lundi, mercredi et vendredi"))
        assertEquals("en semaine", daysWords(listOf(5, 4, 3, 2, 1)))
        assertEquals("lundi, jeudi", daysWords(listOf(4, 1)))
    }

    @Test fun `the time and the next look`() {
        assertEquals("07:40", clockOf("7h40"))
        assertEquals("18:00", clockOf("18 h"))
        assertNull(clockOf("tôt"))
        val c = Commute("boulot", "Versailles", "Paris Saint-Lazare", listOf(1, 2, 3, 4, 5), "07:40")
        // Friday 2 October 2026, 6 a.m.: today at 6:55
        assertEquals(LocalDateTime.of(2026, 10, 2, 6, 55), nextCheck(c, LocalDateTime.of(2026, 10, 2, 6, 0)))
        // Friday 8 a.m.: Monday at 6:55
        assertEquals(LocalDateTime.of(2026, 10, 5, 6, 55), nextCheck(c, LocalDateTime.of(2026, 10, 2, 8, 0)))
    }

    @Test fun `the disruptions' words, and what is told`() {
        val body = """{"journeys":[],"disruptions":[
          {"status":"active","severity":{"effect":"SIGNIFICANT_DELAYS"},"messages":[{"text":"<p>Accident de personne à Saint-Cloud.&nbsp;Le trafic est fortement perturbé.</p>"},{"text":"Accident de personne"}]},
          {"status":"active","severity":{"effect":"NO_SERVICE"},"messages":[]},
          {"status":"past","severity":{"effect":"NO_SERVICE"},"messages":[{"text":"Old"}]}]}"""
        assertEquals(listOf("Accident de personne", "trafic interrompu"), parseDisruptions(body))
        val c = Commute("boulot", "Versailles", "Paris Saint-Lazare", listOf(1, 2, 3, 4, 5), "07:40")
        val onTime = Journey(LocalDateTime.of(2026, 10, 2, 7, 40), LocalDateTime.of(2026, 10, 2, 8, 12), 0, listOf(Leg("Transilien", "L", "Paris")), 0, false)
        assertNull(commuteWords(c, onTime, emptyList(), onlyProblems = true))
        val late = onTime.copy(delayMinutes = 12)
        val w = commuteWords(c, late, listOf("Accident de personne"), onlyProblems = true)!!
        assertTrue(w, w.startsWith("Trajet « boulot » (Versailles → Paris Saint-Lazare) : 7 h 40 → 8 h 12"))
        assertTrue(w, w.contains("retard de 12 min") && w.endsWith("Perturbations : Accident de personne."))
        assertTrue(commuteWords(c, null, emptyList(), onlyProblems = true)!!.contains("supprimé"))
    }
}
