package com.jarvis.android.meetings

import android.content.Context
import com.jarvis.android.docs.DocumentStore
import com.jarvis.android.docs.buildDocx
import com.jarvis.android.docs.buildPdf
import com.jarvis.android.docs.parseBlocks
import java.io.File

/** The formats the notes can be handed over in. The notes themselves stay Markdown inside the app. */
internal enum class NoteFormat(val extension: String) { PDF("pdf"), DOCX("docx"), TXT("txt") }

/** `2026-09-20_1530_point-budget.md` becomes `2026-09-20_1530_point-budget.pdf`: the same stem, so a note has one export per format. */
internal fun exportFileName(note: File, format: NoteFormat): String = "${note.nameWithoutExtension}.${format.extension}"

/**
 * Writes the notes in a form phones open: a PDF (every phone has a viewer), a Word file or a plain text file, in Documents/Jarvis,
 * rewritten each time so it always matches the note. The Markdown file itself has no application on most phones ("text/markdown").
 */
internal fun exportNote(context: Context, note: File, format: NoteFormat): File {
    val markdown = note.readText()
    val out = File(DocumentStore.folder(context), exportFileName(note, format))
    when (format) {
        // the title is the first "# " line of the notes: it is not passed twice
        NoteFormat.PDF -> out.writeBytes(buildPdf("", parseBlocks(markdown)))
        NoteFormat.DOCX -> out.writeBytes(buildDocx("", parseBlocks(markdown)))
        NoteFormat.TXT -> out.writeText(markdown, Charsets.UTF_8)
    }
    return out
}
