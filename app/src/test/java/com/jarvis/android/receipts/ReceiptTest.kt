package com.jarvis.android.receipts

import com.jarvis.android.offline.OfflineAction
import com.jarvis.android.offline.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ReceiptTest {
    private val today = LocalDate.of(2026, 9, 26)

    @Test
    fun `a label and its amount printed on one row, read as two columns, are put back together`() {
        val lines = listOf(
            OcrLine("CARREFOUR MARKET", 100, 10, 40),
            OcrLine("TOTAL", 20, 300, 330),
            OcrLine("CB", 20, 340, 370),
            OcrLine("23,45", 500, 302, 332),
            OcrLine("23,45", 500, 341, 371),
        )
        assertEquals(listOf("CARREFOUR MARKET", "TOTAL 23,45", "CB 23,45"), rowsOf(lines))
    }

    @Test
    fun `the total is the amount to pay, not a subtotal or the tax`() {
        val rows = listOf(
            "CARREFOUR MARKET", "12 RUE DE SIAM 29200 BREST", "TEL 02 98 00 00 00", "LE 24/09/2026 18:42",
            "PAIN DE MIE 1,95", "LAIT 1L X2 2,30", "POULET 8,90",
            "SOUS-TOTAL 13,15", "TOTAL TTC 13,15", "DONT TVA 5,5% 0,69", "CB 13,15", "RENDU 0,00",
        )
        val info = parseReceipt(rows, today)
        assertEquals(1315L, info.totalCents)
        assertEquals("Carrefour Market", info.merchant)
        assertEquals(LocalDate.of(2026, 9, 24), info.date)
        assertEquals("courses", guessCategory(rows))
    }

    @Test
    fun `the amount can be on the row after the word, and with no word the amount printed twice wins`() {
        assertEquals(4250L, parseReceipt(listOf("Brasserie du Port", "NET A PAYER", "42,50 €", "Merci"), today).totalCents)
        assertEquals(1890L, parseReceipt(listOf("Boutique", "article 12,00", "article 6,90", "18,90", "carte 18,90"), today).totalCents)
        assertEquals("restaurant", guessCategory(listOf("Brasserie du Port")))
    }

    @Test
    fun `thousands, dates too old or in the future, and a receipt without amounts`() {
        assertEquals(listOf(123456L, 999L), amountsIn("TOTAL 1 234,56 et 9.99"))
        assertNull(parseReceipt(listOf("LE 24/09/2025"), today).date)
        assertNull(parseReceipt(listOf("LE 30/09/2026"), today).date)
        assertNull(parseReceipt(listOf("Bonjour", "Merci de votre visite"), today).totalCents)
    }

    @Test
    fun `offline, scanning a receipt opens the camera`() {
        val a = interpret("Scanne ce ticket") as OfflineAction.ToolCall
        assertEquals("receipt", a.name)
        assertEquals("camera", a.args["source"])
        assertEquals("receipt", (interpret("ajoute ce ticket à mes dépenses") as OfflineAction.ToolCall).name)
    }
}
