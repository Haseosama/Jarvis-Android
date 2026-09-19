package com.jarvis.android.actions

import android.content.Intent
import android.net.Uri
import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object BrowserTool : Tool {
    override val name = "browser_control"
    override val description = "Ouvrir un site HTTP ou HTTPS dans le navigateur."
    override val parameters = objectSchema(required = listOf("url")) {
        string("url", "Adresse HTTP(S) ou nom de domaine à ouvrir.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val url = normalizeBrowserUrl(args.utilityString("url").orEmpty())
            ?: return "Adresse invalide. Indiquez un site HTTP ou HTTPS, sans identifiants."
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.appContext.startActivity(intent)
            "Ouverture du navigateur : $url"
        } catch (_: Exception) {
            "Impossible d’ouvrir cette adresse. Vérifiez qu’un navigateur est installé."
        }
    }
}

internal fun JsonObject.utilityString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun normalizeBrowserUrl(input: String, allowBareHost: Boolean = true): String? {
    if (input.length > 4096 || input.any { it.isISOControl() || it == '\\' }) return null
    val value = input.trim()
    if (value.isEmpty() || value.any { it.isWhitespace() }) return null
    val explicitHttp = value.startsWith("http://", true) || value.startsWith("https://", true)
    if (!explicitHttp && (!allowBareHost || value.startsWith("//") ||
            Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value))) return null
    val candidate = if (explicitHttp) value else "https://$value"
    val authority = candidate.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isBlank() || '@' in authority) return null
    val url = candidate.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    if (!explicitHttp && '.' !in url.host && url.host != "localhost") return null
    return url.toString()
}

internal fun normalizedUtilityQuery(input: String?, maxLength: Int): String? {
    if (input == null || input.length > maxLength || input.any { it.isISOControl() }) return null
    return input.trim().takeIf { it.isNotEmpty() }
}
