package com.jarvis.android.actions

import android.content.Intent
import android.net.Uri
import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject

/** Search/play YouTube — Android port of `actions/youtube_video.py`. */
object YoutubeTool : Tool {
    override val name = "youtube_video"
    override val description = "Search for and play a video on YouTube."
    override val parameters = objectSchema(required = listOf("query")) {
        string("query", "What to search for on YouTube.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val query = args.stringArg("query")
        if (query.isBlank()) return "What should I search for on YouTube?"
        val encoded = Uri.encode(query)
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://results?q=$encoded"))
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded"))
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        return try {
            val pm = ctx.appContext.packageManager
            if (appIntent.resolveActivity(pm) != null) ctx.appContext.startActivity(appIntent)
            else ctx.appContext.startActivity(webIntent)
            "Searching YouTube for '$query'."
        } catch (e: Exception) {
            "Could not open YouTube: ${e.message}"
        }
    }
}
