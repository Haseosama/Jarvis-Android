package com.jarvis.android.actions

import android.content.Intent
import android.net.Uri
import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject

/** Open a URL — Android port of `actions/browser_control.py`. */
object BrowserTool : Tool {
    override val name = "browser_control"
    override val description = "Open a website or URL in the browser."
    override val parameters = objectSchema(required = listOf("url")) {
        string("url", "The URL to open, e.g. 'wikipedia.org' or 'https://example.com'.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        var url = args.stringArg("url").trim()
        if (url.isBlank()) return "Which website?"
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.appContext.startActivity(intent)
            "Opening $url."
        } catch (e: Exception) {
            "Could not open $url: ${e.message}"
        }
    }
}
