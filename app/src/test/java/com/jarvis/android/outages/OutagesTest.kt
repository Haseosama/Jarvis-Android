package com.jarvis.android.outages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class OutagesTest {
    // a Sunday
    private val today = LocalDate.of(2026, 10, 4)
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test fun `an Enedis SMS`() {
        val o = parseOutage("ENEDIS : coupure d'électricité pour travaux le 14/10/2026 de 09h00 à 12h00 à votre adresse. Infos 09.72.67.50.21", today, source = "Messages")!!
        assertEquals(OutageKind.ELECTRICITY, o.kind)
        assertEquals(LocalDate.of(2026, 10, 14), o.date)
        assertEquals(t(9) to t(12), o.start to o.end)
        assertEquals("Messages", o.source)
    }

    @Test fun `a water notice with the day in words and half hours`() {
        val o = parseOutage("Veolia : l’eau sera coupée mercredi 7 octobre entre 8 h 30 et 17 h pour travaux sur le réseau", today)!!
        assertEquals(OutageKind.WATER, o.kind)
        assertEquals(LocalDate.of(2026, 10, 7), o.date)
        assertEquals(t(8, 30) to t(17), o.start to o.end)
    }

    @Test fun `a gas notice with a short year and only a start`() {
        val o = parseOutage("GRDF : interruption de la fourniture de gaz le 3/11/26 à partir de 8h", today)!!
        assertEquals(OutageKind.GAS, o.kind)
        assertEquals(LocalDate.of(2026, 11, 3), o.date)
        assertEquals(t(8) to null, o.start to o.end)
    }

    @Test fun `tomorrow, a weekday, hours with a dash or in words`() {
        assertEquals(today.plusDays(1), outageDateIn("coupure de courant demain", today))
        assertEquals(LocalDate.of(2026, 10, 8), outageDateIn("travaux jeudi", today))
        assertEquals(today, outageDateIn("coupure dimanche", today)) // today is a Sunday
        assertEquals(t(9) to t(17), outageHoursIn("de 9:00 - 17:00"))
        assertEquals(t(9) to t(12), outageHoursIn("de 9 heures à 12 heures"))
        assertEquals(null to null, outageHoursIn("toute la journée"))
        assertEquals(LocalDate.of(2027, 1, 5), outageDateIn("coupure le 5 janvier", LocalDate.of(2026, 12, 20))) // next year's
    }

    @Test fun `the kind nearest the cut, and what is not a notice`() {
        assertEquals(OutageKind.ELECTRICITY, outageKindIn("Coupure d'électricité le 9/10 : pensez au ballon d'eau chaude"))
        assertEquals(OutageKind.WATER, outageKindIn("Mairie : coupure d'eau rue des Lilas le 9/10, travaux du réseau électrique"))
        assertNull(outageKindIn("La facture d'électricité de septembre est disponible"))
        // a headline about a blackout elsewhere: no sender of notices, no works
        assertNull(parseOutage("Coupure d'électricité géante en Espagne le 5/10", today))
        // but handed over by the user, it is noted
        assertTrue(parseOutage("Coupure d'électricité le 5/10", today, explicit = true) != null)
        // a past day, or no day
        assertNull(parseOutage("Enedis : coupure pour travaux le 1/10 de 9h à 12h", today))
        assertNull(parseOutage("Enedis : une coupure pour travaux aura lieu prochainement", today))
        // a phone number is not a date
        assertNull(outageDateIn("Infos au 09.72.67.50.21", today))
    }

    @Test fun `noted once, past ones dropped, kept as text`() {
        val a = Outage(OutageKind.WATER, LocalDate.of(2026, 10, 7), t(8), t(12), "SMS")
        val b = Outage(OutageKind.ELECTRICITY, LocalDate.of(2026, 10, 6), null, null, "", reminderId = 12)
        val past = Outage(OutageKind.GAS, LocalDate.of(2026, 10, 1))
        val all = addOutage(addOutage(listOf(past, a), b, today), a.copy(end = t(13)), today)
        assertEquals(listOf(b, a.copy(end = t(13))), all)
        assertEquals(all, outagesFromJson(outagesToJson(all)))
        assertTrue(outagesFromJson("not json").isEmpty())
        assertEquals(listOf(a), outagesFromJson("""[{"kind":"eau","date":"2026-10-07","start":"08:00","end":"12:00","source":"SMS"},{"kind":"eau","date":"x"},{"kind":"?"}]"""))
    }

    @Test fun `reminded the evening before, or an hour before when too late`() {
        val o = Outage(OutageKind.WATER, LocalDate.of(2026, 10, 7), t(8), t(12))
        assertEquals(LocalDateTime.of(2026, 10, 6, 20, 0), outageReminderAt(o, LocalDateTime.of(2026, 10, 4, 10, 0)))
        assertEquals(LocalDateTime.of(2026, 10, 7, 7, 0), outageReminderAt(o, LocalDateTime.of(2026, 10, 6, 21, 0)))
        assertNull(outageReminderAt(o, LocalDateTime.of(2026, 10, 7, 7, 30)))
        assertEquals(LocalDateTime.of(2026, 10, 7, 7, 0), outageReminderAt(o.copy(start = null), LocalDateTime.of(2026, 10, 6, 22, 0)))
    }

    @Test fun `the words said`() {
        val water = Outage(OutageKind.WATER, LocalDate.of(2026, 10, 5), t(8, 30), t(12))
        val power = Outage(OutageKind.ELECTRICITY, LocalDate.of(2026, 10, 8), t(9), null, "Messages")
        assertEquals("coupure d’eau demain de 8 h 30 à 12 h", outageLine(water, today))
        assertEquals("coupure d’électricité jeudi 8 octobre à partir de 9 h", outageLine(power, today))
        assertEquals(
            "Coupure d’eau demain de 8 h 30 à 12 h : remplissez quelques bouteilles et une casserole, et prévoyez de quoi tirer la chasse.",
            reminderText(water, today),
        )
        assertEquals(
            "2 coupures prévues : coupure d’eau demain de 8 h 30 à 12 h ; coupure d’électricité jeudi 8 octobre à partir de 9 h (vue dans Messages).",
            outagesWords(listOf(power, water), today),
        )
        assertEquals("Aucune coupure prévue notée.", outagesWords(emptyList(), today))
        assertEquals("Coupure d’eau demain de 8 h 30 à 12 h", outagesBriefingLine(listOf(water, power), today))
        assertNull(outagesBriefingLine(listOf(power), today))
    }

    @Test fun `the kind in the user's words`() {
        assertEquals(OutageKind.ELECTRICITY, OutageKind.of("électricité"))
        assertEquals(OutageKind.ELECTRICITY, OutageKind.of("courant"))
        assertEquals(OutageKind.WATER, OutageKind.of("eau"))
        assertEquals(OutageKind.GAS, OutageKind.of("Gaz"))
        assertNull(OutageKind.of(""))
    }
}
