package com.jarvis.android.habits

import com.jarvis.android.offline.OfflineAction
import com.jarvis.android.offline.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class HabitsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Europe/Paris")
    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    private val pill = Habit(1, "Doliprane", listOf("08:00", "20:00"), medication = true)
    private val water = Habit(2, "boire de l'eau", listOf("10:00", "14:00", "17:00"))
    private val sport = Habit(3, "sport", listOf("18:00"), days = listOf(1, 4)) // Monday, Thursday

    // Wednesday 23 September 2026
    private val wed = LocalDate.of(2026, 9, 23)

    @Test
    fun `times are read the ways they are said`() {
        assertEquals(LocalTime.of(8, 0), parseHabitTime("8h"))
        assertEquals(LocalTime.of(8, 30), parseHabitTime("8 h 30"))
        assertEquals(LocalTime.of(20, 0), parseHabitTime("20:00"))
        assertEquals(LocalTime.of(20, 0), parseHabitTime("20"))
        assertNull(parseHabitTime("25h"))
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), parseHabitTimes("20h et 8h"))
        assertNull(parseHabitTimes("8h, midi"))
    }

    @Test
    fun `days are read from words`() {
        assertEquals(emptyList<Int>(), parseHabitDays("tous les jours"))
        assertEquals(listOf(1, 2, 3, 4, 5), parseHabitDays("en semaine"))
        assertEquals(listOf(6, 7), parseHabitDays("le week-end"))
        assertEquals(listOf(1, 4), parseHabitDays("lundi et jeudi"))
    }

    @Test
    fun `the next slot is the earliest of any habit, respecting chosen days`() {
        val (h, t) = nextSlot(listOf(pill, water, sport), wed.atTime(9, 0))!!
        assertEquals(water, h)
        assertEquals(wed.atTime(10, 0), t)
        // Sport only: next is Thursday 18 h, not today.
        assertEquals(wed.plusDays(1).atTime(18, 0), nextSlot(listOf(sport), wed.atTime(9, 0))!!.second)
        assertNull(nextSlot(emptyList(), wed.atTime(9, 0)))
    }

    @Test
    fun `an answer goes to the slot just past, or one up to an hour ahead, and not twice`() {
        assertEquals(wed.atTime(8, 0), slotToAnswer(pill, emptyList(), wed.atTime(8, 20), zone))
        assertEquals(wed.atTime(20, 0), slotToAnswer(pill, emptyList(), wed.atTime(19, 15), zone))
        val answered = listOf(HabitEntry(1, ms(wed.atTime(8, 0)), HabitAnswer.DONE, ms(wed.atTime(8, 5))))
        // 8 h already answered, 20 h more than an hour away, yesterday's 20 h beyond the 12-hour window: nothing open,
        // so a "fait" now is logged as unscheduled rather than stuck on an old dose.
        assertNull(slotToAnswer(pill, answered, wed.atTime(9, 0), zone))
        // Taken half an hour early: it counts for 8 h, not for last night's dose.
        assertEquals(wed.atTime(8, 0), slotToAnswer(pill, emptyList(), wed.atTime(7, 30), zone))
    }

    @Test
    fun `today's status says what was done, skipped, not noted, or not yet due`() {
        val log = listOf(HabitEntry(2, ms(wed.atTime(10, 0)), HabitAnswer.DONE, ms(wed.atTime(10, 5))),
            HabitEntry(2, ms(wed.atTime(14, 0)), HabitAnswer.SKIPPED, ms(wed.atTime(14, 1))))
        assertEquals("Aujourd'hui, boire de l'eau : 10 h fait à 10 h 05, 14 h sauté, 17 h pas encore l'heure.",
            todayStatus(water, log, wed.atTime(15, 0), zone))
        assertEquals("Aujourd'hui, Doliprane : 8 h pas noté, 20 h pas encore l'heure.", todayStatus(pill, emptyList(), wed.atTime(15, 0), zone))
        assertEquals("Aujourd'hui, sport : rien de prévu.", todayStatus(sport, emptyList(), wed.atTime(15, 0), zone))
    }

    @Test
    fun `adherence counts only past slots and names the ones not noted`() {
        val from = wed.minusDays(1).atStartOfDay()
        val log = listOf(
            HabitEntry(1, ms(wed.minusDays(1).atTime(8, 0)), HabitAnswer.DONE, 0),
            HabitEntry(1, ms(wed.minusDays(1).atTime(20, 0)), HabitAnswer.DONE, 0),
        )
        val text = adherence(pill, log, from, wed.atTime(12, 0), zone, "sur 2 jours")
        assertEquals("Sur 2 jours, Doliprane : 2 prises sur 3, non notées : mercredi 8 h.", text)
    }

    @Test
    fun `a spoken name finds its habit, and the generic word finds the only medicine`() {
        val all = listOf(pill, water, sport)
        assertEquals(water, findHabit(all, "l'eau"))
        assertEquals(pill, findHabit(all, "mon médicament"))
        assertEquals(sport, findHabit(all, "Sport"))
        assertNull(findHabit(all, "yoga"))
    }

    @Test
    fun `a bare c'est fait answers the habit that was just due`() {
        assertEquals(water, habitWaitingForAnswer(listOf(pill, water), emptyList(), wed.atTime(10, 10), zone))
    }

    @Test
    fun `the store replaces a habit of the same name and keeps one answer per slot`() {
        val s = HabitStore(tmp.newFile())
        val a = s.upsert("Doliprane", listOf(LocalTime.of(8, 0)), emptyList(), true, 0)!!
        val b = s.upsert("doliprane", listOf(LocalTime.of(9, 0)), emptyList(), true, 0)!!
        assertEquals(a.id, b.id)
        assertEquals(listOf("09:00"), s.load().habits.single().times)
        s.answer(a.id, 1000L, HabitAnswer.SKIPPED, 1)
        s.answer(a.id, 1000L, HabitAnswer.DONE, 2)
        assertEquals(HabitAnswer.DONE, s.load().log.single().answer)
        assertTrue(s.remove(a.id))
        assertTrue(s.load().log.isEmpty())
    }

    private fun call(text: String) = interpret(text) as OfflineAction.ToolCall

    @Test
    fun `offline, taking and asking about a medicine are understood`() {
        assertEquals(mapOf("action" to "done", "name" to "medicament"), call("J'ai pris mon médicament").args)
        assertEquals("status", call("Est-ce que j'ai pris mon médicament aujourd'hui ?").args["action"])
        assertEquals("medicament", call("Est-ce que j'ai pris mon médicament aujourd'hui ?").args["name"])
        assertEquals(mapOf("action" to "done"), call("C'est fait").args)
    }
}
