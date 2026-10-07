package com.jarvis.android.docs

import android.content.Context
import java.io.File
import java.util.UUID

/** Where the drafts and the pictures added to them are kept (inside the app, private). */
internal object DocumentDrafts {
    private fun dir(context: Context) = File(context.filesDir, "document-drafts").also { it.mkdirs() }
    fun imagesDir(context: Context) = File(dir(context), "images").also { it.mkdirs() }

    fun list(context: Context): List<DocDraft> =
        dir(context).listFiles { f -> f.isFile && f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { decodeDraft(it.readText()) }.getOrNull() }
            .sortedByDescending { it.updated }

    fun load(context: Context, id: String): DocDraft? = File(dir(context), "${safeId(id)}.json").takeIf { it.isFile }?.let { decodeDraft(it.readText()) }

    fun create(context: Context, title: String, markdown: String): DocDraft =
        save(context, DocDraft(UUID.randomUUID().toString(), title, markdown, System.currentTimeMillis()))

    fun save(context: Context, draft: DocDraft): DocDraft {
        val kept = draft.copy(updated = System.currentTimeMillis())
        val target = File(dir(context), "${safeId(kept.id)}.json")
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(encodeDraft(kept))
        if (!temp.renameTo(target)) { target.writeText(encodeDraft(kept)); temp.delete() }
        return kept
    }

    /** Deletes a draft; its pictures stay until [forgetUnusedPictures] (so the deletion can still be undone). */
    fun delete(context: Context, id: String) {
        File(dir(context), "${safeId(id)}.json").delete()
    }

    /** Deletes the pictures the editor copied that no draft uses any more. */
    fun forgetUnusedPictures(context: Context) {
        val used = usedPictures(list(context))
        imagesDir(context).listFiles().orEmpty().filter { it.absolutePath !in used }.forEach { it.delete() }
    }

    /** Copies a picture the user picked into the drafts' own folder (upright, at most 1600 pixels, without metadata); its path, or null. */
    fun keepPicture(context: Context, bytes: ByteArray): String? {
        val image = documentJpeg(bytes) ?: return null
        val file = File(imagesDir(context), UUID.randomUUID().toString() + ".jpg")
        file.writeBytes(image.jpeg)
        return file.absolutePath
    }

    private fun safeId(id: String) = id.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "x" }
}
