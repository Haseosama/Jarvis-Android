package com.jarvis.android.actions

import android.net.Uri
import com.jarvis.android.JarvisContainer
import com.jarvis.android.filemanager.DocumentTree
import com.jarvis.android.filemanager.FileManager
import com.jarvis.android.filemanager.FileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal const val ERROR_NO_WORK_FOLDER =
    "Aucun dossier de travail choisi. L’utilisateur doit en choisir un dans Paramètres > Dossier de travail : je ne peux agir que dedans."

/**
 * File management (Android port of Mark-LIII's `file_controller`), limited to the one folder the
 * user granted. Deleting moves to a trash folder inside it; every change can be undone with `undo`.
 * Deleting, overwriting and organizing ask for the user's confirmation first.
 */
object FileManagerTool : Tool {
    override val name = "file_manager"
    override val description =
        "Gérer les fichiers du dossier de travail choisi par l’utilisateur (rien en dehors). Actions : list, info, read, find, largest, usage, " +
            "create_file, create_folder, write, rename, move, copy, delete (corbeille, confirmation demandée), organize (range par type, confirmation demandée). " +
            "Les chemins sont relatifs au dossier de travail (« Documents/notes.txt », vide pour la racine). Tout changement peut être annulé avec undo."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "list, info, read, find, largest, usage, create_file, create_folder, write, rename, move, copy, delete ou organize.")
        string("path", "Fichier ou dossier visé, relatif au dossier de travail.")
        string("destination", "Pour move/copy : dossier de destination.")
        string("name", "Pour rename : le nouveau nom.")
        string("content", "Pour create_file/write : le texte.")
        string("query", "Pour find : une partie du nom.")
        string("extension", "Pour find : l’extension (pdf, jpg…).")
        integer("count", "Pour largest : nombre de fichiers (défaut 10).")
        string("append", "Pour write : 'true' pour ajouter à la fin au lieu de remplacer.")
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val uri = ctx.configStore.workFolder.first().takeIf { it.isNotBlank() } ?: return@withContext ERROR_NO_WORK_FOLDER
        val manager = FileManager(DocumentTree(ctx.appContext, Uri.parse(uri)))
        val path = args.text("path")
        val result: FileResult = when (val action = args.text("action").trim().lowercase()) {
            "list" -> manager.list(path)
            "info" -> manager.info(path)
            "read" -> manager.read(path)
            "find" -> manager.find(args.text("query"), args.text("extension"), path)
            "largest" -> manager.largest(path, args.intArg("count", 10))
            "usage" -> manager.usage(path)
            "create_file" -> manager.createFile(path, args.text("content"))
            "create_folder" -> manager.createFolder(path)
            "rename" -> manager.rename(path, args.text("name"))
            "move" -> manager.move(path, args.text("destination"))
            "copy" -> manager.copy(path, args.text("destination"))
            "write" -> {
                if (!confirm(ctx, "Écrire dans un fichier", "Modifier ou remplacer « ${path.take(60)} » ?")) return@withContext "Modification refusée : rien n’a été changé."
                manager.write(path, args.text("content"), args["append"]?.jsonPrimitive?.booleanOrNull == true || args.text("append").lowercase() == "true")
            }
            "delete" -> {
                if (!confirm(ctx, "Supprimer", "Mettre « ${path.take(60)} » à la corbeille ?")) return@withContext "Suppression refusée : rien n’a été touché."
                manager.delete(path)
            }
            "organize" -> {
                val plan = manager.organizePlan(path) ?: return@withContext "Dossier introuvable."
                if (!confirm(ctx, "Ranger des fichiers", "Ranger ${plan.size} fichier(s) de « ${path.take(60).ifEmpty { "/" }} » par type ?")) return@withContext "Rangement refusé : rien n’a été déplacé."
                manager.organize(path)
            }
            else -> return@withContext "Action inconnue : $action."
        }
        result.undo?.let { ctx.undoManager.push(it) }
        result.message
    }

    private suspend fun confirm(ctx: JarvisContainer, label: String, detail: String): Boolean {
        if (ctx.skipConfirmations) return true
        return try {
            withTimeout(60_000L) { ctx.confirmManager.request(label, detail) }
        } catch (_: TimeoutCancellationException) {
            false
        }
    }
}
