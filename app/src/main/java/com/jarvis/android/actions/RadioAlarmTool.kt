package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.wakeup.RadioAlarmSpec
import com.jarvis.android.wakeup.RadioAlarms
import com.jarvis.android.wakeup.daysWords
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.stringArg
import com.jarvis.android.tool.objectSchema

/** Waking up to a radio station (see wakeup/RadioAlarm.kt): one radio alarm, set, shown or removed. */
object RadioAlarmTool : Tool {
    override val name = "radio_alarm"
    override val description =
        "Réveil en radio : « réveille-moi avec FIP à 7 h », « en semaine à 6 h 45 avec France Inter ». action « set » : time (HH:MM), " +
            "query (la station), days (jours ISO séparés par des virgules : 1 lundi … 7 dimanche ; vide = une seule fois ; « 1,2,3,4,5 » " +
            "en semaine) ; « show » : le réveil radio réglé ; « cancel » : le retirer. La radio démarre doucement puis monte ; si la station ne " +
            "répond pas, la sonnerie du téléphone prend le relais. Un seul réveil radio à la fois (un nouveau remplace l’ancien)."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "set, show ou cancel.")
        string("time", "Pour set : l’heure, HH:MM.")
        string("query", "Pour set : la station (FIP, France Inter, Nostalgie…).")
        string("days", "Pour set : jours ISO séparés par des virgules (1 lundi … 7 dimanche), vide pour une seule fois.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val context = ctx.appContext
        return when (args.stringArg("action").trim().lowercase()) {
            "cancel", "remove", "annuler" -> if (RadioAlarms.current(context) != null) { RadioAlarms.cancel(context); "Réveil radio retiré." } else "Aucun réveil radio n’est réglé."
            "show", "list" -> RadioAlarms.current(context)?.let { s -> "Réveil radio : ${"%02d:%02d".format(s.hour, s.minute)}, ${daysWords(s.days)}, avec « ${s.station} »." }
                ?: "Aucun réveil radio n’est réglé."
            else -> {
                val (request, error) = parseAlarm(args.stringArg("time"), "", args.stringArg("days"))
                if (request == null) return error ?: "Heure invalide."
                val days = args.stringArg("days").split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.distinct().sorted()
                val query = args.stringArg("query").trim().take(80)
                if (query.isEmpty()) return "Dites avec quelle station vous réveiller."
                val station = try {
                    com.jarvis.android.radio.findStations(ctx.http, query, "FR").firstOrNull()
                } catch (_: IOException) {
                    return "L’annuaire des radios ne répond pas : réessayez dans un moment."
                } ?: return "Aucune radio trouvée pour « $query »."
                val (at, approximate) = RadioAlarms.set(context, RadioAlarmSpec(request.hour, request.minute, days, station.name, station.stream))
                val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
                "Réveil radio réglé à ${"%02d:%02d".format(request.hour, request.minute)} (${daysWords(days)} ; prochain le " +
                    "${com.jarvis.android.photos.dayWords(day, false)}) avec « ${station.name} » : la radio démarre doucement puis monte." +
                    (if (approximate) " Attention : Android ne permet à Jarvis que des alarmes approximatives ; pour qu’il sonne à l’heure, autorisez " +
                        "« Alarmes et rappels » pour Jarvis dans les réglages du téléphone." else "") +
                    " Dites-le en une phrase courte."
            }
        }
    }
}
