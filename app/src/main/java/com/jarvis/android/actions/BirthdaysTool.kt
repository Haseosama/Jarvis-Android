package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.calendar.birthdaysOf
import com.jarvis.android.calendar.describeBirthday
import com.jarvis.android.calendar.hasContactsPermission
import com.jarvis.android.calendar.readBirthdays
import com.jarvis.android.calendar.upcomingBirthdays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/** Birthdays saved in the phone's contacts. */
object BirthdaysTool : Tool {
    override val name = "birthdays"
    override val description =
        "Les anniversaires enregistrés dans les contacts du téléphone. upcoming (défaut) : ceux des prochains jours (days, 30 par défaut) ; " +
            "when : « c'est quand l'anniversaire de Paul ? » (name). Pour souhaiter un anniversaire, proposez d'écrire un message avec send_message."
    override val parameters = objectSchema {
        string("action", "'upcoming' (défaut) ou 'when'.")
        string("name", "Pour when : le nom du contact.")
        integer("days", "Pour upcoming : combien de jours à venir (1 à 366, défaut 30).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        if (!hasContactsPermission(ctx.appContext)) {
            return@withContext "L'accès aux contacts n'est pas autorisé : l'utilisateur peut l'autoriser dans Réglages de Jarvis > Contacts."
        }
        val all = readBirthdays(ctx.appContext)
        if (all.isEmpty()) return@withContext "Aucun anniversaire enregistré dans les contacts du téléphone (champ « Anniversaire » de la fiche contact)."
        val today = LocalDate.now()
        when (args.stringArg("action").trim().lowercase().ifEmpty { "upcoming" }) {
            "when" -> {
                val name = args.stringArg("name").trim().ifEmpty { return@withContext "Indiquez le nom du contact." }
                val found = birthdaysOf(all, name)
                if (found.isEmpty()) "Pas d'anniversaire enregistré pour « $name » dans les contacts."
                else found.take(5).joinToString("\n") { describeBirthday(it, today) }
            }
            else -> {
                val days = args.intArg("days", 30).coerceIn(1, 366)
                val soon = upcomingBirthdays(all, today, days)
                if (soon.isEmpty()) "Aucun anniversaire dans les $days prochains jours."
                else "Anniversaires des $days prochains jours :\n" + soon.take(20).joinToString("\n") { describeBirthday(it, today) }
            }
        }
    }
}
