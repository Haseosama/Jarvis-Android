package com.jarvis.android.meetings

import com.jarvis.android.docs.DocumentStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class NoteExportTest {
    @Test fun `an export keeps the note's name and takes the format's extension`() {
        val note = File("2026-09-20_1530_point-budget.md")
        assertEquals("2026-09-20_1530_point-budget.pdf", exportFileName(note, NoteFormat.PDF))
        assertEquals("2026-09-20_1530_point-budget.docx", exportFileName(note, NoteFormat.DOCX))
        assertEquals("2026-09-20_1530_point-budget.txt", exportFileName(note, NoteFormat.TXT))
    }

    @Test fun `markdown is opened as plain text because no phone app takes text markdown`() {
        assertEquals("text/plain", DocumentStore.viewMimeFor("md"))
        assertEquals("text/plain", DocumentStore.viewMimeFor("MD"))
        assertEquals("application/pdf", DocumentStore.viewMimeFor("pdf"))
        assertEquals("text/csv", DocumentStore.viewMimeFor("csv"))
        // what is uploaded to Drive keeps its real type
        assertNotEquals(DocumentStore.viewMimeFor("md"), DocumentStore.mimeFor("md"))
    }
}
