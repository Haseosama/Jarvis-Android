package com.jarvis.android.meetings

import com.jarvis.android.rest.RestChatException
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime

class MeetingNotesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `the request carries the audio and the instruction`() {
        val request = buildMeetingRequest(ByteArray(20_000) { 1 })
        assertTrue(request["systemInstruction"]!!.toString().contains("Résumé"))
        val parts = request["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals("audio/aac", parts[0].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `too short and too long recordings are refused`() {
        try { buildMeetingRequest(ByteArray(10)); throw AssertionError("should refuse") } catch (_: RestChatException) { }
        try { buildMeetingRequest(ByteArray(15_000_001)); throw AssertionError("should refuse") } catch (_: RestChatException) { }
    }

    @Test
    fun `the title is the first heading`() {
        assertEquals("Point budget", noteTitle("Intro\n# Point budget\n## Résumé"))
        assertEquals("Réunion", noteTitle("Pas de titre"))
    }

    @Test
    fun `file names carry the date and the title`() {
        assertEquals("2026-09-20_1530_point-budget.md", noteFileName(LocalDateTime.of(2026, 9, 20, 15, 30), "Point budget"))
    }

    @Test
    fun `notes are listed newest first and found by name or title`() {
        val dir = folder.newFolder()
        val older = java.io.File(dir, "2026-09-01_0900_a.md").apply { writeText("# Ancienne\ntexte"); setLastModified(1_000) }
        val newer = java.io.File(dir, "2026-09-02_0900_b.md").apply { writeText("# Récente réunion\ntexte"); setLastModified(2_000) }
        java.io.File(dir, "autre.txt").writeText("ignoré")
        val notes = listNotes(dir)
        assertEquals(listOf(newer, older), notes.map { it.file })
        assertEquals("Récente réunion", notes[0].title)
        assertEquals(newer, findNote(notes, null)!!.file)
        assertEquals(older, findNote(notes, "ancienne")!!.file)
        assertEquals(older, findNote(notes, "2026-09-01_0900_a")!!.file)
        assertNull(findNote(notes, "introuvable"))
    }
}
