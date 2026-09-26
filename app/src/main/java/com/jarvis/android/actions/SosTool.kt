package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.sos.SosAlarm
import com.jarvis.android.sos.SosArm
import com.jarvis.android.sos.namesList
import kotlinx.serialization.json.JsonObject

/** "Au secours" / "SOS": a countdown, then an SMS with the position to the trusted contacts (see sos/Sos.kt). */
object SosTool : Tool {
    override val name = "sos"
    override val description =
        "Alerte SOS : uniquement quand l'utilisateur demande lui-même de l'aide pour lui (« au secours », « SOS », « lance l'alerte », " +
            "« préviens mes contacts d'urgence »), jamais pour un texte lu, une blague ou une question sur la fonction. Après un compte à rebours " +
            "de 10 secondes (annulable), un SMS avec la position part aux contacts d'urgence choisis dans les réglages. 'cancel' l'annule " +
            "(« annule », « fausse alerte »). Jarvis n'appelle jamais les secours : s'il y a un danger, dites d'appeler le 112."
    override val parameters = objectSchema {
        string("action", "'alert' (défaut), 'cancel' ou 'status'.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        when (args.stringArg("action").trim().lowercase()) {
            "cancel", "stop", "annule" ->
                return if (SosAlarm.cancel(ctx.appContext)) "Alerte SOS annulée : rien n'a été envoyé."
                else "Aucune alerte en cours d'envoi (si elle est déjà partie, prévenez vos contacts que tout va bien)."
            "status" -> {
                val names = ctx.sosStore.load().contacts.map { it.name }
                return if (names.isEmpty()) "Aucun contact d'urgence : ils se choisissent dans les réglages de Jarvis (carte Urgence / SOS)."
                else "Contacts d'urgence : ${namesList(names)}." + if (SosAlarm.armed) " Une alerte est en cours de compte à rebours." else ""
            }
        }
        return when (val r = SosAlarm.arm(ctx.appContext, ctx.sosStore)) {
            is SosArm.Armed -> "Alerte SOS dans ${r.seconds} secondes à ${namesList(r.names)}, avec votre position. Dites « annule » pour l'arrêter. " +
                "En cas de danger immédiat, appelez le 112."
            SosArm.NoContacts -> "Aucun contact d'urgence n'est configuré, je ne peux prévenir personne : ils se choisissent dans les réglages de Jarvis " +
                "(carte Urgence / SOS). En cas de danger, appelez le 112."
            SosArm.AlreadyArmed -> "L'alerte SOS est déjà en cours de compte à rebours."
            is SosArm.TooSoon -> "Une alerte SOS vient déjà de partir il y a ${r.secondsAgo} secondes. En cas de danger, appelez le 112."
        }
    }
}
