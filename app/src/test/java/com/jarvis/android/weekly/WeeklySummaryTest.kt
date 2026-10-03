package com.jarvis.android.weekly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WeeklySummaryTest {
    private val sunday = LocalDate.of(2026, 10, 4)

    @Test fun `the week gone by`() {
        val days = (6 downTo 0).map { sunday.minusDays(it.toLong()) }
        val w = PastWeek(
            nights = days.zip(listOf(420L, 450L, 300L, 480L, 435L, 510L, 465L)),
            steps = days.zip(listOf(8000L, 12000L, 0L, 6000L, 9000L, 15000L, 5000L)),
            spending = mapOf("courses" to 8_550L, "essence" to 6_000L, "divers" to 1_200L, "café" to 450L),
        )
        val lines = pastWeekWords(w)
        assertEquals("sommeil : 7 h 17 en moyenne sur 7 nuits, la plus courte mercredi (5 h 00)", lines[0])
        assertTrue(lines[1], lines[1].startsWith("pas : 55") && "le plus samedi" in lines[1]) // the day without steps is not counted
        assertEquals("dépenses notées : 162,00 € (courses 85,50 €, essence 60,00 €, divers 12,00 €)", lines[2])
        assertTrue(pastWeekWords(PastWeek(emptyList(), emptyList(), emptyMap())).isEmpty())
    }

    @Test fun `the week to come`() {
        val days = (1..7).map { sunday.plusDays(it.toLong()) }
        val next = days.mapIndexed { i, d ->
            NextDay(d, if (i == 1) listOf("10:00-11:00 Dentiste") else if (i == 4) listOf("Toute la journée Anniversaire de Léa", "19:00-23:00 Dîner") else emptyList(),
                listOf(0, 1, 61, 61, 63, 3, 95)[i], 20.0 + i, 10.0, 0.0)
        }
        val lines = nextWeekWords(next)
        assertEquals("à l’agenda : mardi 10:00-11:00 Dentiste ; vendredi Toute la journée Anniversaire de Léa, 19:00-23:00 Dîner", lines[0])
        assertEquals("météo : lundi soleil (20 °C), mardi éclaircies (21 °C), mercredi à vendredi pluie (24 °C), samedi couvert (25 °C), dimanche orages (26 °C)", lines[1])
        assertEquals(listOf("rien à l’agenda"), nextWeekWords(days.map { NextDay(it, emptyList(), null, null, null, null) }))
        val all = summaryWords(listOf("pas : 1"), lines)
        assertTrue(all, all.startsWith("Bilan de la semaine. Pas : 1. La semaine prochaine : à l’agenda"))
    }

    @Test fun `the daily forecast and the next Sunday`() {
        val d = WeeklySummary.parseDaily("""{"daily":{"time":["2026-10-05","2026-10-06"],"weather_code":[3,61],"temperature_2m_max":[18.2,15.0],"temperature_2m_min":[9.0,8.1],"precipitation_sum":[0.0,4.2]}}""")
        assertEquals(61, d[LocalDate.of(2026, 10, 6)]!!.code)
        assertEquals(4.2, d[LocalDate.of(2026, 10, 6)]!!.rain!!, 1e-9)
        // Saturday 3 October 2026 at 7 p.m.: Sunday 4 at 6 p.m.
        assertEquals(LocalDateTime.of(2026, 10, 4, 18, 0), WeeklySummary.nextSunday(LocalDateTime.of(2026, 10, 3, 19, 0), 18))
        // Sunday at 5 p.m.: today; at 7 p.m.: next week
        assertEquals(LocalDateTime.of(2026, 10, 4, 18, 0), WeeklySummary.nextSunday(LocalDateTime.of(2026, 10, 4, 17, 0), 18))
        assertEquals(LocalDateTime.of(2026, 10, 11, 18, 0), WeeklySummary.nextSunday(LocalDateTime.of(2026, 10, 4, 19, 0), 18))
        assertEquals("orages", weatherWord(96))
    }
}
