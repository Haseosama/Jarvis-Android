package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.routines.dayNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Create, list or delete tasks Jarvis does by himself at a fixed time every day. */
object RoutineTool : Tool {
    override val name = "routine"
    override val description =
        "Gérer les routines : une tâche que Jarvis exécute tout seul à heure fixe (par exemple « chaque matin à 7 h, donne la météo »). " +
            "Le résultat arrive en notification, à quelques minutes près. Actions : create (défaut), list, delete."
    override val parameters = objectSchema {
        string("action", "'create' (défaut), 'list' ou 'delete'.")
        string("time", "Heure locale HH:mm, obligatoire pour create.")
        string("task", "Ce que Jarvis doit faire, en une ou deux phrases, obligatoire pour create.")
        string("days", "Jours facultatifs pour create, numéros séparés par des virgules : 1 lundi … 7 dimanche. Vide = tous les jours.")
        integer("id", "Numéro de la routine, obligatoire pour delete.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = safely {
        val store = ctx.routines
        when (args["action"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().lowercase().ifEmpty { "create" }) {
            "list" -> {
                val all = store.list()
                if (all.isEmpty()) "Aucune routine."
                else all.joinToString("\n") { "#${it.id} — ${it.time}, ${dayNames(it.days)} — ${it.task}" }
            }
            "delete" -> {
                val id = args["id"]?.jsonPrimitive?.intOrNull ?: return@safely "Indiquez le numéro de la routine."
                if (store.remove(id)) "Routine $id supprimée." else "Routine $id introuvable."
            }
            else -> {
                val days = args["days"]?.jsonPrimitive?.contentOrNull.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
                val r = store.add(args["time"]?.jsonPrimitive?.contentOrNull.orEmpty(), args["task"]?.jsonPrimitive?.contentOrNull.orEmpty(), days)
                "Routine ${r.id} créée : ${r.time}, ${dayNames(r.days)} — ${r.task}. Le résultat s’affichera en notification."
            }
        }
    }

    private suspend fun safely(block: suspend () -> String): String = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        e.message ?: "Paramètres de la routine invalides."
    } catch (_: Exception) {
        "La routine n'a pas pu être enregistrée."
    }
}
