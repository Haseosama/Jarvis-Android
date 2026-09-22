package com.jarvis.android.actions

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder

/*
 * Opens Spotify's own search for a title, an artist or a playlist, the same shape as `youtube_video`:
 * results are opened, the user picks and presses play themselves. No Spotify developer account, no
 * OAuth, no API key — Spotify's Web API can do more (actually start playback on a chosen device by
 * itself), but only after the USER registers their own app in Spotify's developer dashboard and
 * Jarvis is granted access, the same dead end Google Home would have been (see "Maison connectée").
 * `spotify:search:<query>` is a plain, stable app deep link, not a private API.
 *
 * Play/pause/next/previous already work for whatever is currently playing — Spotify included — through
 * `device_settings`' media keys, which this tool does not duplicate.
 */
// java.net.URLEncoder, not android.net.Uri: the latter is an unmocked Android-framework class in plain JVM unit
// tests, and RFC 3986 percent-encoding is all a search query needs — URLEncoder's own "+" for space is swapped
// back to "%20" since it otherwise means a literal plus in the query rather than a space.
private fun percentEncode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

internal fun spotifySearchUri(query: String?): String? {
    val q = normalizedUtilityQuery(query, 200) ?: return null
    return "spotify:search:" + percentEncode(q)
}

internal fun spotifyWebSearchUrl(query: String?): String? {
    val q = normalizedUtilityQuery(query, 200) ?: return null
    return "https://open.spotify.com/search/".toHttpUrl().newBuilder().addPathSegment(q).build().toString()
}

object SpotifyTool : Tool {
    override val name = "spotify_search"
    override val description =
        "Ouvrir une recherche Spotify (un titre, un artiste, une playlist) ; l'utilisateur choisit et lance la lecture lui-même. " +
            "Pour lecture/pause/suivant/précédent sur ce qui joue déjà, utilisez device_settings (media), pas cet outil."
    override val parameters = objectSchema(required = listOf("query")) {
        string("query", "Ce qu'il faut chercher sur Spotify (titre, artiste ou playlist).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val query = args.utilityString("query")
        val appUri = spotifySearchUri(query) ?: return "Indiquez une recherche non vide, de 200 caractères maximum."
        return try {
            try {
                ctx.appContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(appUri)).setPackage("com.spotify.music").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                "Recherche Spotify ouverte. Choisissez le titre et lancez la lecture."
            } catch (_: ActivityNotFoundException) {
                val webUrl = spotifyWebSearchUrl(query)!!
                ctx.appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Spotify n'est pas installé : recherche ouverte dans le navigateur à la place."
            }
        } catch (_: Exception) {
            "Impossible d'ouvrir Spotify ou un navigateur."
        }
    }
}
