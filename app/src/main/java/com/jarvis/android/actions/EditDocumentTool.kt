package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.docs.BLOCK_DOCUMENT_TYPES
import com.jarvis.android.docs.DocumentDrafts
import com.jarvis.android.docs.exportDocument
import com.jarvis.android.docs.findDraft
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.text.DateFormat
import java.util.Date

/** Reads and changes the documents of the editor (drafts) by voice: add a section or a picture, rewrite, export again. */
object EditDocumentTool : Tool {
    override val name = "edit_document"
    override val description =
        "Lire ou modifier un document de l’éditeur Documents de Jarvis (les brouillons, y compris ceux créés par create_document) : " +
            "action lister (les documents), lire (le contenu en Markdown léger), ajouter (du contenu à la fin, par exemple une section ou une image), " +
            "remplacer (tout le contenu, après l’avoir lu et réécrit), creer (un nouveau brouillon vide ou avec du contenu, sans fichier), " +
            "exporter (vers pdf, docx, pptx, html, md ou txt, avec notification Ouvrir/Partager). " +
            "Contenu au même format que create_document, images comprises : ![légende](galerie:dernière), ![légende|50](source)… " +
            "Le document est désigné par son titre (le plus récent si aucun)."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "lister, lire, ajouter, remplacer, creer ou exporter.")
        string("document", "Titre (ou partie du titre) du document visé ; pour creer, le titre du nouveau document.")
        string("content", "Pour ajouter, remplacer ou creer : le contenu en Markdown léger.")
        string("type", "Pour exporter : pdf, docx, pptx, html, md ou txt (pdf par défaut).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val context = ctx.appContext
        val drafts = DocumentDrafts.list(context)
        val query = args.stringArg("document").trim()
        val content = args.stringArg("content").trim()
        when (args.stringArg("action").trim().lowercase()) {
            "lister", "list" -> {
                if (drafts.isEmpty()) return@withContext "Aucun document dans l’éditeur pour l’instant."
                val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                "Documents (${drafts.size}) : " + drafts.take(30).joinToString(" ; ") { "« ${it.title.ifBlank { "Sans titre" }} » (modifié ${format.format(Date(it.updated))})" }
            }
            "creer", "créer", "create" -> {
                if (content.length > MAX_DOCUMENT_CHARS) return@withContext "Contenu trop long."
                val draft = DocumentDrafts.create(context, query.ifBlank { "Sans titre" }, content)
                "Document « ${draft.title} » créé dans l’éditeur Documents."
            }
            else -> {
                val draft = findDraft(drafts, query)
                    ?: return@withContext if (drafts.isEmpty()) "Aucun document dans l’éditeur pour l’instant." else "Aucun document ne s’appelle « $query ». Documents : " + drafts.take(10).joinToString(", ") { "« ${it.title} »" } + "."
                when (args.stringArg("action").trim().lowercase()) {
                    "lire", "read" -> "Document « ${draft.title} » :\n" + draft.markdown.ifBlank { "(vide)" }.take(MAX_DOCUMENT_CHARS)
                    "ajouter", "append" -> {
                        if (content.isEmpty()) return@withContext "Rien à ajouter : donnez le contenu."
                        val merged = listOf(draft.markdown.trimEnd(), content).filter { it.isNotEmpty() }.joinToString("\n\n")
                        if (merged.length > MAX_DOCUMENT_CHARS) return@withContext "Le document deviendrait trop long."
                        DocumentDrafts.save(context, draft.copy(markdown = merged))
                        "Ajouté à la fin de « ${draft.title} »."
                    }
                    "remplacer", "replace" -> {
                        if (content.isEmpty()) return@withContext "Le nouveau contenu est vide."
                        if (content.length > MAX_DOCUMENT_CHARS) return@withContext "Contenu trop long."
                        DocumentDrafts.save(context, draft.copy(markdown = content))
                        "Contenu de « ${draft.title} » remplacé."
                    }
                    "exporter", "export" -> {
                        val type = args.stringArg("type").trim().lowercase().removePrefix(".").ifEmpty { "pdf" }
                        if (type !in BLOCK_DOCUMENT_TYPES) return@withContext "Format inconnu : ${BLOCK_DOCUMENT_TYPES.joinToString(", ")}."
                        val exported = try {
                            exportDocument(context, ctx.http, type, draft.title, draft.markdown, draft.title)
                        } catch (e: Exception) {
                            return@withContext "Impossible d’exporter le document : ${e.message}"
                        }
                        announce(context, exported.file, draft.title, exported.bytes) + missingNote(exported.missingImages)
                    }
                    else -> "Action inconnue : lister, lire, ajouter, remplacer, creer ou exporter."
                }
            }
        }
    }
}
