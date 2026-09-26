package com.jarvis.android.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.jarvis.android.JarvisContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * "Qui m'a appelé ?", "j'ai des appels manqués ?", then "rappelle-le". Read from the phone's call log (READ_CALL_LOG, asked
 * in the Contacts card). As with call_contact, numbers are never handed to the model: a caller is their contact name, or
 * "numéro inconnu finissant par 42"; calling back goes through the numbered list, and only opens the dialer.
 */

internal enum class CallKind { MISSED, INCOMING, OUTGOING, REJECTED, OTHER }

internal data class CallEntry(val name: String?, val number: String, val kind: CallKind, val at: Long, val durationSec: Long)

internal fun callKindOf(type: Int): CallKind = when (type) {
    CallLog.Calls.MISSED_TYPE -> CallKind.MISSED
    CallLog.Calls.INCOMING_TYPE -> CallKind.INCOMING
    CallLog.Calls.OUTGOING_TYPE -> CallKind.OUTGOING
    CallLog.Calls.REJECTED_TYPE -> CallKind.REJECTED
    else -> CallKind.OTHER
}

/** Who called, without the number: the contact name, or the last two digits of an unknown number. */
internal fun callerLabel(e: CallEntry): String {
    e.name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val digits = e.number.filter { it.isDigit() }
    return if (digits.length >= 2) "numéro inconnu finissant par ${digits.takeLast(2)}" else "numéro masqué"
}

/** "aujourd'hui à 9 h 03", "hier à 18 h 12", "le 21/09 à 14 h" */
internal fun whenLabel(at: Long, today: LocalDate, zone: ZoneId): String {
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val hm = if (t.minute == 0) "${t.hour} h" else "${t.hour} h ${t.minute.toString().padStart(2, '0')}"
    return when (t.toLocalDate()) {
        today -> "aujourd'hui à $hm"
        today.minusDays(1) -> "hier à $hm"
        else -> "le ${t.dayOfMonth}/${t.monthValue.toString().padStart(2, '0')} à $hm"
    }
}

/** One caller per line, with how many times and when last: repeated calls from the same person are grouped, newest first. */
internal data class CallerGroup(val label: String, val number: String, val count: Int, val lastAt: Long)

internal fun groupCallers(entries: List<CallEntry>): List<CallerGroup> =
    entries.groupBy { it.number.filter { c -> c.isDigit() }.takeLast(9).ifEmpty { callerLabel(it) } }
        .map { (_, list) -> val last = list.maxBy { it.at }; CallerGroup(callerLabel(last), last.number, list.size, last.at) }
        .sortedByDescending { it.lastAt }

internal fun describeGroups(groups: List<CallerGroup>, today: LocalDate, zone: ZoneId): String =
    groups.mapIndexed { i, g ->
        "${i + 1}) ${g.label}" + (if (g.count > 1) " (${g.count} fois)" else "") + ", ${whenLabel(g.lastAt, today, zone)}"
    }.joinToString("\n")

internal fun hasCallLogPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

internal fun readCallLog(context: Context, sinceMs: Long, limit: Int = 300): List<CallEntry> {
    if (!hasCallLogPermission(context)) return emptyList()
    return try {
        val out = mutableListOf<CallEntry>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            "${CallLog.Calls.DATE} >= ?", arrayOf(sinceMs.toString()), "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out += CallEntry(c.getString(0), c.getString(1).orEmpty(), callKindOf(c.getInt(2)), c.getLong(3), c.getLong(4))
            }
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

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
                    "Numéroteur ouvert pour ${g.label}. L'appel ne part pas tout seul : l'utilisateur appuie sur appeler."
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
