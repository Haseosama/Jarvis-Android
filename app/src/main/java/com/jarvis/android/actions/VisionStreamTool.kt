package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.core.VideoSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Lets the user say "regarde mon écran" / "regarde avec la caméra" / "arrête de regarder". */
object VisionStreamTool : Tool {
    override val name = "vision_stream"
    override val description =
        "Partager en continu l’écran ou la caméra avec la session vocale, pour que l’assistant voie en direct (une image toutes les deux secondes environ). " +
            "À utiliser seulement sur demande de l’utilisateur. Sources : 'screen', 'camera' ou 'off' pour arrêter."
    override val parameters = objectSchema(required = listOf("source")) {
        string("source", "'screen', 'camera' ou 'off'.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val source = when (args["source"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().lowercase()) {
            "screen", "ecran", "écran" -> VideoSource.SCREEN
            "camera", "caméra" -> VideoSource.CAMERA
            "off", "stop", "none" -> VideoSource.OFF
            else -> return "Source inconnue : screen, camera ou off."
        }
        return ctx.engine.setVideoSource(source)
    }
}
