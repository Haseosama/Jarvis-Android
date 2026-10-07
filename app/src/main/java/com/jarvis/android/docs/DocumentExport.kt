package com.jarvis.android.docs

import android.content.Context
import okhttp3.OkHttpClient
import java.io.File

/** The formats a document made of blocks can be exported to (tables of figures, xlsx and csv, are made by the document tool). */
internal val BLOCK_DOCUMENT_TYPES = listOf("pdf", "docx", "pptx", "html", "md", "txt")

/** A written document: its file, and how many of its pictures could not be found (written as "image introuvable"). */
internal class ExportedDocument(val file: File, val bytes: Int, val missingImages: Int)

/**
 * Writes the document [title] + [markdown] as [type] (one of [BLOCK_DOCUMENT_TYPES]) in the Documents/Jarvis folder, pictures fetched
 * and embedded, under a name from [baseName] that does not overwrite another file. Throws when it cannot be written.
 */
internal suspend fun exportDocument(context: Context, client: OkHttpClient?, type: String, title: String, markdown: String, baseName: String): ExportedDocument {
    val blocks = parseBlocks(markdown)
    val needsPictures = type in setOf("pdf", "docx", "pptx", "html")
    val images = if (needsPictures) loadDocumentImages(context, blocks, client) else emptyMap()
    val missing = if (needsPictures) blocks.filterIsInstance<Block.Image>().count { it.source !in images } else 0
    val bytes: ByteArray = when (type) {
        "pdf" -> buildPdf(title, blocks, images)
        "docx" -> buildDocx(title, blocks, images)
        "pptx" -> buildPptx(title, blocks, images)
        "html" -> buildHtml(title, blocks, images).toByteArray(Charsets.UTF_8)
        "md" -> ((if (title.isNotEmpty() && !markdown.trimStart().startsWith("#")) "# $title\n\n" else "") + markdown.trim() + "\n").toByteArray(Charsets.UTF_8)
        "txt" -> buildPlainText(title, blocks).toByteArray(Charsets.UTF_8)
        else -> throw IllegalArgumentException("type $type")
    }
    val file = DocumentStore.uniqueFile(DocumentStore.folder(context), safeFileName(baseName.ifBlank { title }), type)
    file.writeBytes(bytes)
    return ExportedDocument(file, bytes.size, missing)
}
