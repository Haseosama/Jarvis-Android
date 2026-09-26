package com.jarvis.android.actions

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.jarvis.android.JarvisContainer
import com.jarvis.android.notifications.JarvisNotificationListener
import com.jarvis.android.people.PersonReminder
import com.jarvis.android.people.PersonReminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** "La prochaine fois que Paul m'appelle, rappelle-moi de…": reminders tied to a contact (see people/PersonReminders.kt). */
object PersonReminderTool : Tool {
    override val name = "person_reminder"
    override val description =
        "Rappel lié à une personne : « la prochaine fois que Paul m'appelle, rappelle-moi de lui parler du week-end », « quand Maman m'écrit, " +
            "rappelle-moi de… ». Il s'affiche quand ce contact appelle, quand un message de lui arrive, ou quand vous l'appelez ou lui écrivez " +
            "par Jarvis, et reste jusqu'à « Fait ». Actions : add (name + text, text à la 2e personne sans « rappelle-moi de », ex. " +
            "« lui parler du week-end »), list, done (choice = numéro dans list, ou name). S'il y a plusieurs contacts, l'outil les liste : " +
            "demandez lequel puis rappelez avec choice."
    override val parameters = objectSchema {
        string("action", "'add' (défaut), 'list' ou 'done'.")
        string("name", "Nom du contact.")
        string("text", "Pour add : ce qu'il faut rappeler, ex. « lui rendre son livre ».")
        integer("choice", "Numéro du choix (plusieurs contacts pour add, ou ligne de list pour done).")
    }

    private fun lines(items: List<PersonReminder>) = items.mapIndexed { i, r -> "${i + 1}) ${r.name} : ${r.text}" }.joinToString("\n")

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val store = ctx.personReminderStore
        val context = ctx.appContext
        when (args.stringArg("action").trim().lowercase().ifEmpty { "add" }) {
            "list" -> {
                val items = store.all()
                return if (items.isEmpty()) "Aucun rappel lié à une personne." else "Rappels liés à une personne :\n" + lines(items)
            }
            "done", "remove", "delete" -> {
                val items = store.all()
                if (items.isEmpty()) return "Aucun rappel lié à une personne."
                val i = args.intArg("choice", 0)
                val name = args.stringArg("name").trim()
                val target = when {
                    i in 1..items.size -> listOf(items[i - 1])
                    name.isNotEmpty() -> com.jarvis.android.people.remindersForSender(items, name).ifEmpty {
                        items.filter { it.name.contains(name, ignoreCase = true) }
                    }
                    else -> emptyList()
                }
                if (target.size != 1) return "Lequel ?\n" + lines(items) + "\n(Rappelez avec done et choice.)"
                store.remove(target[0].id)
                return "Rappel pour ${target[0].name} effacé."
            }
            "add" -> Unit
            else -> return "Action inconnue : add, list ou done."
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "L'accès aux contacts n'est pas autorisé : il se donne dans les réglages de Jarvis (carte Contacts)."
        }
        val query = args.stringArg("name").trim()
        val text = args.stringArg("text").trim().take(300)
        if (query.isEmpty() || text.isEmpty()) return "Il faut le nom du contact et ce qu'il faut rappeler."
        val choices = withContext(Dispatchers.IO) { matchContacts(ContactTool.readContacts(context), query) }
        if (choices.isEmpty()) return "Aucun contact ne correspond à « ${query.take(40)} »."
        val people = choices.map { it.name }.distinct()
        val person = if (people.size == 1) people[0] else {
            val i = args.intArg("choice", 0)
            if (i in 1..people.size) people[i - 1]
            else return "Plusieurs contacts : " + people.take(8).mapIndexed { n, p -> "${n + 1}) $p" }.joinToString(" ; ") +
                ". Demandez lequel, puis rappelez avec le même nom, le texte et choice."
        }
        store.add(person, choices.filter { it.name == person }.map { it.number }, text)
        val missing = buildList {
            if (!PersonReminders.canWatchCalls(context)) add("ses appels (autorisez « Rappels pendant les appels », carte Contacts des réglages)")
            if (!JarvisNotificationListener.isEnabled(context)) add("ses messages (il faut l'accès aux notifications)")
        }
        return "C'est noté : quand $person vous appellera ou vous écrira, je vous rappellerai « $text »." +
            if (missing.isEmpty()) "" else " Pour l'instant, je ne vois pas " + missing.joinToString(" ni ") + "."
    }
}
