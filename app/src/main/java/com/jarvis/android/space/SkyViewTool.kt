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
            "de l’ISS ». show = « map » : la carte du monde (où sont l’ISS et Tiangong, leur trace, le jour et la nuit). Pour savoir ce qui " +
            "est à l’écran, action look de play_video."
    override val parameters = objectSchema {
        string("show", "all, stars (le ciel étoilé : Lune, planètes, étoiles, constellations), satellites, planes, pass ou map (la carte du monde).")
        string("name", "Pour pass : le satellite (ISS par défaut, Tiangong, Hubble).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val show = args.stringArg("show").trim().lowercase()
        val (mode, title) = when (show) {
            "satellites", "sat" -> SkyModes.SATELLITES to "Satellites en direct"
            "stars", "etoiles", "étoiles", "night", "nuit" -> SkyModes.STARS to "Le ciel étoilé"
            "planes", "avions", "aircraft" -> SkyModes.PLANES to "Avions autour de vous"
            "map", "carte", "monde", "world" -> SkyModes.MAP to "Carte du monde"
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
        if (mode == SkyModes.MAP) {
            return "La carte du monde s’affiche à la place du visage : l’ISS et Tiangong là où ils sont, avec leur trace, et la nuit sur la Terre. " +
                "Dites-le en une phrase courte."
        }
        return "$title s’affiche à la place du visage : une carte du ciel au-dessus de l’utilisateur, mise à jour chaque seconde, et la liste " +
            "détaillée ; il peut toucher un objet pour tout voir, ou mettre en plein écran. Dites-le en une phrase courte."
    }
}

/** The rain radar where the face is: the last two hours of rain around the user, animated (RainViewer), on the world map. */
object RainRadarTool : Tool {
    override val name = "rain_radar"
    override val description =
        "Afficher le radar de pluie à la place du visage : les deux dernières heures de précipitations autour de l’utilisateur, animées " +
            "(RainViewer), sur une carte qu’il peut agrandir et déplacer. Pour « montre-moi le radar », « est-ce qu’il pleut autour », " +
            "« d’où vient la pluie ». Les prévisions chiffrées restent l’outil météo."
    override val parameters = objectSchema {
        string("action", "show (par défaut).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        ctx.videoPanel.show(VideoPanel.Video(title = "Radar de pluie", sky = SkyModes.RADAR))
        return "Le radar de pluie s’affiche à la place du visage, centré sur l’utilisateur : les deux dernières heures en boucle. Dites-le " +
            "en une phrase courte."
    }
}
