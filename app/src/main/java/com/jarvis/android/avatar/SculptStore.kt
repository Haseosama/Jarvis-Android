package com.jarvis.android.avatar

import android.content.Context
import java.io.File

/**
 * The polygon editor's retouches, one small text file per face in the app's private files (avatar_sculpt/), so a face's thousands of
 * offsets do not weigh on every settings change.
 */
internal object SculptStore {
    private fun file(context: Context, face: String): File =
        File(File(context.filesDir, "avatar_sculpt"), fileName(face))

    /** "Léa" → "Lea.txt": a plain file name from the face's label. */
    internal fun fileName(face: String): String =
        java.text.Normalizer.normalize(face, java.text.Normalizer.Form.NFD).replace(Regex("[^A-Za-z0-9_-]"), "").ifEmpty { "face" } + ".txt"

    fun load(context: Context, face: String): Sculpt =
        try { file(context, face).takeIf { it.isFile }?.readText()?.let { MeshSculpt.decode(it) } ?: emptyMap() } catch (_: Exception) { emptyMap() }

    fun save(context: Context, face: String, sculpt: Sculpt) {
        try {
            val f = file(context, face)
            if (sculpt.isEmpty()) { f.delete(); return }
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(MeshSculpt.encode(sculpt))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Exception) {
            // a retouch that could not be written is lost at the next start, nothing worse
        }
    }
}
