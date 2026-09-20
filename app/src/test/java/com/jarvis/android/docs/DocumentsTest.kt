package com.jarvis.android.docs

import com.jarvis.android.files.docxText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class DocumentsTest {
    private fun entries(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    @Test
    fun `markdown becomes headings paragraphs lists and tables`() {
        val blocks = parseBlocks(
            "# Titre\n\nUn **paragraphe** sur\ndeux lignes.\n\n- un\n- deux\n\n1. premier\n2. second\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n## Fin"
        )
        assertEquals(
            listOf(
                Block.Heading(1, "Titre"),
                Block.Paragraph("Un paragraphe sur deux lignes."),
                Block.Bullets(listOf("un", "deux"), false),
                Block.Bullets(listOf("premier", "second"), true),
                Block.Table(listOf(listOf("a", "b"), listOf("1", "2"))),
                Block.Heading(2, "Fin"),
            ),
            blocks,
        )
    }

    @Test
    fun `rows can be json markdown or delimited text`() {
        val expected = listOf(listOf("nom", "prix"), listOf("pain", "2,5"))
        assertEquals(expected, parseRows("[[\"nom\",\"prix\"],[\"pain\",\"2,5\"]]"))
        assertEquals(expected, parseRows("| nom | prix |\n|---|---|\n| pain | 2,5 |"))
        assertEquals(expected, parseRows("nom;prix\npain;2,5"))
        assertEquals(listOf(listOf("a, b", "c\"d")), parseRows("\"a, b\",\"c\"\"d\""))
        assertTrue(parseRows("   ").isEmpty())
    }

    @Test
    fun `csv quotes what needs quotes`() {
        assertEquals("a,\"b,c\",\"d\"\"e\"\r\n1,2,3", toCsv(listOf(listOf("a", "b,c", "d\"e"), listOf("1", "2", "3"))))
    }

    @Test
    fun `file names are safe`() {
        assertEquals("compte-rendu-de-reunion", safeFileName("Compte-rendu de réunion !"))
        assertEquals("document", safeFileName("???"))
        assertTrue(safeFileName("x".repeat(200)).length <= 60)
    }

    @Test
    fun `a word file can be read back by the app's own reader`() {
        val bytes = buildDocx("Mon titre", parseBlocks("# Partie\n\nDu texte & <des> caractères.\n\n- point un\n- point deux\n\n| a | b |\n| 1 | 2 |"))
        val text = docxText(bytes)
        assertTrue(text.contains("Mon titre"))
        assertTrue(text.contains("Partie"))
        assertTrue(text.contains("Du texte & <des> caractères."))
        assertTrue(text.contains("point deux"))
        assertTrue(text.contains("1"))
        assertTrue(entries(bytes).keys.containsAll(listOf("[Content_Types].xml", "word/document.xml", "word/styles.xml")))
    }

    @Test
    fun `an excel file keeps numbers numbers and formulas formulas`() {
        val files = entries(buildXlsx("Budget", listOf(listOf("Poste", "Montant"), listOf("Loyer", "800"), listOf("Café", "2,5"), listOf("Total", "=SUM(B2:B3)"))))
        val sheet = files.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("<c r=\"B2\"><v>800</v></c>"))
        assertTrue(sheet.contains("<c r=\"B3\"><v>2.5</v></c>"))
        assertTrue(sheet.contains("<f>SUM(B2:B3)</f>"))
        assertTrue(sheet.contains("<t xml:space=\"preserve\">Loyer</t>"))
        assertTrue(files.getValue("xl/workbook.xml").contains("name=\"Budget\""))
    }

    @Test
    fun `column letters go past Z`() {
        assertEquals("A", columnLetters(0))
        assertEquals("Z", columnLetters(25))
        assertEquals("AA", columnLetters(26))
        assertEquals("AB", columnLetters(27))
    }

    @Test
    fun `xml is escaped and control characters dropped`() {
        assertEquals("a&amp;b&lt;c&gt;&quot;d", xmlEscape("a&b<c>\"d"))
        assertEquals("ab", xmlEscape("ab"))
    }

    @Test
    fun `text wraps at spaces and cuts very long words`() {
        val measure = { s: String -> s.length.toFloat() }
        assertEquals(listOf("aaa bbb", "ccc"), wrapText("aaa bbb ccc", 7f, measure))
        assertEquals(listOf("abcd", "efgh", "ij"), wrapText("abcdefghij", 4f, measure))
        assertEquals(listOf("a", "b"), wrapText("a\nb", 10f, measure))
    }
}
