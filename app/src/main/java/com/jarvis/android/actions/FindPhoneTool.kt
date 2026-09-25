package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.device.PhoneRinger
import com.jarvis.android.device.RING_DEFAULT_SECONDS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** "Où es-tu ?": rings the phone loud, even in silent mode, so it can be found. */
object FindPhoneTool : Tool {
    override val name = "find_phone"
    override val description =
        "Faire sonner le téléphone très fort (même en mode silencieux) pour le retrouver : « où es-tu ? », « où est mon téléphone ? », " +
            "« fais sonner le téléphone ». Il sonne une minute, s'arrête avec « arrête de sonner » (action stop) ou le bouton « Trouvé » " +
            "de la notification. Pendant la sonnerie, restez bref : l'utilisateur ne vous entend probablement pas."
    override val parameters = objectSchema {
        string("action", "'ring' (défaut) ou 'stop'.")
        integer("seconds", "Durée de la sonnerie, 10 à 180 secondes (défaut $RING_DEFAULT_SECONDS).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.Main) {
        if (args.stringArg("action").trim().lowercase() == "stop") {
            val was = PhoneRinger.ringing
            PhoneRinger.stopNow()
            return@withContext if (was) "Sonnerie arrêtée." else "Le téléphone ne sonnait pas."
        }
        val seconds = args.intArg("seconds", RING_DEFAULT_SECONDS)
        if (PhoneRinger.start(ctx.appContext, seconds)) {
            "Le téléphone sonne au volume maximum pendant ${seconds.coerceIn(10, 180)} secondes. Dites « arrête de sonner » ou touchez « Trouvé » pour l'arrêter."
        } else {
            "Impossible de faire sonner le téléphone : aucun son disponible. Il vibre peut-être quand même."
        }
    }
}
