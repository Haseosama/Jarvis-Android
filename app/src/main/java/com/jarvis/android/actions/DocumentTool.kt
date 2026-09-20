package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.docs.Block
import com.jarvis.android.docs.DocumentStore
import com.jarvis.android.docs.buildDocx
import com.jarvis.android.docs.buildPptx
import com.jarvis.android.docs.buildPdf
import com.jarvis.android.docs.buildXlsx
import com.jarvis.android.docs.parseBlocks
import com.jarvis.android.docs.parseRows
import com.jarvis.android.docs.safeFileName
import com.jarvis.android.docs.toCsv
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import kotlinx.serialization.json.JsonObject
import java.util.Locale

internal const val MAX_DOCUMENT_CHARS = 60_000
internal val DOCUMENT_TYPES = listOf("pdf", "docx", "xlsx", "pptx", "csv", "md", "txt")

/** What the tool would write: the type, the title and the content, checked. Null type means the request cannot be served. */
internal fun documentType(raw: String): String? = raw.trim().lowercase(Locale.ROOT).removePrefix(".").takeIf { it in DOCUMENT_TYPES }

/** Writes a document the user asked for (a report, a letter, a table) and offers to open or share it. */
object DocumentTool : Tool {
    override val name = "create_document"
    override val description =
        "Créer un document et le mettre à disposition de l’utilisateur (notification avec Ouvrir et Partager) : PDF, Word (docx), Excel (xlsx), PowerPoint (pptx), CSV, Markdown ou texte. " +
            "Pour pdf, docx, md et txt, fournir le contenu en Markdown léger : titres avec #, ##, ###, listes avec - ou 1., tableaux avec |, paragraphes séparés par une ligne vide. " +
            "Pour pptx (présentation), le titre du document devient la diapositive de titre (le premier paragraphe en est le sous-titre) ; chaque titre # ou ## ouvre une diapositive, avec des listes à puces courtes (5 à 7 puces d’une ligne) ; une diapositive trop chargée se poursuit sur la suivante. Pour xlsx et csv, fournir un tableau : lignes de cellules séparées par | ou par des points-virgules, ou du JSON [[\"a\",\"b\"],[1,2]] ; la première ligne est l’en-tête, une cellule =SOMME(A1:A3) devient une formule (écrire les noms de fonctions Excel en anglais : =SUM). " +
            "Rédige le contenu complet toi-même avant l’appel. Ne l’utilise que si l’utilisateur demande un document, un fichier ou un export."
    override val parameters = objectSchema(required = listOf("type", "content")) {
        string("type", "pdf, docx, xlsx, pptx, csv, md ou txt.")
        string("title", "Titre du document (aussi utilisé pour le nom du fichier).")
        string("content", "Le contenu complet, dans le format décrit ci-dessus.")
        string("filename", "Nom du fichier sans extension ; par défaut, dérivé du titre.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val type = documentType(args.stringArg("type")) ?: return "Type de document inconnu. Types possibles : ${DOCUMENT_TYPES.joinToString(", ")}."
        val content = args.stringArg("content").trim()
        if (content.isEmpty()) return "Le contenu du document est vide : rédigez-le d’abord."
        if (content.length > MAX_DOCUMENT_CHARS) return "Contenu trop long (${MAX_DOCUMENT_CHARS / 1000} 000 caractères au plus)."
        val title = args.stringArg("title").trim().take(120)
        val base = safeFileName(args.stringArg("filename").ifBlank { title }, fallback = "document")
        val context = ctx.appContext

        val bytes: ByteArray = try {
            when (type) {
                "pdf" -> buildPdf(title, parseBlocks(content))
                "docx" -> buildDocx(title, parseBlocks(content))
                "pptx" -> buildPptx(title, parseBlocks(content))
                "xlsx" -> buildXlsx(title, parseRows(content).also { if (it.isEmpty()) return "Le tableau est vide." })
                "csv" -> ("﻿" + toCsv(parseRows(content).also { if (it.isEmpty()) return "Le tableau est vide." })).toByteArray(Charsets.UTF_8)
                "md" -> ((if (title.isNotEmpty() && !content.startsWith("#")) "# $title\n\n" else "") + content).toByteArray(Charsets.UTF_8)
                else -> ((if (title.isNotEmpty()) "$title\n\n" else "") + content).toByteArray(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            return "Impossible de créer le document : ${e.message}"
        }
        val file = DocumentStore.uniqueFile(DocumentStore.folder(context), base, type)
        try {
            file.writeBytes(bytes)
        } catch (e: java.io.IOException) {
            return "Impossible d’enregistrer le fichier : ${e.message}"
        }
        val label = title.ifEmpty { file.nameWithoutExtension }
        DocumentStore.notifyReady(context, file, trf("Document prêt : {0}", label), trf("{0} ({1} Ko). Touchez pour l’ouvrir, ou partagez-le.", file.name, (bytes.size / 1000).coerceAtLeast(1)), 7300 + (file.name.hashCode() and 0xFF))
        return "Document « ${file.name} » créé (${bytes.size / 1000 + 1} Ko). Une notification permet de l’ouvrir ou de le partager ; il est enregistré dans le dossier Documents/Jarvis de l’application."
    }
}
