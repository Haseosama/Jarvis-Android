package com.jarvis.android.actions

import android.content.Intent
import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject

/**
 * Compose a message — Android port of `actions/send_message.py`. Android sandboxing
 * means an app cannot silently send a WhatsApp/SMS message on the user's behalf;
 * this opens the target app's share sheet with the text prefilled, and the user
 * taps send themselves (the same boundary the safety rules in this assistant draw
 * for any "send on my behalf" action).
 */
object SendMessageTool : Tool {
    override val name = "send_message"
    override val description =
        "Open a messaging app (WhatsApp, Telegram, SMS, or a chooser) with a message prefilled, ready to send."
    override val parameters = objectSchema(required = listOf("text")) {
        string("text", "The message text.")
        string("app", "Optional: 'whatsapp', 'telegram', 'sms', or leave empty for a chooser.")
    }

    private val packages = mapOf(
        "whatsapp" to "com.whatsapp",
        "telegram" to "org.telegram.messenger",
    )

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val text = args.stringArg("text")
        if (text.isBlank()) return "What should the message say?"
        val app = args.stringArg("app").lowercase()

        val intent = if (app == "sms") {
            Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("smsto:")
                putExtra("sms_body", text)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                packages[app]?.let { setPackage(it) }
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            val chooser = if (app.isBlank() || app == "sms") intent else Intent.createChooser(intent, "Send message")
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ctx.appContext.startActivity(chooser)
            "Message ready to send — tap send in the app."
        } catch (e: Exception) {
            "Could not open a messaging app for '$app': ${e.message}"
        }
    }
}
