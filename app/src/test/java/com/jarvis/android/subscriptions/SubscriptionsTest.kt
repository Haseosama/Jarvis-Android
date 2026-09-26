package com.jarvis.android.subscriptions

import com.jarvis.android.expenses.Expense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SubscriptionsTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 9, 26)
    private fun sub(day: Int, cents: Long = 1349, period: String = MONTHLY, month: Int = 1, name: String = "Netflix") = Subscription(1, name, cents, day, period, month)
    private fun exp(d: LocalDate, cents: Long, note: String, category: String = "divers") =
        Expense(cents, category, note, d.atTime(9, 0).atZone(zone).toInstant().toEpochMilli())

    @Test
    fun `the next payment day, the 31st being the last day of shorter months`() {
        assertEquals(LocalDate.of(2026, 10, 5), nextDue(sub(5), today))
        assertEquals(today, nextDue(sub(26), today))
        assertEquals(LocalDate.of(2026, 9, 30), nextDue(sub(31), today))
        assertEquals(LocalDate.of(2027, 2, 28), nextDue(sub(31), LocalDate.of(2027, 2, 1)))
        assertEquals(LocalDate.of(2027, 3, 15), nextDue(sub(15, period = YEARLY, month = 3), today))
        assertEquals(LocalDate.of(2026, 11, 2), nextDue(sub(2, period = YEARLY, month = 11), today))
    }

    @Test
    fun `the monthly cost spreads yearly payments, and the list is in payment order`() {
        val items = listOf(sub(5), sub(27, 1199, name = "Spotify"), sub(1, 12000, YEARLY, 3, "Assurance"))
        assertEquals(1349L + 1199L + 1000L, monthlyCents(items))
        val text = describeSubscriptions(items, today)
        assertTrue(text, text.startsWith("3 abonnements, 35,48 € par mois en tout :\n1) Spotify : 11,99 € par mois, prochain prélèvement demain"))
        assertEquals(listOf("prélèvement Spotify 11,99 € demain"), dueSoonLines(items, today))
    }

    @Test
    fun `regular payments are spotted among expenses, not the weekly shopping nor a changing amount`() {
        val expenses = listOf(
            exp(LocalDate.of(2026, 7, 5), 1349, "Netflix"), exp(LocalDate.of(2026, 8, 5), 1349, "Netflix"), exp(LocalDate.of(2026, 9, 4), 1349, "netflix"),
            exp(LocalDate.of(2026, 9, 1), 5000, "", "courses"), exp(LocalDate.of(2026, 9, 8), 4200, "", "courses"), exp(LocalDate.of(2026, 9, 15), 6100, "", "courses"),
            exp(LocalDate.of(2026, 8, 10), 3000, "Gaz"), exp(LocalDate.of(2026, 9, 10), 9000, "Gaz"),
            exp(LocalDate.of(2025, 9, 20), 12000, "Assurance habitation"), exp(LocalDate.of(2026, 9, 20), 12500, "Assurance habitation"),
        )
        val found = detectRecurring(expenses, zone)
        assertEquals(listOf("Assurance habitation", "Netflix"), found.map { it.name })
        assertEquals(Candidate("Netflix", 1349, 4, MONTHLY, 3), found[1])
        assertEquals(YEARLY, found[0].period)
        assertEquals(listOf("Assurance habitation"), detectRecurring(expenses, zone, listOf(sub(5))).map { it.name })
    }
}
