package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject

/** A shopping list, a to-do list, or any other list the user names — persists between sessions, works offline too. */
object TaskListTool : Tool {
    override val name = "task_list"
    override val description =
        "Gérer une liste qui reste en mémoire d'une session à l'autre : une liste de courses, une liste de tâches, ou toute autre liste " +
            "que l'utilisateur nomme. Actions : add (ajoute un élément, défaut), done (coche un élément comme fait ou acheté), " +
            "remove (le supprime), list (lit la liste), clear (la vide), clear_done (retire seulement les éléments déjà cochés). " +
            "Plusieurs listes distinctes coexistent par leur nom (« courses », « tâches »… ) ; sans nom, une liste par défaut est utilisée."
    override val parameters = objectSchema {
        string("action", "'add' (défaut), 'done', 'remove', 'list', 'clear' ou 'clear_done'.")
        string("liste", "Nom de la liste, par exemple « courses » ou « tâches » ; facultatif.")
        string("item", "Texte de l'élément, pour add/done/remove.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val store = ctx.taskListStore
        val listName = args.stringArg("liste").trim()
        // "la liste" for the default one, "la liste courses" for a named one — never the awkward "la liste liste".
        val onList = if (listName.isEmpty()) "la liste" else "la liste $listName"
        val item = args.stringArg("item").trim()
        return when (args.stringArg("action").trim().lowercase().ifEmpty { "add" }) {
            "add" -> {
                if (item.isEmpty()) return "Indiquez ce qu'il faut ajouter."
                if (store.add(listName, item)) "« $item » ajouté à $onList."
                else "$onList est pleine, ou il y a déjà trop de listes différentes."
            }
            "done" -> {
                if (item.isEmpty()) return "Indiquez quel élément cocher."
                if (store.setDone(listName, item, true)) "« $item » coché sur $onList."
                else "Rien qui ressemble à « $item » sur $onList."
            }
            "undone" -> {
                if (item.isEmpty()) return "Indiquez quel élément décocher."
                if (store.setDone(listName, item, false)) "« $item » décoché sur $onList."
                else "Rien qui ressemble à « $item » sur $onList."
            }
            "remove" -> {
                if (item.isEmpty()) return "Indiquez quel élément supprimer."
                if (store.remove(listName, item)) "« $item » retiré de $onList."
                else "Rien qui ressemble à « $item » sur $onList."
            }
            "list" -> {
                val items = store.items(listName)
                if (items.isEmpty()) return "$onList est vide."
                items.joinToString("\n") { (if (it.done) "☑ " else "☐ ") + it.text }
            }
            "clear" -> {
                store.clear(listName)
                "${onList.replaceFirstChar { it.uppercase() }} vidée."
            }
            "clear_done" -> {
                val n = store.clearDone(listName)
                if (n == 0) "Rien de coché à retirer sur $onList." else "$n élément(s) coché(s) retiré(s) de $onList."
            }
            else -> "Action inconnue : utilisez add, done, undone, remove, list, clear ou clear_done."
        }
    }
}
