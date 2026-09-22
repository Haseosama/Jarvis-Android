package com.jarvis.android.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadWebpageToolTest {
    private val base = "https://exemple.fr/article"

    @Test fun `scripts, styles and navigation are left out of the text`() {
        val html = """
            <html><head><title>Un titre</title></head><body>
            <nav>Accueil | Contact</nav>
            <header>Bandeau</header>
            <script>alert(1)</script>
            <style>.x{color:red}</style>
            <main><p>Le texte utile est ici.</p><p>Une deuxième phrase.</p></main>
            <footer>Pied de page</footer>
            </body></html>
        """.trimIndent()
        val page = extractReadableContent(html, base)
        assertEquals("Un titre", page.title)
        assertTrue(page.text.contains("Le texte utile est ici."))
        assertTrue(page.text.contains("Une deuxième phrase."))
        assertFalse(page.text.contains("Accueil"))
        assertFalse(page.text.contains("Bandeau"))
        assertFalse(page.text.contains("Pied de page"))
        assertFalse(page.text.contains("alert"))
        assertFalse(page.truncated)
    }

    @Test fun `without a main or article, the body is used`() {
        val html = "<html><body><nav>Menu</nav><div><p>Contenu du corps.</p></div></body></html>"
        val page = extractReadableContent(html, base)
        assertTrue(page.text.contains("Contenu du corps."))
        assertFalse(page.text.contains("Menu"))
    }

    @Test fun `a long page is cut and says so`() {
        val html = "<html><body><main><p>" + "mot ".repeat(3000) + "</p></main></body></html>"
        val page = extractReadableContent(html, base)
        assertEquals(MAX_PAGE_TEXT_CHARS, page.text.length)
        assertTrue(page.truncated)
        assertTrue(formatPageContent(page).contains("[texte tronqué]"))
    }

    @Test fun `links are collected, absolute, deduplicated, and short labels are dropped`() {
        val html = """
            <html><body><main>
            <a href="/suite">Lire la suite</a>
            <a href="/suite">Lire la suite</a>
            <a href="https://autre.fr/page">Un autre site</a>
            <a href="#">x</a>
            <a href="javascript:void(0)">Cliquez ici pour agir</a>
            <a href="mailto:a@b.fr">Nous écrire</a>
            </main></body></html>
        """.trimIndent()
        val page = extractReadableContent(html, base)
        val urls = page.links.map { it.url }
        assertEquals(2, urls.size)
        assertTrue(urls.contains("https://exemple.fr/suite"))
        assertTrue(urls.contains("https://autre.fr/page"))
    }

    @Test fun `at most MAX_PAGE_LINKS links are kept`() {
        val links = (1..30).joinToString("\n") { "<a href=\"/p$it\">Lien numéro $it</a>" }
        val page = extractReadableContent("<html><body><main>$links</main></body></html>", base)
        assertEquals(MAX_PAGE_LINKS, page.links.size)
    }

    @Test fun `an empty page says there is nothing readable`() {
        val page = extractReadableContent("<html><body><nav>Menu</nav></body></html>", base)
        assertTrue(page.text.isBlank())
        assertTrue(formatPageContent(page).contains("Aucun texte lisible"))
    }

    @Test fun `the formatted answer carries the title, the url and the links`() {
        val page = WebpageContent("Titre", base, "Un texte.", listOf(WebSearchResult("Suite", "https://exemple.fr/suite", "")), false)
        val out = formatPageContent(page)
        assertTrue(out.contains("Titre") && out.contains(base) && out.contains("Un texte."))
        assertTrue(out.contains("1. Suite — https://exemple.fr/suite"))
    }

    @Test fun `private and local addresses are refused before any request is made`() {
        for (host in listOf("localhost", "127.0.0.1", "192.168.1.10", "10.0.0.5", "printer.local")) {
            assertTrue(host, com.jarvis.android.plugins.isForbiddenHost(host))
        }
        assertFalse(com.jarvis.android.plugins.isForbiddenHost("exemple.fr"))
    }
}
