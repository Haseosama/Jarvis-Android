package com.jarvis.android.expenses

import com.jarvis.android.offline.OfflineAction
import com.jarvis.android.offline.interpret
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneId

class ExpenseStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Europe/Paris")
    private fun store() = ExpenseStore(tmp.newFile(), zone = { zone })
    private fun at(day: LocalDate) = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `amounts are read the ways they are said or typed`() {
        assertEquals(1200L, parseAmountCents("12"))
        assertEquals(1250L, parseAmountCents("12,50"))
        assertEquals(1250L, parseAmountCents("12.5"))
        assertEquals(1250L, parseAmountCents("12 euros 50"))
        assertEquals(1250L, parseAmountCents("12€50"))
        assertEquals(120000L, parseAmountCents("1 200 €"))
        assertNull(parseAmountCents("douze"))
        assertNull(parseAmountCents("0"))
        assertNull(parseAmountCents("99999999"))
    }

    @Test
    fun `amounts are written back in French`() {
        assertEquals("12 €", formatCents(1200))
        assertEquals("12,05 €", formatCents(1205))
        assertEquals("1 200,50 €", formatCents(120050))
    }

    @Test
    fun `categories fold case, accents and a leading article, so they add up`() {
        assertEquals("restaurant", normalizeCategory("Au Restaurant"))
        assertEquals("sante", normalizeCategory("la Santé"))
        assertEquals(DEFAULT_CATEGORY, normalizeCategory(""))
    }

    @Test
    fun `periods start where a person expects them to`() {
        val wednesday = LocalDate.of(2026, 9, 23)
        assertEquals(LocalDate.of(2026, 9, 21).atStartOfDay(zone).toInstant().toEpochMilli(), periodStart(ExpensePeriod.WEEK, wednesday, zone))
        assertEquals(LocalDate.of(2026, 9, 1).atStartOfDay(zone).toInstant().toEpochMilli(), periodStart(ExpensePeriod.MONTH, wednesday, zone))
        assertEquals(ExpensePeriod.MONTH, parsePeriod(""))
        assertEquals(ExpensePeriod.WEEK, parsePeriod("Cette semaine"))
        assertEquals(ExpensePeriod.DAY, parsePeriod("aujourd'hui"))
    }

    @Test
    fun `a month's summary totals by category, biggest first, and leaves last month out`() = runBlocking {
        val s = store()
        val today = LocalDate.of(2026, 9, 23)
        s.add(1250, "restaurant", at = at(today))
        s.add(3000, "Courses", at = at(today.minusDays(5)))
        s.add(1750, "Restaurant", at = at(today.minusDays(10)))
        s.add(9900, "restaurant", at = at(LocalDate.of(2026, 8, 30)))
        val text = summarize(s.inPeriod(ExpensePeriod.MONTH, today), ExpensePeriod.MONTH)
        assertEquals("Ce mois-ci : 60 € en 3 dépense(s) — restaurant 30 €, courses 30 €.", text)
        assertEquals(1, s.inPeriod(ExpensePeriod.MONTH, today, category = "courses").size)
    }

    @Test
    fun `remove_last takes back the most recent one, and out-of-range amounts are refused`() = runBlocking {
        val s = store()
        val today = LocalDate.of(2026, 9, 23)
        s.add(500, "cafe", at = at(today.minusDays(1)))
        s.add(800, "cinema", at = at(today))
        assertEquals("cinema", s.removeLast()?.category)
        assertEquals(1, s.inPeriod(ExpensePeriod.ALL, today).size)
        assertTrue(!s.add(0, "x"))
        assertEquals("Aucune dépense notée cette semaine.", summarize(emptyList(), ExpensePeriod.WEEK))
    }

    // ── offline commands ────────────────────────────────────────────────────

    private fun call(text: String) = interpret(text) as OfflineAction.ToolCall

    @Test
    fun `offline, a spending is understood with the cents however the recogniser wrote them`() {
        val a = call("J'ai dépensé 12,50 € au restaurant")
        assertEquals("expenses", a.name)
        assertEquals("12,50", a.args["amount"])
        assertEquals("restaurant", a.args["category"])
        assertEquals("12,50", call("j'ai payé 12 euros 50 pour le cinéma").args["amount"])
        assertEquals("30,00", call("j'ai dépensé 30 euros").args["amount"])
        assertEquals(null, call("j'ai dépensé 30 euros").args["category"])
    }

    @Test
    fun `offline, the spending question picks its period`() {
        assertEquals("mois", call("combien j'ai dépensé ce mois-ci").args["period"])
        assertEquals("semaine", call("combien j'ai dépensé cette semaine ?").args["period"])
        assertEquals("jour", call("combien ai-je dépensé aujourd'hui").args["period"])
        assertEquals("remove_last", call("annule la dernière dépense").args["action"])
    }
}
