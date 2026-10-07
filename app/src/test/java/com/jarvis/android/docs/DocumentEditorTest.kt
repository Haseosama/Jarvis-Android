package com.jarvis.android.docs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocumentEditorTest {
    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val map = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                map[entry.name] = zip.readBytes()
            }
        }
        return map
    }

    private fun assertXml(name: String, bytes: ByteArray) {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw AssertionError("$name n'est pas du XML valide : ${e.message}")
        }
    }

    private val sample = """
        # Visite de l'usine
        Une usine abandonnée.${"  "}
        Deuxième ligne du même paragraphe.

        ![La grande salle|50](galerie:dernière)

        > Personne n'était venu
        > depuis vingt ans.

        - [x] Lampe
        - [ ] Trépied

        ```
        fun main() {
            println("ok")
        }
        ```

        ---

        [saut de page]

        | Pièce | État |
        | --- | --- |
        | Hall | effondré |
    """.trimIndent()

    @Test
    fun `the new blocks are read`() {
        val blocks = parseBlocks(sample)
        assertEquals(Block.Heading(1, "Visite de l'usine"), blocks[0])
        assertEquals(Block.Paragraph("Une usine abandonnée.\nDeuxième ligne du même paragraphe."), blocks[1])
        assertEquals(Block.Image("galerie:dernière", "La grande salle", 50), blocks[2])
        assertEquals(Block.Quote("Personne n'était venu depuis vingt ans."), blocks[3])
        assertEquals(Block.Checklist(listOf(CheckItem("Lampe", true), CheckItem("Trépied", false))), blocks[4])
        assertEquals(Block.Code("fun main() {\n    println(\"ok\")\n}"), blocks[5])
        assertEquals(Block.Divider, blocks[6])
        assertEquals(Block.PageBreak, blocks[7])
        assertEquals(Block.Table(listOf(listOf("Pièce", "État"), listOf("Hall", "effondré"))), blocks[8])
        assertEquals(9, blocks.size)
        assertEquals(Block.Image("https://x.fr/a.jpg", "", 100), parseBlocks("![](https://x.fr/a.jpg)").single())
    }

    @Test
    fun `what the editor writes reads back the same`() {
        val blocks = listOf(
            Block.Heading(2, "Titre"),
            Block.Paragraph("Ligne un\nLigne deux"),
            Block.Image("/data/user/0/x/images/a.jpg", "Légende [1]", 70),
            Block.Bullets(listOf("a", "b"), numbered = true),
            Block.Bullets(listOf("c"), numbered = false),
            Block.Checklist(listOf(CheckItem("fait", true))),
            Block.Table(listOf(listOf("A", "B|C"), listOf("", "2"))),
            Block.Quote("Une citation\nsur deux paragraphes"),
            Block.Code("  indenté\nfin"),
            Block.Divider,
            Block.PageBreak,
        )
        val back = parseBlocks(toMarkdown(blocks))
        assertEquals(Block.Image("/data/user/0/x/images/a.jpg", "Légende [1)", 70), back[2])
        assertEquals(Block.Table(listOf(listOf("A", "B/C"), listOf("", "2"))), back[6])
        assertEquals(blocks.filterIndexed { i, _ -> i != 2 && i != 6 }, back.filterIndexed { i, _ -> i != 2 && i != 6 })
        // the editor's untouched new blocks are not kept
        assertEquals("", toMarkdown(listOf(Block.Paragraph(" "), Block.Bullets(listOf("", ""), false), Block.Table(listOf(listOf("", ""))))))
        assertEquals("- x", toMarkdown(listOf(Block.Bullets(listOf("", "x", ""), false))))
    }

    @Test
    fun `image sources are understood`() {
        assertEquals(ImageSource.Gallery(1), imageSource("galerie:dernière"))
        assertEquals(ImageSource.Gallery(1), imageSource("Galerie:"))
        assertEquals(ImageSource.Gallery(3), imageSource("galerie:3"))
        assertEquals(ImageSource.GalleryDay(LocalDate.of(2026, 10, 5), 1), imageSource("galerie:2026-10-05"))
        assertEquals(ImageSource.GalleryDay(LocalDate.of(2026, 10, 5), 2), imageSource("photo:2026-10-05#2"))
        assertEquals(ImageSource.Web("https://exemple.fr/a.png"), imageSource("<https://exemple.fr/a.png>"))
        assertEquals(ImageSource.Local("content://media/external/images/media/12"), imageSource("content://media/external/images/media/12"))
        assertEquals(ImageSource.Local("/sdcard/a.jpg"), imageSource("/sdcard/a.jpg"))
        assertNull(imageSource("galerie:0"))
        assertNull(imageSource("galerie:hier"))
        assertNull(imageSource("chat.jpg"))
    }

    @Test
    fun `pictures keep their proportions inside the box`() {
        assertEquals(200f to 100f, fitBox(400, 200, 200f, 500f))
        assertEquals(150f to 300f, fitBox(1000, 2000, 400f, 300f))
        assertEquals(800f to 400f, fitBox(100, 50, 800f, 600f))
    }

    private val picture = DocImage(byteArrayOf(-1, -40, -1, -32, 1, 2, 3), 800, 600)

    @Test
    fun `the word file embeds its pictures`() {
        val blocks = parseBlocks("# Rapport\n\n![Salle](galerie:1)\n\n![Absente](galerie:2)\n\n> cité\n\n- [x] fait\n\n[saut de page]\n\n```\ncode\n```\n\n---")
        val files = entries(buildDocx("Urbex", blocks, mapOf("galerie:1" to picture)))
        assertArrayEquals(picture.jpeg, files["word/media/image1.jpeg"])
        assertFalse(files.containsKey("word/media/image2.jpeg"))
        val document = files["word/document.xml"]!!.toString(Charsets.UTF_8)
        val rels = files["word/_rels/document.xml.rels"]!!.toString(Charsets.UTF_8)
        assertTrue(document.contains("r:embed=\"rIdImg1\""))
        assertTrue(rels.contains("Id=\"rIdImg1\"") && rels.contains("media/image1.jpeg"))
        assertTrue(files["[Content_Types].xml"]!!.toString(Charsets.UTF_8).contains("Extension=\"jpeg\""))
        assertTrue(document.contains("Image introuvable : Absente"))
        assertTrue(document.contains("w:type=\"page\""))
        assertTrue(document.contains("☑"))
        // 800 × 600 at the full width of the page: 6 120 130 EMU wide, in proportion
        assertTrue(document.contains("cx=\"6120130\" cy=\"4590097\""))
        files.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (name, bytes) -> assertXml(name, bytes) }
    }

    @Test
    fun `a picture gets its own slide, titled by its section`() {
        val blocks = parseBlocks("# Usine\n\n![La salle](galerie:1)\n\nUn mot après.\n\n# Fin\n\n- a\n\n![Sans fichier](galerie:9)")
        val slides = slidesFromBlocks("Urbex", blocks)
        assertEquals(listOf("Urbex", "Usine", "Usine (suite)", "Fin", "Sans fichier"), slides.map { it.title })
        assertEquals("galerie:1", slides[1].image)
        assertEquals("La salle", slides[1].caption)
        assertEquals("", slides[4].caption)
        val files = entries(buildPptx("Urbex", blocks, mapOf("galerie:1" to picture)))
        assertArrayEquals(picture.jpeg, files["ppt/media/image2.jpeg"])
        assertTrue(files["ppt/slides/_rels/slide2.xml.rels"]!!.toString(Charsets.UTF_8).contains("../media/image2.jpeg"))
        assertTrue(files["ppt/slides/slide2.xml"]!!.toString(Charsets.UTF_8).contains("r:embed=\"rId2\""))
        assertTrue(files["ppt/slides/slide5.xml"]!!.toString(Charsets.UTF_8).contains("Image introuvable"))
        files.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (name, bytes) -> assertXml(name, bytes) }
    }

    @Test
    fun `the web page carries its pictures`() {
        val html = buildHtml("Urbex <1>", parseBlocks("## Salle\n\n![Vue|50](galerie:1)\n\n- [ ] trépied\n\n| a | b |\n|---|---|\n| 1 | 2 |"), mapOf("galerie:1" to picture))
        assertTrue(html.contains("<title>Urbex &lt;1&gt;</title>"))
        assertTrue(html.contains("<h3>Salle</h3>"))
        assertTrue(html.contains("style=\"width:50%\""))
        assertTrue(html.contains("src=\"data:image/jpeg;base64,/9j/4AECAw==\""))
        assertTrue(html.contains("<figcaption>Vue</figcaption>"))
        assertTrue(html.contains("<th>a</th>") && html.contains("<td>2</td>"))
        assertTrue(buildPlainText("T", parseBlocks("![Vue](galerie:1)\n\n- [x] ok")).contains("[Image : Vue]\n\n[x] ok"))
    }

    @Test
    fun `drafts are kept and found by their title`() {
        val draft = DocDraft("id-1", "Rapport « urbex »", "# A\n\nB", 42L)
        assertEquals(draft, decodeDraft(encodeDraft(draft)))
        assertNull(decodeDraft("pas du json"))
        val drafts = listOf(
            DocDraft("1", "Rapport urbex usine", "", 10),
            DocDraft("2", "Liste de courses", "![x](/a.jpg)", 30),
            DocDraft("3", "Rapport urbex hôpital", "![y](/b.jpg)\n\n![x](/a.jpg)", 20),
        )
        assertEquals("2", findDraft(drafts, "")?.id)
        assertEquals("2", findDraft(drafts, "liste de courses")?.id)
        assertEquals("3", findDraft(drafts, "rapport urbex")?.id)
        assertEquals("3", findDraft(drafts, "urbex hopital")?.id)
        assertEquals("1", findDraft(drafts, "usine urbex")?.id)
        assertNull(findDraft(drafts, "factures"))
        assertEquals(setOf("/a.jpg", "/b.jpg"), usedPictures(drafts))
    }
}
