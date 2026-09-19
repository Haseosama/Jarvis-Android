package com.jarvis.android.actions

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

object YoutubeTool : Tool {
    override val name = "youtube_video"
    override val description = "Ouvrir les résultats d’une recherche YouTube ; l’utilisateur choisit la vidéo."
    override val parameters = objectSchema(required = listOf("query")) {
        string("query", "Termes à rechercher sur YouTube.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val url = youtubeSearchUrl(args.utilityString("query"))
            ?: return "Indiquez une recherche YouTube non vide, de 500 caractères maximum."
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            try {
                ctx.appContext.startActivity(Intent(intent).setPackage("com.google.android.youtube"))
            } catch (_: ActivityNotFoundException) {
                ctx.appContext.startActivity(intent)
            }
            "Résultats YouTube ouverts. Choisissez la vidéo à lire."
        } catch (_: Exception) {
            "Impossible d’ouvrir YouTube ou un navigateur."
        }
    }
}

internal fun youtubeSearchUrl(input: String?): String? {
    val query = normalizedUtilityQuery(input, 500) ?: return null
    return "https://www.youtube.com/results".toHttpUrl().newBuilder()
        .addQueryParameter("search_query", query).build().toString()
}
