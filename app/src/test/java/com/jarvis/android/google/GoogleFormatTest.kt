package com.jarvis.android.google

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleFormatTest {
    @Test
    fun `a raw message decodes to a mail with encoded subject and body`() {
        val raw = buildRawMessage("ami@example.org", "Café à 8 h", "Bonjour,\nà demain.")
        val text = String(decodeBase64Url(raw), Charsets.UTF_8)
        assertTrue(text.startsWith("To: ami@example.org\r\n"))
        assertTrue(text.contains("Subject: =?UTF-8?B?"))
        assertTrue(text.contains("Content-Type: text/plain; charset=UTF-8"))
        val body = text.substringAfter("\r\n\r\n").replace("\r\n", "")
        assertEquals("Bonjour,\nà demain.", String(java.util.Base64.getDecoder().decode(body), Charsets.UTF_8))
    }

    @Test
    fun `a plain ascii subject stays plain`() {
        assertEquals("Hello", encodeHeader("Hello"))
    }

    @Test
    fun `bad addresses and injected headers are refused`() {
        assertTrue(validAddress("a.b@c.fr"))
        assertFalse(validAddress("a@b"))
        assertFalse(validAddress("Nom <a@b.fr>"))
        assertFalse(validAddress("a@b.fr, c@d.fr"))
        try { buildRawMessage("a@b.fr", "objet\r\nBcc: x@y.fr", "x"); throw AssertionError("should refuse") } catch (_: IllegalArgumentException) { }
        try { buildRawMessage("pas une adresse", "objet", "x"); throw AssertionError("should refuse") } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `gmail queries`() {
        assertEquals("is:unread", gmailQuery("", true))
        assertEquals("is:unread (from:anne)", gmailQuery("from:anne", true))
        assertEquals("from:anne", gmailQuery("from:anne", false))
        assertEquals("in:inbox", gmailQuery("", false))
    }

    private fun message(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `a listed message is summarised`() {
        val summary = parseMailSummary(message("""{"id":"m1","snippet":"Salut","labelIds":["INBOX","UNREAD"],"payload":{"headers":[{"name":"From","value":"Anne <a@b.fr>"},{"name":"subject","value":"Rendez-vous"},{"name":"Date","value":"Mon, 1 Sep 2026"}]}}"""))
        assertEquals("m1", summary.id)
        assertEquals("Anne <a@b.fr>", summary.from)
        assertEquals("Rendez-vous", summary.subject)
        assertTrue(summary.unread)
        assertEquals("(sans objet)", parseMailSummary(message("""{"id":"m2","payload":{"headers":[]}}""")).subject)
    }

    private fun b64(text: String) = base64Url(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `the body is the plain part, found in nested parts`() {
        val plain = b64("Le texte brut")
        val html = b64("<p>Le <b>html</b></p>")
        val m = message("""{"payload":{"mimeType":"multipart/mixed","parts":[{"mimeType":"multipart/alternative","parts":[{"mimeType":"text/plain","body":{"data":"$plain"}},{"mimeType":"text/html","body":{"data":"$html"}}]}]}}""")
        assertEquals("Le texte brut", extractBody(m))
    }

    @Test
    fun `without a plain part the html is turned into text`() {
        val html = b64("<div>Bonjour</div><script>evil()</script><p>Le&nbsp;monde &amp; vous</p>")
        val m = message("""{"payload":{"mimeType":"text/html","body":{"data":"$html"}}}""")
        assertEquals("Bonjour\nLe monde & vous", extractBody(m))
    }

    @Test
    fun `drive searches are escaped`() {
        assertEquals("it\\'s", driveEscape("it's"))
        assertEquals("(name contains 'facture' or fullText contains 'facture') and trashed = false", driveQuery("facture"))
        assertEquals("trashed = false", driveQuery("  "))
        assertFalse(driveQuery("a' or name contains 'b").contains("' or name contains 'b"))
    }

    @Test
    fun `drive files are read according to their type`() {
        assertEquals(DriveRead.Export("text/plain"), driveReadKind("application/vnd.google-apps.document"))
        assertEquals(DriveRead.Export("text/csv"), driveReadKind("application/vnd.google-apps.spreadsheet"))
        assertEquals(DriveRead.DownloadText, driveReadKind("text/markdown"))
        assertEquals(DriveRead.DownloadForAnalysis("application/pdf"), driveReadKind("application/pdf"))
        assertNull(driveReadKind("application/zip"))
    }
}
