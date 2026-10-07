package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.docs.BLOCK_DOCUMENT_TYPES
import com.jarvis.android.docs.DocumentDrafts
import com.jarvis.android.docs.DocumentStore
import com.jarvis.android.docs.buildXlsx
import com.jarvis.android.docs.exportDocument
import com.jarvis.android.docs.parseRows
import com.jarvis.android.docs.safeFileName
import com.jarvis.android.docs.toCsv
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.stringArg
import com.jarvis.android.tool.objectSchema

internal const val MAX_DOCUMENT_CHARS = 60_000
internal val DOCUMENT_TYPES = listOf("pdf", "docx", "xlsx", "pptx", "html", "csv", "md", "txt")

/** What the tool would write: the type, the title and the content, checked. Null type means the request cannot be served. */
internal fun documentType(raw: String): String? = raw.trim().lowercase(Locale.ROOT).removePrefix(".").takeIf { it in DOCUMENT_TYPES }

/** Writes a document the user asked for (a report, a letter, a table) and offers to open or share it. */
object DocumentTool : Tool {
    override val name = "create_document"
    override val description =
        "Créer un document et le mettre à disposition de l’utilisateur (notification avec Ouvrir et Partager) : PDF, Word (docx), Excel (xlsx), PowerPoint (pptx), page web (html), CSV, Markdown ou texte. " +
            "Pour pdf, docx, pptx, html, md et txt, fournir le contenu en Markdown léger : titres avec #, ##, ###, listes avec - ou 1., cases à cocher - [ ] et - [x], tableaux avec |, citations avec >, code entre ```, séparateur ---, [saut de page], paragraphes séparés par une ligne vide. " +
            "Images (intégrées au PDF, Word, PowerPoint et HTML) : une ligne ![légende](source), ou ![légende|50](source) pour une demi-largeur ; source = galerie:dernière (dernière photo prise), galerie:3 (3e plus récente), galerie:2026-10-05 ou galerie:2026-10-05#2 (photos d’un jour), une adresse https:// d’image, ou un chemin/content:// donné par un autre outil. " +
            "Pour pptx (présentation), le titre du document devient la diapositive de titre (le premier paragraphe en est le sous-titre) ; chaque titre # ou ## ouvre une diapositive, avec des listes à puces courtes (5 à 7 puces d’une ligne) ; une diapositive trop chargée se poursuit sur la suivante. Pour xlsx et csv, fournir un tableau : lignes de cellules séparées par | ou par des points-virgules, ou du JSON [[\"a\",\"b\"],[1,2]] ; la première ligne est l’en-tête, une cellule =SOMME(A1:A3) devient une formule (écrire les noms de fonctions Excel en anglais : =SUM). " +
            "Rédige le contenu complet toi-même avant l’appel. Ne l’utilise que si l’utilisateur demande un document, un fichier ou un export. " +
            "Le document (sauf xlsx et csv) reste ensuite modifiable dans l’éditeur Documents de Jarvis et avec edit_document."
    override val parameters = objectSchema(required = listOf("type", "content")) {
        string("type", "pdf, docx, xlsx, pptx, html, csv, md ou txt.")
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

        if (type in BLOCK_DOCUMENT_TYPES) {
            val exported = try {
                exportDocument(context, ctx.http, type, title, content, base)
            } catch (e: Exception) {
                return "Impossible de créer le document : ${e.message}"
            }
            val draft = runCatching { DocumentDrafts.create(context, title.ifEmpty { exported.file.nameWithoutExtension }, content) }.getOrNull()
            return announce(context, exported.file, title, exported.bytes) + missingNote(exported.missingImages) +
                (if (draft != null) " Il reste modifiable dans l’éditeur Documents de Jarvis (brouillon « ${draft.title} »)." else "")
        }
        val bytes: ByteArray = try {
            when (type) {
                "xlsx" -> buildXlsx(title, parseRows(content).also { if (it.isEmpty()) return "Le tableau est vide." })
                else -> ("\uFEFF" + toCsv(parseRows(content).also { if (it.isEmpty()) return "Le tableau est vide." })).toByteArray(Charsets.UTF_8)
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
        return announce(context, file, title, bytes.size)
    }
}

/** Offers [file] in a notification (open, share) and says so. */
internal fun announce(context: android.content.Context, file: java.io.File, title: String, size: Int): String {
    val label = title.ifEmpty { file.nameWithoutExtension }
    DocumentStore.notifyReady(context, file, trf("Document prêt : {0}", label), trf("{0} ({1} Ko). Touchez pour l’ouvrir, ou partagez-le.", file.name, (size / 1000).coerceAtLeast(1)), 7300 + (file.name.hashCode() and 0xFF))
    return "Document « ${file.name} » créé (${size / 1000 + 1} Ko). Une notification permet de l’ouvrir ou de le partager ; il est enregistré dans le dossier Documents/Jarvis de l’application."
}

internal fun missingNote(missing: Int): String = when {
    missing <= 0 -> ""
    missing == 1 -> " Une image n’a pas pu être trouvée (source introuvable ou accès aux photos refusé) : sa place est marquée dans le document."
    else -> " $missing images n’ont pas pu être trouvées (source introuvable ou accès aux photos refusé) : leur place est marquée dans le document."
}
