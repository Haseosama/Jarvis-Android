package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import kotlinx.serialization.json.JsonObject

/*
 * Controls the user's computer through Jarvis on the PC (Jarvis 2.0, or Mark-LIV), which already has the hands for it: apps, volume, brightness,
 * windows, keyboard and mouse, browser, files, screen reading, power. The phone pairs with its remote-control server once and
 * forwards the instruction in plain words; the PC carries it out and its answer comes back (pc/PcRemote.kt).
 */
object PcControlTool : Tool {
    override val name = "jarvis_pc"
    override val description =
        "Contrôle l'ordinateur de l'utilisateur via Jarvis sur le PC (Jarvis 2.0, joignable de partout par Tailscale, ou sur le même Wi-Fi). action « command » " +
            "(défaut) : transmet « instruction » en langage naturel à Jarvis sur le PC, qui l'exécute sur le PC et répond (ouvrir une appli ou " +
            "un site, volume, luminosité, fenêtres, taper du texte, fichiers, lancer une vidéo, décrire l'écran, verrouiller, mettre en " +
            "veille, éteindre…) ; formulez l'ordre complet, comme si l'utilisateur le disait au PC. action « status » : le PC répond-il. " +
            "action « pair » : appairer avec « address » (IP du PC, ou le lien du QR code) et « code » (6 caractères affichés sur " +
            "le PC par « Appairer un téléphone »). action « forget » : oublier le PC. Pour tout ce qui concerne le PC, utilisez cet outil, " +
            "pas les outils du téléphone."
    override val parameters = objectSchema {
        string("action", "command (défaut), status, pair ou forget.")
        string("instruction", "Pour command : l'ordre pour le PC, en français, complet.")
        string("address", "Pour pair : adresse IP du PC (ex. 192.168.1.20 ou 192.168.1.20:8000) ou lien du QR code.")
        string("code", "Pour pair : le code à 6 caractères affiché sur le PC.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val pc = ctx.pcRemote
        return when (args.stringArg("action").trim().lowercase()) {
            "status", "etat", "état" -> pc.status()
            "pair", "appairer", "connect" -> pc.pair(args.stringArg("address"), args.stringArg("code"))
            "forget", "unpair", "oublier" -> pc.forget()
            else -> {
                val instruction = args.stringArg("instruction").ifBlank { args.stringArg("command") }
                pc.command(instruction).also { ctx.log("PC ← $instruction") }
            }
        }
    }
}
