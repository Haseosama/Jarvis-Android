package com.jarvis.android.actions

import android.content.Intent
import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject

/** Launch an installed app by name — Android port of `actions/open_app.py`. */
object OpenAppTool : Tool {
    override val name = "open_app"
    override val description = "Open/launch an app installed on the phone by its name."
    override val parameters = objectSchema(required = listOf("app_name")) {
        string("app_name", "The app's display name, e.g. 'Spotify', 'Camera', 'Gmail'.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val query = args.stringArg("app_name").trim()
        if (query.isBlank()) return "Which app?"
        val pm = ctx.appContext.packageManager
        val launchable = pm.getInstalledApplications(0)
            .mapNotNull { app ->
                val label = pm.getApplicationLabel(app).toString()
                val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                if (launchIntent != null) label to launchIntent else null
            }

        val wanted = com.jarvis.android.offline.normalize(query)
        val exact = launchable.firstOrNull { it.first.equals(query, ignoreCase = true) || com.jarvis.android.offline.normalize(it.first) == wanted }
        val partial = exact ?: launchable.firstOrNull { it.first.contains(query, ignoreCase = true) }
            ?: launchable.firstOrNull { com.jarvis.android.offline.normalize(it.first).contains(wanted) }

        if (partial == null) return "No app matching '$query' is installed."
        val intent = partial.second.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        ctx.appContext.startActivity(intent)
        return "Opening ${partial.first}."
    }
}
