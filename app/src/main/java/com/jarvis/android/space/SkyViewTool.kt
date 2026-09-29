package com.jarvis.android.space

import com.jarvis.android.JarvisContainer
import com.jarvis.android.actions.Tool
import com.jarvis.android.actions.objectSchema
import com.jarvis.android.actions.stringArg
import com.jarvis.android.video.VideoPanel
import kotlinx.serialization.json.JsonObject

/** Shows the live sky (SkyView.kt) where the face is: the satellites and the aircraft around the user, with all their details. */
object SkyViewTool : Tool {
    override val name = "sky_view"
    override val description =
        "Afficher le ciel en direct à la place du visage, avec un maximum de détails : show = « all » (satellites et avions, par défaut), " +
            "« satellites », « planes » (les avions autour), ou « pass » (le prochain passage d’un satellite, name = ISS par défaut, avec son " +
            "tracé dans le ciel). Une carte du ciel (l’horizon en cercle, le zénith au centre, le nord en haut) montre chacun à sa vraie place ; " +
            "l’utilisateur touche un objet pour tout voir (un avion : modèle, compagnie, trajet, altitude, vitesse, photo ; un satellite : " +
            "altitude, vitesse, orbite, prochain passage). Pour « montre-moi les satellites / les avions au-dessus de moi », « montre le passage " +
            "de l’ISS ». Pour savoir ce qui est à l’écran, action look de play_video."
    override val parameters = objectSchema {
        string("show", "all, satellites, planes ou pass.")
        string("name", "Pour pass : le satellite (ISS par défaut, Tiangong, Hubble).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val show = args.stringArg("show").trim().lowercase()
        val (mode, title) = when (show) {
            "satellites", "sat" -> SkyModes.SATELLITES to "Satellites en direct"
            "planes", "avions", "aircraft" -> SkyModes.PLANES to "Avions autour de vous"
            "pass", "passage", "iss" -> {
                val name = args.stringArg("name").trim()
                val w = name.lowercase()
                (SkyModes.PASS + name) to "Passage " + when {
                    w.isEmpty() || "iss" in w || "internationale" in w -> "de l’ISS"
                    "tiangong" in w || "chinoise" in w -> "de Tiangong"
                    "hubble" in w -> "de Hubble"
                    else -> "de ${name.take(30)}"
                }
            }
            else -> SkyModes.ALL to "Ciel en direct"
        }
        ctx.videoPanel.show(VideoPanel.Video(title = title, sky = mode))
        return "$title s’affiche à la place du visage : une carte du ciel au-dessus de l’utilisateur, mise à jour chaque seconde, et la liste " +
            "détaillée ; il peut toucher un objet pour tout voir, ou mettre en plein écran. Dites-le en une phrase courte."
    }
}
