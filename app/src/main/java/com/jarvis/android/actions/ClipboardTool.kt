package com.jarvis.android.actions

import android.content.ClipboardManager
import android.content.Context
import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject

/**
 * Read the clipboard — Android port of the "Clipboard Intelligence" feature.
 * Android 10+ only allows a foreground app to read the clipboard (a privacy
 * restriction the desktop version doesn't have to deal with), so this only
 * works while the Jarvis app itself is in front — there's no system-wide
 * floating panel like the desktop's, since that needs the special
 * "draw over other apps" permission.
 */
object ClipboardTool : Tool {
    override val name = "read_clipboard"
    override val description = "Read whatever text is currently on the clipboard, to translate/summarize/explain it."

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val cm = ctx.appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) return "The clipboard is empty."
        val text = clip.getItemAt(0).coerceToText(ctx.appContext)?.toString()
        return if (text.isNullOrBlank()) "The clipboard has no text on it." else "Clipboard: $text"
    }
}
