package com.jarvis.android.actions

import android.content.Intent
import android.provider.AlarmClock
import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.LocalTime

/** A validated alarm request. [calendarDays] uses java.util.Calendar numbers (Sunday 1 … Saturday 7), as AlarmClock wants. */
internal data class AlarmRequest(val hour: Int, val minute: Int, val label: String, val calendarDays: List<Int>)

/** Parses "HH:mm" and ISO weekday numbers (1 Monday … 7 Sunday, comma separated). Returns the request or an error message. */
internal fun parseAlarm(time: String, label: String, days: String): Pair<AlarmRequest?, String?> {
    val match = Regex("^(\\d{1,2})[:hH](\\d{2})$").matchEntire(time.trim())
        ?: return null to "Heure invalide : utilisez HH:mm, par exemple 07:30."
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    if (hour !in 0..23 || minute !in 0..59) return null to "Heure invalide : utilisez HH:mm, par exemple 07:30."
    val iso = days.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: return null to "Jours invalides : 1 (lundi) à 7 (dimanche)." }
    if (iso.any { it !in 1..7 }) return null to "Jours invalides : 1 (lundi) à 7 (dimanche)."
    val cleanLabel = label.filter { !it.isISOControl() }.trim().take(80)
    return AlarmRequest(hour, minute, cleanLabel, iso.distinct().map { it % 7 + 1 }) to null
}

/** Minutes from [now] until the next time the alarm rings, ignoring the day filter. */
internal fun minutesUntil(now: LocalDateTime, hour: Int, minute: Int): Long {
    var next = now.toLocalDate().atTime(LocalTime.of(hour, minute))
    if (!next.isAfter(now)) next = next.plusDays(1)
    return java.time.Duration.between(now, next).toMinutes()
}

/** Sets an alarm in the phone's own clock app (no special permission beyond the normal SET_ALARM one). */
object AlarmTool : Tool {
    override val name = "alarm"
    override val description =
        "Régler un réveil dans l'application Horloge du téléphone (par exemple « réveille-moi à 7 h »). Actions : set (défaut) ou show (ouvre la liste des alarmes, " +
            "pour les modifier ou les supprimer : Jarvis ne peut pas supprimer une alarme). Pour un simple rappel avec message, préférez l'outil reminder."
    override val parameters = objectSchema {
        string("action", "'set' (défaut) ou 'show'.")
        string("time", "Heure locale HH:mm, obligatoire pour set.")
        string("label", "Nom de l'alarme, facultatif.")
        string("days", "Jours de répétition facultatifs, numéros séparés par des virgules : 1 lundi … 7 dimanche. Vide = une seule fois.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val context = ctx.appContext
        if (args.stringArg("action").trim().lowercase() == "show") {
            return try {
                context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Liste des alarmes ouverte."
            } catch (_: Exception) {
                "Impossible d'ouvrir l'application Horloge."
            }
        }
        val (request, error) = parseAlarm(args.stringArg("time"), args.stringArg("label"), args.stringArg("days"))
        if (request == null) return error ?: "Alarme invalide."
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, request.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, request.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (request.label.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, request.label)
        if (request.calendarDays.isNotEmpty()) intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(request.calendarDays))
        return try {
            context.startActivity(intent)
            val minutes = minutesUntil(LocalDateTime.now(), request.hour, request.minute)
            val when_ = "%02d:%02d".format(request.hour, request.minute)
            "Alarme demandée à l'application Horloge pour $when_ (dans ${minutes / 60} h ${minutes % 60} min). " +
                "Android ne confirme pas : dites à l'utilisateur de vérifier dans l'Horloge si c'est important."
        } catch (_: SecurityException) {
            "Android a refusé de régler l'alarme (autorisation manquante)."
        } catch (_: Exception) {
            "Aucune application Horloge n'a pu régler l'alarme."
        }
    }
}
