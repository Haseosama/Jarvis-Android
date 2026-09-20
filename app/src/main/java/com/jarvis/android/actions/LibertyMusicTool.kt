package com.jarvis.android.actions

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject

internal const val LIBERTY_PACKAGE = "com.libertymusic.android"

/** Media key for a control word, or null. */
internal fun libertyKeyCode(control: String): Int? = when (control.trim().lowercase()) {
    "play", "lecture" -> KeyEvent.KEYCODE_MEDIA_PLAY
    "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
    "toggle", "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    "next", "suivant" -> KeyEvent.KEYCODE_MEDIA_NEXT
    "previous", "precedent", "précédent" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
    "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
    else -> null
}

/** True for a YouTube Music link Liberty Music declares it can open (video, playlist or channel). */
internal fun isLibertyLink(url: String): Boolean {
    val uri = try { java.net.URI(url) } catch (_: Exception) { return false }
    if (uri.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    if (host != "music.youtube.com" && host != "www.youtube.com" && host != "youtube.com" && host != "m.youtube.com" && host != "youtu.be") return false
    val path = uri.path.orEmpty()
    return host == "youtu.be" || path.startsWith("/watch") || path.startsWith("/playlist") || path.startsWith("/channel/")
}

/**
 * Drives the Liberty Music app (a YouTube Music client) that is installed on the phone. It is a built-in tool
 * rather than a JSON plugin because it sends media keys and pins the link to that app, which JSON plugins cannot do.
 * Liberty has no "search and play" entry point for other apps, so playing a song by name is done on the screen
 * with the screen tools (see the description).
 */
object LibertyMusicTool : Tool {
    override val name = "liberty_music"
    override val description =
        "Contrôler l'application Liberty Music (lecteur YouTube Music) installée sur le téléphone. Actions : " +
            "open (ouvre l'appli), control (play, pause, next, previous, stop sur la lecture en cours), " +
            "play_link (ouvre dans Liberty un lien music.youtube.com/watch, /playlist ou /channel). " +
            "Liberty n'accepte pas de recherche depuis l'extérieur : pour jouer un titre par son nom, faites open, " +
            "puis utilisez les outils d'écran (screen_tap sur la recherche, screen_type avec le titre, screen_tap sur le premier résultat)."
    override val parameters = objectSchema {
        string("action", "'open' (défaut), 'control' ou 'play_link'.")
        string("control", "Pour control : play, pause, toggle, next, previous ou stop.")
        string("url", "Pour play_link : lien https music.youtube.com (watch, playlist ou channel).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val context = ctx.appContext
        if (context.packageManager.getLaunchIntentForPackage(LIBERTY_PACKAGE) == null) {
            return "Liberty Music n'est pas installée sur ce téléphone."
        }
        return when (args.stringArg("action").trim().lowercase().ifEmpty { "open" }) {
            "control" -> {
                val key = libertyKeyCode(args.stringArg("control")) ?: return "Commande inconnue : utilisez play, pause, toggle, next, previous ou stop."
                sendKey(context, key)
                "Commande envoyée au lecteur en cours. Elle s'adresse à l'application de musique active, pas forcément à Liberty."
            }
            "play_link" -> {
                val url = args.stringArg("url").trim()
                if (!isLibertyLink(url)) return "Lien refusé : il faut un lien https youtube (watch, playlist ou channel)."
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(LIBERTY_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                    "Lien ouvert dans Liberty Music."
                } catch (_: Exception) {
                    "Liberty Music n'a pas pu ouvrir ce lien."
                }
            }
            else -> {
                val intent = context.packageManager.getLaunchIntentForPackage(LIBERTY_PACKAGE)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Liberty Music ouverte."
            }
        }
    }

    private fun sendKey(context: Context, keyCode: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
