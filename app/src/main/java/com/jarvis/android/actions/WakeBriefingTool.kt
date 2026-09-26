package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.wakeup.WAKE_NOTIFY
import com.jarvis.android.wakeup.WAKE_OFF
import com.jarvis.android.wakeup.WAKE_SPEAK
import com.jarvis.android.wakeup.WakeBriefing
import kotlinx.serialization.json.JsonObject

/** The briefing on waking up (see wakeup/WakeBriefing.kt): its setting, or the briefing now. */
object WakeBriefingTool : Tool {
    override val name = "wake_briefing"
    override val description =
        "Briefing au réveil : quand l'utilisateur arrête son réveil du matin, Jarvis dit (ou affiche) la météo, l'agenda, les rappels, les " +
            "prélèvements du jour et la nuit de sommeil. set (mode : 'speak' à voix haute, 'notify' en notification, 'off') : « lis-moi le " +
            "briefing quand j'arrête mon réveil ». now : le briefing tout de suite (« fais-moi le briefing du réveil »). status."
    override val parameters = objectSchema {
        string("action", "'now' (défaut), 'set' ou 'status'.")
        string("mode", "Pour set : 'speak', 'notify' ou 'off'.")
    }

    private fun modeWords(mode: Int) = when (mode) {
        WAKE_SPEAK -> "à voix haute quand vous arrêtez le réveil du matin"
        WAKE_NOTIFY -> "en notification quand vous arrêtez le réveil du matin"
        else -> "désactivé"
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val store = ctx.wakeStore
        return when (args.stringArg("action").trim().lowercase().ifEmpty { "now" }) {
            "set" -> {
                val mode = when (args.stringArg("mode").trim().lowercase()) {
                    "speak", "voice", "aloud" -> WAKE_SPEAK
                    "notify", "notification" -> WAKE_NOTIFY
                    "off", "none" -> WAKE_OFF
                    else -> return "Mode inconnu : speak, notify ou off."
                }
                store.update { it.copy(mode = mode) }
                "Briefing au réveil : ${modeWords(mode)}." + if (mode != WAKE_OFF) " Il marche avec le réveil de l'application Horloge (ou celui réglé par Jarvis)." else ""
            }
            "status" -> "Briefing au réveil : ${modeWords(store.load().mode)}."
            else -> {
                // The text is returned for you to say; the notification keeps it.
                WakeBriefing.deliver(ctx, WAKE_NOTIFY)
            }
        }
    }
}
