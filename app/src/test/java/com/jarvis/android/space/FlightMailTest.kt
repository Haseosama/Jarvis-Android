package com.jarvis.android.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class FlightMailTest {
    private val received = LocalDate.of(2026, 9, 20)

    @Test fun `a French booking confirmation`() {
        val mail = """Confirmation de votre réservation - Référence ABC123
            Aller : Vol AF 1234 - jeudi 15 octobre 2026
            Départ Paris-Charles de Gaulle (CDG) 10:35 Terminal 2E
            Arrivée New York JFK 12:50
            Retour : Vol AF1235 - 22/10/2026 départ 18h15"""
        val f = findFlightsInMail(mail, received)
        assertEquals(listOf("AF1234", "AF1235"), f.map { it.flight })
        assertEquals(LocalDate.of(2026, 10, 15), f[0].date)
        assertEquals(LocalTime.of(10, 35), f[0].time)
        assertEquals("2E", f[0].terminal)
        assertEquals(LocalDate.of(2026, 10, 22), f[1].date)
        assertEquals(LocalTime.of(18, 15), f[1].time)
    }

    @Test fun `an English boarding pass with a gate, a date without its year`() {
        val mail = "Your boarding pass. Flight: BA 304 London Heathrow to Paris CDG. Date: Oct 3. Departs 07:40. Gate: B42. Seat 23A"
        val f = findFlightsInMail(mail, received).single()
        assertEquals("BA304", f.flight)
        assertEquals(LocalDate.of(2026, 10, 3), f.date)
        assertEquals(LocalTime.of(7, 40), f.time)
        assertEquals("B42", f.gate)
    }

    @Test fun `a low-cost itinerary with a digit in the airline code`() {
        val f = findFlightsInMail("easyJet - votre vol U2 4811 le 2 novembre de Nice à Londres Gatwick, départ 6h50", received).single()
        assertEquals("U24811", f.flight)
        assertEquals(LocalDate.of(2026, 11, 2), f.date)
        assertEquals(LocalTime.of(6, 50), f.time)
    }

    @Test fun `a date in January is next year's when the mail is from the autumn`() {
        val f = findFlightsInMail("Flight LH 1033 on 14 January, 09:05", LocalDate.of(2026, 11, 30)).single()
        assertEquals(LocalDate.of(2027, 1, 14), f.date)
    }

    @Test fun `numbers that are not flights are left alone`() {
        assertTrue(findFlightsInMail("Votre commande CB 1234 du 12/10/2026 : 3 articles, total 45,90 €", received).isEmpty()) // no travel word
        assertTrue(findFlightsInMail("Promo vols : jusqu'à -30 % en octobre", received).isEmpty()) // no number
        assertTrue(findFlightsInMail("Votre vol AF1234", received).isEmpty()) // no date
        assertTrue(findFlightsInMail("vol 10 PM 12 le 3 octobre", received).none { it.flight.startsWith("PM") })
    }

    @Test fun `a trip in words, its reminder, the mail's day`() {
        val t = Trip(
            "AF1234", "AFR1234", "2026-10-15", "10:35", "Europe/Paris", "CDG", "Paris", "JFK", "New York", 40.64, -73.78, "K42", "2E", "Confirmation",
            java.time.ZonedDateTime.of(2026, 10, 15, 10, 35, 0, 0, java.time.ZoneId.of("Europe/Paris")).toInstant().toEpochMilli(),
        )
        assertEquals("AF1234 Paris (CDG) → New York (JFK), jeudi 15 octobre à 10h35 (heure locale du départ), terminal 2E, porte K42", tripWords(t))
        assertEquals(t.departMs - 3 * 3_600_000L, tripAlarmMs(t))
        val noTime = t.copy(time = null)
        assertEquals(java.time.ZonedDateTime.of(2026, 10, 15, 6, 0, 0, 0, java.time.ZoneId.of("Europe/Paris")).toInstant().toEpochMilli(), tripAlarmMs(noTime))
        assertEquals(LocalDate.of(2026, 9, 29), mailDay("Tue, 29 Sep 2026 10:12:00 +0200 (CEST)"))
    }

    @Test fun `the take-off is told with its delay`() {
        val w = WatchedFlight("AF1234", "AFR1234", "New York (JFK)", 40.64, -73.78, Long.MAX_VALUE, scheduledMs = 1_000_000_000L)
        assertTrue(takeoffWords(w, 1_000_000_000L + 42 * 60_000L).endsWith("avec environ 32 minutes de retard."))
        assertTrue(takeoffWords(w, 1_000_000_000L + 16 * 60_000L).endsWith("à l’heure."))
        assertTrue(takeoffWords(w.copy(scheduledMs = 0), 1_000_000_000L).endsWith("heure du téléphone)."))
        assertNull(null)
    }
}
