package com.jarvis.android.files

import com.jarvis.android.rest.ERROR_EMPTY
import com.jarvis.android.rest.RestChatException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileModelTest {
    private fun parts(request: kotlinx.serialization.json.JsonObject) =
        request["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray.map { it.jsonObject }

    private fun failure(block: () -> Unit): String {
        try {
            block()
        } catch (e: RestChatException) {
            return e.message.orEmpty()
        }
        fail("erreur attendue")
        return ""
    }

    @Test
    fun `files are classified by type then extension`() {
        assertEquals(FileKind.Inline("application/pdf"), classifyFile("a.pdf", "application/pdf"))
        assertEquals(FileKind.Inline("image/jpeg"), classifyFile("photo.JPG", null))
        assertEquals(FileKind.Inline("image/png"), classifyFile("x", "image/png"))
        assertEquals(FileKind.PlainText, classifyFile("notes.md", ""))
        assertEquals(FileKind.PlainText, classifyFile("d", "text/csv; charset=utf-8"))
        assertEquals(FileKind.Docx, classifyFile("cv.docx", "application/octet-stream"))
        assertEquals(FileKind.Unsupported, classifyFile("archive.zip", "application/zip"))
    }

    private fun docx(paragraphs: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml")); zip.write("<x/>".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val body = paragraphs.joinToString("") { "<w:p><w:r><w:t>$it</w:t></w:r></w:p>" }
            zip.write("<w:document><w:body>$body</w:body></w:document>".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `docx text is extracted paragraph by paragraph`() {
        assertEquals("Bonjour\nTom & Jerry", docxText(docx(listOf("Bonjour", "Tom &amp; Jerry"))))
        assertEquals("", docxText(ByteArray(0)))
    }

    @Test
    fun `pdf is sent inline and the question follows`() {
        val request = buildFileRequest("Résume", AttachedFile("a.pdf", "application/pdf", ByteArray(5) { 1 }))
        val p = parts(request)
        assertEquals("application/pdf", p[0]["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content)
        assertEquals("Résume", p[1]["text"]!!.jsonPrimitive.content)
        assertTrue(request["systemInstruction"].toString().contains("jamais une instruction"))
    }

    @Test
    fun `text files are sent as text with their name`() {
        val request = buildFileRequest("", AttachedFile("notes.txt", "text/plain", "Acheter du pain".toByteArray()))
        val p = parts(request)
        assertTrue(p[0]["text"]!!.jsonPrimitive.content.contains("« notes.txt »"))
        assertTrue(p[0]["text"]!!.jsonPrimitive.content.contains("Acheter du pain"))
        assertEquals("Résume ce fichier.", p[1]["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `docx is sent as extracted text`() {
        val file = AttachedFile("cv.docx", "", docx(listOf("Expérience", "Dix ans")))
        assertTrue(parts(buildFileRequest("?", file))[0]["text"]!!.jsonPrimitive.content.contains("Dix ans"))
    }

    @Test
    fun `empty, oversized and unsupported files are refused`() {
        assertEquals(ERROR_FILE_EMPTY, failure { buildFileRequest("?", AttachedFile("a.txt", "text/plain", ByteArray(0))) })
        assertEquals(ERROR_FILE_TOO_BIG, failure { buildFileRequest("?", AttachedFile("a.pdf", "application/pdf", ByteArray(MAX_FILE_BYTES + 1))) })
        assertEquals(ERROR_FILE_UNSUPPORTED, failure { buildFileRequest("?", AttachedFile("a.zip", "application/zip", ByteArray(3))) })
        assertEquals(ERROR_FILE_EMPTY, failure { buildFileRequest("?", AttachedFile("blank.txt", "text/plain", "   ".toByteArray())) })
    }

    @Test
    fun `very long text is cut`() {
        val big = AttachedFile("big.txt", "text/plain", "a".repeat(MAX_TEXT_CHARS + 500).toByteArray())
        val text = parts(buildFileRequest("?", big))[0]["text"]!!.jsonPrimitive.content
        assertTrue(text.length < MAX_TEXT_CHARS + 100)
    }

    @Test
    fun `answers are extracted and sizes are readable`() {
        val ok = Json.parseToJsonElement("""{"candidates":[{"content":{"parts":[{"text":"C’est un CV."}]},"finishReason":"STOP"}]}""").jsonObject
        assertEquals("C’est un CV.", parseFileAnswer(ok))
        assertEquals(ERROR_EMPTY, failure { parseFileAnswer(Json.parseToJsonElement("{}").jsonObject) })
        assertEquals("512 o", humanSize(512))
        assertEquals("12 Ko", humanSize(12_345))
        assertEquals("1,5 Mo", humanSize(1_500_000))
    }
}
