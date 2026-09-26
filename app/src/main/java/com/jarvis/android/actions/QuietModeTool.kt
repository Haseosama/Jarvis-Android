package com.jarvis.android.actions

import android.content.Intent
import android.provider.Settings
import com.jarvis.android.JarvisContainer
import com.jarvis.android.quiet.QuietMode
import com.jarvis.android.quiet.QuietStart
import com.jarvis.android.quiet.clockWords
import com.jarvis.android.quiet.quietUntil
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.ZoneId

/** "Je suis en réunion jusqu'à 15 h": Do Not Disturb until then, favourites only, a summary at the end (see quiet/QuietMode.kt). */
object QuietModeTool : Tool {
    override val name = "quiet_mode"
    override val description =
        "Ne pas déranger jusqu'à une heure : « je suis en réunion jusqu'à 15 h », « ne me dérange pas pendant une heure », « mode ne pas " +
            "déranger jusqu'à demain 8 h » (start, avec until en HH:MM ou minutes). Seuls les contacts favoris (étoile dans Contacts), les appels " +
            "répétés et les alarmes passent ; à la fin, les réglages de l'utilisateur reviennent et une notification résume ce qui est arrivé. " +
            "« J'ai fini ma réunion », « tu peux me déranger » : stop. status : où on en est."
    override val parameters = objectSchema {
        string("action", "'start' (défaut), 'stop' ou 'status'.")
        string("until", "Pour start : heure de fin HH:MM (la prochaine fois qu'il sera cette heure).")
        integer("minutes", "Pour start, à la place de until : durée en minutes (5 à 720 ; 60 par défaut).")
        string("reason", "Facultatif : réunion, sieste, cinéma…")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val context = ctx.appContext
        val zone = ZoneId.systemDefault()
        when (args.stringArg("action").trim().lowercase().ifEmpty { "start" }) {
            "stop", "end" -> return if (QuietMode.stop(context)) "Mode ne pas déranger terminé ; vos réglages habituels sont revenus. Un résumé de ce qui est arrivé s'affiche en notification."
            else "Le mode ne pas déranger de Jarvis n'était pas actif."
            "status" -> return QuietMode.active(context)?.let { "Ne pas déranger jusqu'à ${clockWords(it.untilMs, zone)}." } ?: "Le mode ne pas déranger de Jarvis n'est pas actif."
        }
        val until = quietUntil(args.stringArg("until"), args.intArg("minutes", 0).let { if (it <= 0 && args.stringArg("until").isBlank()) 60 else it }, LocalDateTime.now(zone), zone)
            ?: return "Heure de fin illisible : donnez-la en HH:MM ou en minutes."
        return when (QuietMode.start(context, until, args.stringArg("reason"))) {
            is QuietStart.Started -> {
                val auto = QuietMode.store(context).load().autoReply
                "Ne pas déranger jusqu'à ${clockWords(until, zone)} : seuls vos contacts favoris, les appels répétés et les alarmes passent" +
                    (if (auto) ", et chaque personne qui écrit reçoit une réponse automatique" else "") +
                    ". À la fin, je vous résume ce que vous avez manqué."
            }
            QuietStart.NeedsAccess -> {
                try {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
                "Android demande d'abord d'autoriser Jarvis à gérer le mode Ne pas déranger : j'ai ouvert la page, activez « Jarvis » puis redemandez-moi."
            }
        }
    }
}
