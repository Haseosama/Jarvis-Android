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
        "Briefing du matin : quand l'utilisateur arrête son réveil du matin, Jarvis dit (ou affiche) la météo, la pluie qui arrive (avec le " +
            "radar), l'agenda, les rappels, ses vols du jour, les prélèvements, les mails importants non lus, l'ISS visible ce soir, les " +
            "lancements de fusées du jour, une aurore possible cette nuit et la nuit de sommeil. set (mode : 'speak' à voix haute, 'notify' " +
            "en notification, 'off') : « lis-moi le briefing quand j'arrête mon réveil » ; car ('on'/'off') : le dire aussi en montant en " +
            "voiture le matin (Android Auto ou le Bluetooth de la voiture) ; skip / include : retirer ou remettre des parties (« sans les " +
            "mails », « remets la météo »). now : le briefing tout de suite. status : les réglages."
    override val parameters = objectSchema {
        string("action", "'now' (défaut), 'set' ou 'status'.")
        string("mode", "Pour set : 'speak', 'notify' ou 'off'.")
        string("car", "Pour set : 'on' ou 'off' (le briefing en voiture le matin).")
        string("skip", "Pour set : les parties à retirer, séparées par des virgules (mails, météo, pluie, agenda, rappels, prélèvements, vols, iss, fusées, aurores, sommeil).")
        string("include", "Pour set : les parties à remettre.")
    }

    private fun modeWords(mode: Int) = when (mode) {
        WAKE_SPEAK -> "à voix haute quand vous arrêtez le réveil du matin"
        WAKE_NOTIFY -> "en notification quand vous arrêtez le réveil du matin"
        else -> "désactivé"
    }

    private fun statusWords(d: com.jarvis.android.wakeup.WakeData): String {
        val parts = com.jarvis.android.wakeup.BRIEFING_SECTIONS.filterKeys { it !in d.off }.values
        return "Briefing du matin : ${modeWords(d.mode)}" + (if (d.car) " ; et en voiture le matin" else "") + ". Il dit : ${parts.joinToString(", ")}." +
            if (d.off.isNotEmpty()) " Retirés : ${d.off.mapNotNull { com.jarvis.android.wakeup.BRIEFING_SECTIONS[it] }.joinToString(", ")}." else ""
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val store = ctx.wakeStore
        return when (args.stringArg("action").trim().lowercase().ifEmpty { "now" }) {
            "set" -> {
                val modeWord = args.stringArg("mode").trim().lowercase()
                val mode = when (modeWord) {
                    "speak", "voice", "aloud" -> WAKE_SPEAK
                    "notify", "notification" -> WAKE_NOTIFY
                    "off", "none" -> WAKE_OFF
                    "" -> null
                    else -> return "Mode inconnu : speak, notify ou off."
                }
                val car = when (args.stringArg("car").trim().lowercase()) { "on", "oui", "true" -> true; "off", "non", "false" -> false; else -> null }
                val skip = args.stringArg("skip").split(',', ';').mapNotNull { com.jarvis.android.wakeup.sectionKey(it) }
                val include = args.stringArg("include").split(',', ';').mapNotNull { com.jarvis.android.wakeup.sectionKey(it) }
                val d = store.update { it.copy(mode = mode ?: it.mode, car = car ?: it.car, off = ((it.off + skip) - include.toSet()).distinct()) }
                statusWords(d) + if (mode != null && mode != WAKE_OFF) " Il marche avec le réveil de l'application Horloge (ou celui réglé par Jarvis)." else ""
            }
            "status" -> statusWords(store.load())
            else -> {
                // The text is returned for you to say; the notification keeps it.
                WakeBriefing.deliver(ctx, WAKE_NOTIFY)
            }
        }
    }
}
