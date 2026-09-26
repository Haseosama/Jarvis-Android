package com.jarvis.android.budgets

import com.jarvis.android.offline.OfflineAction
import com.jarvis.android.offline.interpret
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class BudgetsTest {
    @Test
    fun `alerts at 80 and 100 percent, each once a month, a higher one after a lower one`() {
        assertEquals(0, budgetLevel(31_000, 40_000))
        assertEquals(80, budgetLevel(32_000, 40_000))
        assertEquals(100, budgetLevel(40_000, 40_000))
        assertEquals(0, budgetLevel(10, 0))
        assertEquals(80, newAlert(80, null, "2026-09"))
        assertEquals(0, newAlert(80, "2026-09:80", "2026-09"))
        assertEquals(100, newAlert(100, "2026-09:80", "2026-09"))
        assertEquals(0, newAlert(80, "2026-09:100", "2026-09"))
        // A new month starts again from nothing.
        assertEquals(80, newAlert(80, "2026-08:100", "2026-09"))
    }

    @Test
    fun `the line says what is spent, what is left and for how many days`() {
        val day = LocalDate.of(2026, 9, 26)
        assertEquals("courses : 322 € sur 400 € (80 %), il reste 78 € pour 5 jours", budgetLine("courses", 32_200, 40_000, day))
        assertEquals("total : 1 612,50 € sur 1 500 € (107 %), dépassé de 112,50 €", budgetLine(TOTAL_BUDGET, 161_250, 150_000, day).replace(' ', ' '))
        assertEquals("il reste 10 € pour 1 jour", budgetLine("x", 0, 1_000, LocalDate.of(2026, 9, 30)).substringAfter("), "))
        assertEquals(TOTAL_BUDGET, budgetKey("Total"))
        assertEquals("courses", budgetKey("les Courses"))
    }

    @Test
    fun `offline, where the budget stands`() {
        assertEquals("budget", (interpret("Où en est mon budget ?") as OfflineAction.ToolCall).name)
        assertEquals("status", (interpret("il me reste combien") as OfflineAction.ToolCall).args["action"])
    }
}
