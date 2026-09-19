package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject

/** Lets the user close the voice session by voice ("arrête la session", "au revoir"). */
object EndSessionTool : Tool {
    override val name = "end_session"
    override val description =
        "Terminer la session vocale (micro coupé) quand l’utilisateur demande d’arrêter, de fermer ou de terminer la session, ou dit au revoir pour de bon."

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String =
        if (ctx.engine.requestEndSession()) {
            "La session va se fermer. Dites simplement au revoir, en une phrase courte."
        } else {
            "Aucune session vocale en cours."
        }
}
