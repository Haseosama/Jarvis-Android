package com.jarvis.android.trash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class TrashScheduleTest {
    // a Monday, in ISO week 41 (odd)
    private val today = LocalDate.of(2026, 10, 5)
    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate.of(y, m, day)
    private val paris = ZoneId.of("Europe/Paris")

    @Test fun `days as said`() {
        assertEquals(setOf(TUESDAY, FRIDAY), parseDays("mardi et vendredi"))
        assertEquals(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY), parseDays("du lundi au vendredi"))
        assertEquals(setOf(THURSDAY), parseDays("les jeudis"))
        assertEquals(setOf(WEDNESDAY), parseDays("Wednesday"))
        assertTrue(parseDays("bientôt").isEmpty())
    }

    @Test fun `how often as said`() {
        assertEquals(Cadence.WEEKLY to 0, parseCadence(""))
        assertEquals(Cadence.WEEKLY to 0, parseCadence("toutes les semaines"))
        assertEquals(Cadence.EVEN_WEEKS to 0, parseCadence("les semaines paires"))
        assertEquals(Cadence.ODD_WEEKS to 0, parseCadence("semaines impaires"))
        assertEquals(Cadence.BIWEEKLY to 0, parseCadence("une semaine sur deux"))
        assertEquals(Cadence.BIWEEKLY to 0, parseCadence("tous les 15 jours"))
        assertEquals(Cadence.MONTHLY to 1, parseCadence("le premier lundi du mois"))
        assertEquals(Cadence.MONTHLY to 1, parseCadence("1er mardi du mois"))
        assertEquals(Cadence.MONTHLY to 3, parseCadence("3e jeudi du mois"))
        assertEquals(Cadence.MONTHLY to -1, parseCadence("le dernier vendredi du mois"))
    }

    @Test fun `even and odd weeks follow the ISO week number`() {
        val yellow = TrashRule("bac jaune", setOf(TUESDAY), Cadence.EVEN_WEEKS)
        assertFalse(yellow.collects(d(10, 6))) // week 41
        assertTrue(yellow.collects(d(10, 13))) // week 42
        assertFalse(yellow.collects(d(10, 14))) // a Wednesday
        val odd = yellow.copy(cadence = Cadence.ODD_WEEKS)
        assertTrue(odd.collects(d(10, 6)))
        assertFalse(odd.collects(d(10, 13)))
    }

    @Test fun `every other week counts from the week given, across a year with 53 weeks`() {
        val r = TrashRule("verre", setOf(THURSDAY), Cadence.BIWEEKLY, anchor = d(10, 6)) // any day of that week
        assertTrue(r.collects(d(10, 8)))
        assertFalse(r.collects(d(10, 15)))
        assertTrue(r.collects(d(10, 22)))
        // 2026 has 53 ISO weeks: the alternation goes on where even/odd numbers would repeat
        assertTrue(r.collects(d(12, 31)))
        assertTrue(r.collects(d(1, 14, 2027)))
        assertFalse(r.collects(d(1, 7, 2027)))
    }

    @Test fun `nth and last weekday of the month`() {
        val first = TrashRule("encombrants", setOf(MONDAY), Cadence.MONTHLY, nth = 1)
        assertTrue(first.collects(d(10, 5)))
        assertFalse(first.collects(d(10, 12)))
        assertTrue(first.collects(d(11, 2)))
        val last = TrashRule("déchets verts", setOf(FRIDAY), Cadence.MONTHLY, nth = -1)
        assertTrue(last.collects(d(10, 30)))
        assertFalse(last.collects(d(10, 23)))
    }

    @Test fun `bins on a day, each once, without the skipped ones`() {
        val s = TrashSchedule(
            rules = listOf(TrashRule("ordures ménagères", setOf(TUESDAY, FRIDAY)), TrashRule("bac jaune", setOf(TUESDAY), Cadence.EVEN_WEEKS)),
            dates = listOf(TrashDay("encombrants", d(10, 13))),
            skips = listOf(TrashDay("", d(11, 11)), TrashDay("bac jaune", d(10, 27))),
        )
        assertEquals(listOf("ordures ménagères"), binsOn(s, d(10, 6)))
        assertEquals(listOf("ordures ménagères", "bac jaune", "encombrants"), binsOn(s, d(10, 13)))
        // the .ics says the same bin in other words: said once
        assertEquals(listOf("ordures ménagères", "bac jaune", "encombrants"), binsOn(s, d(10, 13), listOf(TrashDay("Bac jaune", d(10, 13)))))
        assertEquals(listOf("ordures ménagères"), binsOn(s, d(10, 27)))
        assertTrue(binsOn(TrashSchedule(rules = listOf(TrashRule("ordures", setOf(WEDNESDAY))), skips = s.skips), d(11, 11)).isEmpty())
        assertEquals(listOf(d(10, 6), d(10, 9)), collections(s, today, 7).map { it.first })
    }

    @Test fun `bins named differently are the same bin`() {
        assertTrue(sameBin("le bac jaune", "poubelle jaune"))
        assertTrue(sameBin("jaune", "Bac jaune"))
        assertTrue(sameBin("verre", "collecte du verre"))
        assertFalse(sameBin("bac jaune", "bac vert"))
        assertEquals("bac jaune", binLabel("Jaune"))
        assertEquals("ordures ménagères", binLabel("Ordures ménagères."))
    }

    @Test fun `a bin said again replaces its rules, remove and clear`() {
        var s = withRule(TrashSchedule(), TrashRule("bac jaune", setOf(TUESDAY)))
        s = withRule(s, TrashRule("verre", setOf(MONDAY), Cadence.MONTHLY, nth = 1))
        s = withRule(s, TrashRule("poubelle jaune", setOf(THURSDAY), Cadence.ODD_WEEKS))
        assertEquals(2, s.rules.size)
        assertEquals(setOf(THURSDAY), s.rules.first { sameBin(it.bin, "jaune") }.days)
        assertEquals(listOf("verre"), withoutBin(s, "jaune").rules.map { it.bin })
        assertTrue(withoutBin(s, "tout").isEmpty)
        assertTrue(isAllBins(""))
    }

    @Test fun `past one-off days and skips are dropped`() {
        val s = TrashSchedule(dates = listOf(TrashDay("encombrants", d(10, 1)), TrashDay("encombrants", d(10, 20))), skips = listOf(TrashDay("", d(9, 30))))
        val p = pruned(s, today)
        assertEquals(listOf(d(10, 20)), p.dates.map { it.date })
        assertTrue(p.skips.isEmpty())
    }

    @Test fun `dates and times as said`() {
        assertEquals(d(11, 14), parseDate("2026-11-14", today))
        assertEquals(d(11, 14), parseDate("le 14/11", today))
        assertEquals(d(1, 3, 2027), parseDate("3/1/2027", today))
        assertEquals(d(11, 11), parseDate("le 11 novembre", today))
        assertEquals(d(10, 6), parseDate("demain", today))
        assertEquals(d(10, 8), parseDate("jeudi", today))
        assertEquals(d(1, 2, 2027), parseDate("2 janvier", d(12, 20)))
        assertNull(parseDate("un jour", today))
        assertEquals(LocalTime.of(19, 30), parseTime("19h30"))
        assertEquals(LocalTime.of(21, 0), parseTime("21 h"))
        assertEquals(LocalTime.of(20, 0), parseTime("8 heures du soir"))
        assertEquals(LocalTime.of(20, 15), parseTime("20:15"))
        assertNull(parseTime("tard"))
    }

    @Test fun `the evening words and the schedule said`() {
        assertEquals("Demain mardi, collecte : bac jaune. Pensez à sortir le bac ce soir.", eveningWords(listOf("bac jaune"), d(10, 6)))
        assertEquals("Demain mardi, collecte : ordures ménagères et bac jaune. Pensez à sortir les bacs ce soir.", eveningWords(listOf("ordures ménagères", "bac jaune"), d(10, 6)))
        assertEquals("bac jaune : le mardi des semaines paires", ruleWords(TrashRule("bac jaune", setOf(TUESDAY), Cadence.EVEN_WEEKS)))
        assertEquals("ordures ménagères : chaque mardi et vendredi", ruleWords(TrashRule("ordures ménagères", setOf(FRIDAY, TUESDAY))))
        assertEquals("verre : le premier lundi du mois", ruleWords(TrashRule("verre", setOf(MONDAY), Cadence.MONTHLY, nth = 1)))
        assertEquals("verre : le jeudi, une semaine sur deux (dont la semaine du 5 octobre)", ruleWords(TrashRule("verre", setOf(THURSDAY), Cadence.BIWEEKLY, d(10, 8))))
        assertEquals("Demain mardi : bac jaune. Vendredi 9 octobre : ordures ménagères et verre.", upcomingWords(listOf(d(10, 6) to listOf("bac jaune"), d(10, 9) to listOf("ordures ménagères", "verre")), today))
    }

    @Test fun `the reminder rings today if the hour is ahead, else tomorrow`() {
        assertEquals(LocalDateTime.of(2026, 10, 5, 20, 0), nextRing(LocalDateTime.of(2026, 10, 5, 18, 0), LocalTime.of(20, 0)))
        assertEquals(LocalDateTime.of(2026, 10, 6, 20, 0), nextRing(LocalDateTime.of(2026, 10, 5, 20, 0), LocalTime.of(20, 0)))
    }

    @Test fun `calendar events about a collection`() {
        assertTrue(isTrashTitle("Collecte bac jaune"))
        assertTrue(isTrashTitle("Ordures ménagères"))
        assertTrue(isTrashTitle("Encombrants"))
        assertTrue(isTrashTitle("Sortir les poubelles"))
        assertFalse(isTrashTitle("Verre avec Paul"))
        assertFalse(isTrashTitle("Dentiste"))
        assertEquals("ordures ménagères", binFromTitle("Collecte des ordures ménagères"))
        assertEquals("bac jaune", binFromTitle("Collecte jaune"))
        assertEquals("encombrants", binFromTitle("Encombrants"))
    }

    @Test fun `an ics with single days, a weekly repeat, a monthly one, exclusions and a count`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:1
            DTSTART;VALUE=DATE:20261013
            SUMMARY:Collecte des emballages\, papiers
            END:VEVENT
            BEGIN:VEVENT
            UID:2
            DTSTART;TZID=Europe/Paris:20250106T060000
            RRULE:FREQ=WEEKLY;BYDAY=TU,FR
            EXDATE;TZID=Europe/Paris:20261009T060000
            SUMMARY:Ordures ménagères
            END:VEVENT
            BEGIN:VEVENT
            UID:3
            DTSTART;VALUE=DATE:20260105
            RRULE:FREQ=MONTHLY;BYDAY=1MO
            SUMMARY:Verre
            END:VEVENT
            BEGIN:VEVENT
            UID:4
            DTSTART:20261006T220000Z
            RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=2
            SUMMARY:Biodéchets
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")
        val days = parseIcs(ics, today, d(10, 31), paris)
        fun on(day: LocalDate) = days.filter { it.date == day }.map { it.bin }.toSet()
        assertEquals(setOf("ordures ménagères", "verre"), on(d(10, 5)).plus(on(d(10, 6))))
        assertEquals(setOf("verre"), on(d(10, 5)))
        assertTrue(on(d(10, 9)).isEmpty()) // excluded
        assertEquals(setOf("ordures ménagères", "emballages, papiers"), on(d(10, 13)))
        // 22:00 UTC is midnight in Paris: the 7th, then two weeks later, then the count is spent
        assertEquals(listOf(d(10, 7), d(10, 21)), days.filter { it.bin == "biodéchets" }.map { it.date })
        assertTrue(on(d(11, 2)).isEmpty()) // after [to]
    }

    @Test fun `an ics folded over lines and ending with UNTIL`() {
        val ics = "BEGIN:VEVENT\r\nDTSTART;VALUE=DATE:20261001\r\nRRULE:FREQ=WEEKLY;UNTIL=20261015\r\nSUMMARY:Collecte du\r\n  verre\r\nEND:VEVENT\r\n"
        assertEquals(listOf(d(10, 8), d(10, 15)), parseIcs(ics, today, d(12, 31), paris).map { it.date })
        assertEquals("verre", parseIcs(ics, today, d(12, 31), paris).first().bin)
    }

    @Test fun `the schedule is kept and read back`() {
        val s = TrashSchedule(
            rules = listOf(TrashRule("bac jaune", setOf(TUESDAY, FRIDAY), Cadence.BIWEEKLY, d(10, 6)), TrashRule("verre", setOf(MONDAY), Cadence.MONTHLY, nth = -1)),
            dates = listOf(TrashDay("encombrants", d(10, 20))),
            skips = listOf(TrashDay("", d(11, 11))),
        )
        assertEquals(s, trashFromJson(trashToJson(s)))
        assertTrue(trashFromJson("pas du json").isEmpty)
        assertTrue(trashFromJson(null).isEmpty)
    }
}
