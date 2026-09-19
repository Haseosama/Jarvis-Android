package com.jarvis.android.actions

import android.content.Intent
import android.net.Uri
import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject
import java.util.Locale

object SendMessageTool : Tool {
    override val name = "send_message"
    override val description =
        "Préparer un brouillon dans WhatsApp, Telegram, les SMS ou une application choisie. Aucun envoi automatique ; l’utilisateur confirme dans l’application."
    override val parameters = objectSchema(required = listOf("text")) {
        string("text", "Texte du brouillon, sans envoi automatique.")
        string("app", "Facultatif : 'whatsapp', 'telegram', 'sms', ou vide pour choisir.")
    }

    private val packages = mapOf(
        "whatsapp" to "com.whatsapp",
        "telegram" to "org.telegram.messenger",
    )

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val draft = prepareMessageDraft(args.utilityString("text"),
            if ("app" in args) args.utilityString("app") else "")
            ?: return "Indiquez un message non vide (10 000 caractères maximum) et choisissez WhatsApp, Telegram, SMS ou laissez l’application vide."
        val intent = if (draft.app == "sms") {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply {
                putExtra("sms_body", draft.text)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, draft.text)
                packages[draft.app]?.let { setPackage(it) }
            }
        }
        val target = if (draft.app.isEmpty()) Intent.createChooser(intent, "Choisir une application pour le brouillon") else intent
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.appContext.startActivity(target)
            "Brouillon ouvert. Aucun message envoyé ; vérifiez le destinataire et confirmez vous-même l’envoi dans l’application."
        } catch (_: Exception) {
            "Impossible d’ouvrir l’application de messagerie. Aucun message envoyé."
        }
    }
}

internal data class MessageDraft(val text: String, val app: String)

internal fun prepareMessageDraft(text: String?, app: String?): MessageDraft? {
    if (text.isNullOrBlank() || text.length > 10_000 ||
        text.any { it.isISOControl() && it !in "\n\r\t" } || app == null) return null
    val normalizedApp = app.trim().lowercase(Locale.ROOT)
    if (normalizedApp !in setOf("", "whatsapp", "telegram", "sms")) return null
    return MessageDraft(text, normalizedApp)
}
