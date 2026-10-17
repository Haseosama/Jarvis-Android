package com.jarvis.android.actions

import android.content.Intent
import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.stringArg
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.done
import com.jarvis.android.tool.failed
import com.jarvis.android.tool.unverified
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Which of [labels] the user meant by [query]: the exact name (accents and case aside), else a name containing it. Under four
 * characters only the exact name counts: Mark LVI found "wp" opening WPS Office for WhatsApp, two letters being a prefix of too much.
 */
internal fun matchAppLabel(query: String, labels: List<String>): Int? {
    val wanted = com.jarvis.android.text.normalize(query)
    if (wanted.isEmpty()) return null
    labels.indexOfFirst { it.equals(query, ignoreCase = true) || com.jarvis.android.text.normalize(it) == wanted }.takeIf { it >= 0 }?.let { return it }
    if (wanted.replace(" ", "").length < 4) return null
    return labels.indexOfFirst { it.contains(query, ignoreCase = true) }.takeIf { it >= 0 }
        ?: labels.indexOfFirst { com.jarvis.android.text.normalize(it).contains(wanted) }.takeIf { it >= 0 }
}

/** How long an app has to reach the screen before the result says it could not be seen there. */
private const val OPEN_CHECK_MS = 2_500L

/**
 * Launch an installed app by name — Android port of `actions/open_app.py`. Since 0.10.1 (after Mark LVI) the result says what was
 * seen: with the phone control on, the app must be on screen for "[done]"; otherwise, or if it never shows (Android refuses to bring
 * an app up from the background in some states), it is "[unverified]", never a plain "Opening".
 */
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
                if (launchIntent != null) Triple(label, launchIntent, app.packageName) else null
            }

        val index = matchAppLabel(query, launchable.map { it.first })
            ?: return failed("No app matching '$query' is installed.")
        val (label, intent, packageName) = launchable[index]
        try {
            ctx.appContext.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (e: Exception) {
            return failed("Could not open $label: ${e.message}")
        }
        val service = com.jarvis.android.device.JarvisAccessibilityService.instance
            ?: return unverified("Asked Android to open $label; without the phone control I cannot see whether it came up.")
        val seen = withTimeoutOrNull(OPEN_CHECK_MS) {
            while (service.activePackage() != packageName) delay(200)
            true
        } == true
        return if (seen) done("$label is open on the screen.")
        else unverified("Asked Android to open $label, but it did not come to the screen (Android may have held it back): the user should check.")
    }
}
