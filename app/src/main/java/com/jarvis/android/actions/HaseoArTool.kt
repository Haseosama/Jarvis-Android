package com.jarvis.android.actions

import android.content.Intent
import com.jarvis.android.JarvisContainer
import com.jarvis.android.ar.ArFaceActivity
import com.jarvis.android.tool.Tool
import kotlinx.serialization.json.JsonObject

/** "Pose-toi sur la table" / "montre-toi en réalité augmentée": opens the camera with the little robot standing on the table. */
object HaseoArTool : Tool {
    override val name = "haseo_realite_augmentee"
    override val description =
        "Ouvre la réalité augmentée : l'assistant en petit robot chromé posé sur une table vue par l'appareil photo, qui regarde l'utilisateur " +
            "quand il bouge. Pour « pose-toi sur la table », « montre-toi en réalité augmentée », « viens dans la pièce ». " +
            "Sans ARCore sur le téléphone, le robot flotte devant la caméra."

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = try {
        ctx.appContext.startActivity(Intent(ctx.appContext, ArFaceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "Réalité augmentée ouverte : il faut viser la table et la toucher pour y poser le robot."
    } catch (_: Exception) {
        "Impossible d'ouvrir la réalité augmentée depuis l'arrière-plan : ouvrir Jarvis et toucher le bouton de réalité augmentée en haut."
    }
}
