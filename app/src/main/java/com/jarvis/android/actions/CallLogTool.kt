package com.jarvis.android.actions

import android.content.Intent
import android.net.Uri
import com.jarvis.android.JarvisContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.ZoneId
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.intArg
import com.jarvis.android.tool.stringArg
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.people.CallKind
import com.jarvis.android.people.describeGroups
import com.jarvis.android.people.groupCallers
import com.jarvis.android.people.hasCallLogPermission
import com.jarvis.android.people.readCallLog

/*
 * "Qui m'a appelé ?", "j'ai des appels manqués ?", then "rappelle-le". Read from the phone's call log (READ_CALL_LOG, asked
 * in the Contacts card). As with call_contact, numbers are never handed to the model: a caller is their contact name, or
 * "numéro inconnu finissant par 42"; calling back goes through the numbered list, and only opens the dialer.
 */

object CallLogTool : Tool {
    override val name = "call_log"
    override val description =
        "Le journal d'appels du téléphone. missed (défaut) : les appels manqués ; recent : tous les derniers appels (reçus, passés, manqués). " +
            "days : sur combien de jours (1 par défaut pour missed, 3 pour recent). callback : rappeler la personne numéro choice de la liste que " +
            "vous venez de donner — ouvre le numéroteur, l'utilisateur appuie sur appeler. Les numéros ne vous sont jamais communiqués."
    override val parameters = objectSchema {
        string("action", "'missed' (défaut), 'recent' ou 'callback'.")
        integer("days", "Sur combien de jours regarder, 1 à 30.")
        integer("choice", "Pour callback : le numéro de la ligne dans la dernière liste (à partir de 1).")
        string("list", "Pour callback : 'missed' ou 'recent', la liste d'où vient choice (missed par défaut).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val context = ctx.appContext
        if (!hasCallLogPermission(context)) {
            return@withContext "L'accès au journal d'appels n'est pas autorisé : l'utilisateur peut l'autoriser dans Réglages de Jarvis > Contacts (appels et SMS)."
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val action = args.stringArg("action").trim().lowercase().ifEmpty { "missed" }
        val listKind = if (action == "callback") args.stringArg("list").trim().lowercase().ifEmpty { "missed" } else action
        val days = args.intArg("days", if (listKind == "recent") 3 else 1).coerceIn(1, 30)
        val since = today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val entries = readCallLog(context, since)
        val groups = groupCallers(if (listKind == "recent") entries else entries.filter { it.kind == CallKind.MISSED || it.kind == CallKind.REJECTED })
        val period = if (days == 1) "aujourd'hui" else "ces $days derniers jours"
        when (action) {
            "callback" -> {
                val i = args.intArg("choice", 0)
                val g = groups.getOrNull(i - 1) ?: return@withContext "Numéro de choix invalide : redemandez la liste des appels."
                if (g.number.filter { it.isDigit() }.length < 3) return@withContext "Impossible de rappeler ${g.label} : le numéro est masqué."
                try {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", g.number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Numéroteur ouvert pour ${g.label}. L'appel ne part pas tout seul : l'utilisateur appuie sur appeler." +
                        com.jarvis.android.people.PersonReminders.noteFor(context, g.number)
                } catch (_: Exception) {
                    "Impossible d'ouvrir l'application téléphone."
                }
            }
            "recent" -> if (groups.isEmpty()) "Aucun appel $period." else "Derniers appels $period :\n" + describeGroups(groups.take(12), today, zone)
            else -> if (groups.isEmpty()) "Aucun appel manqué $period." else
                groups.sumOf { it.count }.let { n -> if (n == 1) "1 appel manqué" else "$n appels manqués" } + " $period :\n" + describeGroups(groups.take(12), today, zone) +
                    "\n(Pour rappeler : callback avec choice = le numéro de la ligne.)"
        }
    }
}
