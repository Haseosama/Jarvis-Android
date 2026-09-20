package com.jarvis.android.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class PptxTest {
    private fun entries(bytes: ByteArray): Map<String, String> {
        val map = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                map[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return map
    }

    private val deck = """
        Un aperçu du projet pour l'équipe.

        # Contexte
        - Le marché grandit de 12 % par an
        - Trois concurrents directs
        Une phrase de plus.

        ## Plan d'action
        ### Court terme
        1. Recruter
        2. Former

        ---

        # Chiffres
        | Trimestre | Ventes |
        | --- | --- |
        | T1 | 120 |
        | T2 | 150 |
    """.trimIndent()

    @Test
    fun `a heading starts a slide and the title makes a title slide`() {
        val slides = slidesFromBlocks("Projet Aurora", parseBlocks(deck))
        assertEquals(listOf("Projet Aurora", "Contexte", "Plan d'action", "Chiffres"), slides.map { it.title })
        assertTrue(slides[0].isTitle)
        assertEquals("Un aperçu du projet pour l'équipe.", slides[0].subtitle)
        assertEquals(listOf(true, true, false), slides[1].lines.map { it.bullet })
        assertEquals(SlideLine("Court terme", bold = true), slides[2].lines[0])
        assertEquals(SlideLine("T1  ·  120"), slides[3].lines[1])
        assertTrue(slides[3].lines[0].bold)
    }

    @Test
    fun `a first level one heading is the title when none is given`() {
        val slides = slidesFromBlocks("", parseBlocks("# Bilan\n\n## Points\n- un\n- deux"))
        assertEquals(listOf("Bilan", "Points"), slides.map { it.title })
        assertTrue(slides[0].isTitle)
    }

    @Test
    fun `a slide that is too full continues on the next one`() {
        val bullets = (1..10).joinToString("\n") { "- point $it" }
        val slides = slidesFromBlocks("", parseBlocks("## Liste\n$bullets"))
        assertEquals(listOf("Liste", "Liste (suite)"), slides.map { it.title })
        assertEquals(7, slides[0].lines.size)
        assertEquals(3, slides[1].lines.size)
    }

    @Test
    fun `the package has every part and well formed xml`() {
        val bytes = buildPptx("Projet Aurora", parseBlocks(deck))
        val parts = entries(bytes)
        for (name in listOf("[Content_Types].xml", "_rels/.rels", "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels", "ppt/theme/theme1.xml",
            "ppt/slideMasters/slideMaster1.xml", "ppt/slideLayouts/slideLayout1.xml", "ppt/slideLayouts/slideLayout2.xml",
            "ppt/slides/slide1.xml", "ppt/slides/slide4.xml", "ppt/slides/_rels/slide4.xml.rels")) {
            assertTrue("missing $name", name in parts)
        }
        assertFalse("ppt/slides/slide5.xml" in parts)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for ((name, xml) in parts) factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).also { assertTrue(name, it.documentElement != null) }
        assertTrue(parts.getValue("ppt/slides/slide2.xml").contains("Le marché grandit de 12 % par an"))
        assertTrue(parts.getValue("[Content_Types].xml").contains("/ppt/slides/slide4.xml"))
        File("build").takeIf { it.isDirectory }?.resolve("sample-deck.pptx")?.writeBytes(bytes)
    }

    @Test
    fun `text is escaped`() {
        val parts = entries(buildPptx("R&D <2026>", parseBlocks("## Q&A\n- a < b")))
        assertTrue(parts.getValue("ppt/slides/slide1.xml").contains("R&amp;D &lt;2026&gt;"))
        assertTrue(parts.getValue("ppt/slides/slide2.xml").contains("a &lt; b"))
    }

    @Test
    fun `an empty deck still has a slide`() {
        assertEquals(1, entries(buildPptx("Vide", emptyList())).keys.count { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) })
    }
}
