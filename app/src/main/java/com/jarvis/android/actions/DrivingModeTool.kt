package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.driving.DrivingMode
import com.jarvis.android.notifications.JarvisNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** "Mode conduite": short answers, messages read out, optional "je conduis" answer (see driving/DrivingMode.kt). */
object DrivingModeTool : Tool {
    override val name = "driving_mode"
    override val description =
        "Mode conduite : « mode conduite », « je prends la route » (start), « arrête le mode conduite » (stop), « le mode conduite est actif ? » " +
            "(status). Actif, Jarvis répond court, lit à voix haute les messages qui arrivent et, si l'utilisateur l'a activé dans les réglages, " +
            "répond une fois à chaque personne « je conduis ». Il s'active aussi seul avec le Bluetooth de la voiture si c'est réglé."
    override val parameters = objectSchema {
        string("action", "'start' (défaut), 'stop' ou 'status'.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.Main) {
        val context = ctx.appContext
        val settings = DrivingMode.store(context).load()
        val readable = JarvisNotificationListener.isEnabled(context)
        fun what() = buildList {
            add("réponses courtes")
            if (settings.readMessages) add(if (readable) "messages lus à voix haute" else "messages NON lus (il faut l'accès aux notifications pour Jarvis)")
            if (settings.autoReply) add("réponse automatique « ${settings.replyText} » une fois par personne")
        }.joinToString(", ")
        when (args.stringArg("action").trim().lowercase().ifEmpty { "start" }) {
            "stop", "off" -> {
                val was = DrivingMode.active
                DrivingMode.stop(context)
                if (was) "Mode conduite désactivé." else "Le mode conduite n'était pas actif."
            }
            "status" -> if (DrivingMode.active) "Mode conduite actif : ${what()}." else "Mode conduite inactif."
            else -> {
                DrivingMode.start(context, byCar = false)
                "Mode conduite activé : ${what()}. Bonne route. (Répondez en une phrase ; dites « arrête le mode conduite » pour l'arrêter.)"
            }
        }
    }
}
