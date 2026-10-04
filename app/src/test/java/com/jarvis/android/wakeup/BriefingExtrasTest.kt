package com.jarvis.android.wakeup

import com.jarvis.android.google.MailSummary
import com.jarvis.android.space.Launch
import com.jarvis.android.space.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BriefingExtrasTest {
    private val today = LocalDate.of(2026, 10, 1)
    private val paris = ZoneId.of("Europe/Paris")

    @Test fun `parts named in words`() {
        assertEquals("mails", sectionKey("les e-mails"))
        assertEquals("meteo", sectionKey("Météo"))
        assertEquals("pluie", sectionKey("le radar"))
        assertEquals("fusees", sectionKey("fusées"))
        assertEquals("depenses", sectionKey("prélèvements"))
        assertEquals("iss", sectionKey("la station spatiale"))
        assertEquals("pollen", sectionKey("les pollens"))
        assertEquals("pollen", sectionKey("alertes pollen"))
        assertEquals("pollen", sectionKey("la qualité de l’air"))
        assertEquals("pollen", sectionKey("l'air"))
        assertEquals("vigilance", sectionKey("les vigilances"))
        assertNull(sectionKey("n'importe quoi"))
        assertNull(sectionKey(""))
    }

    @Test fun `important mails in one sentence`() {
        fun m(from: String, subject: String) = MailSummary("id", from, subject, "", "", true)
        assertNull(mailsLine(emptyList()))
        assertEquals("1 mail important non lu : Marie Dupont (« Réunion demain »)", mailsLine(listOf(m("\"Marie Dupont\" <marie@x.fr>", "Réunion demain"))))
        assertEquals(
            "5 mails importants non lus : Marie (« A »), paul@y.fr (« B »), Banque (« C »), et 2 autres",
            mailsLine(listOf(m("Marie <m@x.fr>", "A"), m("<paul@y.fr>", "B"), m("Banque <b@z.fr>", "C"), m("D <d@d>", "D"), m("E <e@e>", "E"))),
        )
    }

    @Test fun `today's flights`() {
        val t = Trip("AF1234", "AFR1234", "2026-10-01", "10:35", "Europe/Paris", "CDG", "Paris", "JFK", "New York", gate = "K42")
        assertEquals("vol AF1234 aujourd’hui vers New York (JFK), départ à 10h35, porte K42", flightsLine(listOf(t), today))
        assertNull(flightsLine(listOf(t.copy(date = "2026-10-02")), today))
    }

    @Test fun `today's launches, when their time is known`() {
        fun l(name: String, iso: String, precision: String = "SEC", status: String = "Go") = Launch(
            "id", name, "", "Falcon 9", "SpaceX", "", "", java.time.Instant.parse(iso).toEpochMilli(), precision, status, "", "", emptyList(), "", false, null, null, null,
        )
        assertEquals("lancement aujourd’hui : Crew-13 (SpaceX) à 17h10", launchesLine(listOf(l("Falcon 9 Block 5 | Crew-13", "2026-10-01T15:10:06Z"), l("Other", "2026-10-02T15:10:00Z")), today, paris))
        assertNull(launchesLine(listOf(l("Vague | X", "2026-10-01T12:00:00Z", precision = "DAY")), today, paris))
        assertNull(launchesLine(listOf(l("Held | X", "2026-10-01T12:00:00Z", status = "Hold")), today, paris))
    }
}
