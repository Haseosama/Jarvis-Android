package com.jarvis.android.google

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** A message as listed: enough to be read out. */
internal data class MailSummary(val id: String, val from: String, val subject: String, val date: String, val snippet: String, val unread: Boolean)

internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal fun decodeBase64Url(text: String): ByteArray = try {
    Base64.getUrlDecoder().decode(text.trim())
} catch (_: IllegalArgumentException) {
    ByteArray(0)
}

/** A header value that may contain non-ASCII characters, as RFC 2047 "encoded words" (UTF-8, base64), so mail clients show it right. */
internal fun encodeHeader(value: String): String =
    if (value.all { it.code in 0x20..0x7E }) value else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)) + "?="

private val ADDRESS = Regex("^[^\\s@<>\",;]+@[^\\s@<>\",;]+\\.[^\\s@<>\",;]+$")

/** Whether [address] looks like one e-mail address (no name part, no list). */
internal fun validAddress(address: String) = ADDRESS.matches(address.trim())

/** The raw RFC 2822 text of a plain message, base64url-encoded as Gmail wants it. Line breaks in header values are refused. */
internal fun buildRawMessage(to: String, subject: String, body: String): String {
    require(validAddress(to)) { "Adresse du destinataire invalide." }
    require('\n' !in subject && '\r' !in subject) { "Objet invalide." }
    val text = buildString {
        append("To: ${to.trim()}\r\n")
        append("Subject: ${encodeHeader(subject.trim())}\r\n")
        append("MIME-Version: 1.0\r\n")
        append("Content-Type: text/plain; charset=UTF-8\r\n")
        append("Content-Transfer-Encoding: base64\r\n\r\n")
        append(Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(body.toByteArray(Charsets.UTF_8)))
    }
    return base64Url(text.toByteArray(Charsets.UTF_8))
}

/** The Gmail search for a list: unread mail, or [query] (Gmail's own syntax: `from:x`, `newer_than:2d`…). */
internal fun gmailQuery(query: String, unreadOnly: Boolean): String {
    val q = query.trim()
    return when {
        q.isNotEmpty() && unreadOnly -> "is:unread ($q)"
        q.isNotEmpty() -> q
        unreadOnly -> "is:unread"
        else -> "in:inbox"
    }
}

private fun JsonObject.header(name: String): String =
    (this["payload"] as? JsonObject)?.get("headers")?.jsonArray
        ?.firstOrNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull.equals(name, ignoreCase = true) }
        ?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull.orEmpty()

internal fun parseMailSummary(message: JsonObject): MailSummary = MailSummary(
    id = message["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    from = message.header("From"),
    subject = message.header("Subject").ifBlank { "(sans objet)" },
    date = message.header("Date"),
    snippet = message["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    unread = (message["labelIds"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == "UNREAD" } == true,
)

/** The readable text of a full Gmail message: its text/plain part, else its text/html part without the tags. */
internal fun extractBody(message: JsonObject): String {
    fun partText(part: JsonObject, mime: String): String? {
        val type = part["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val data = (part["body"] as? JsonObject)?.get("data")?.jsonPrimitive?.contentOrNull
        if (type == mime && !data.isNullOrEmpty()) return String(decodeBase64Url(data), Charsets.UTF_8)
        for (child in (part["parts"] as? JsonArray).orEmpty()) partText(child.jsonObject, mime)?.let { return it }
        return null
    }
    val payload = message["payload"] as? JsonObject ?: return ""
    partText(payload, "text/plain")?.let { return it.trim() }
    return partText(payload, "text/html")?.let { htmlToText(it) }.orEmpty()
}

internal fun htmlToText(html: String): String = html
    .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
    .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</li>"), "\n")
    .replace(Regex("<[^>]+>"), " ")
    .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
    .lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()

/** Escapes a value for a Drive search string: backslashes and single quotes. */
internal fun driveEscape(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

/** The Drive query that finds [text] in a file's name or content, outside the bin. */
internal fun driveQuery(text: String): String {
    val t = driveEscape(text.trim().take(200))
    return if (t.isEmpty()) "trashed = false" else "(name contains '$t' or fullText contains '$t') and trashed = false"
}

private const val GOOGLE_APPS = "application/vnd.google-apps."

/** How a Drive file is read: exported as text, downloaded as text, or downloaded to be analysed; null when it cannot be read. */
internal sealed interface DriveRead {
    data class Export(val mime: String) : DriveRead
    data object DownloadText : DriveRead
    data class DownloadForAnalysis(val mime: String) : DriveRead
}

internal fun driveReadKind(mime: String): DriveRead? = when {
    mime == GOOGLE_APPS + "document" || mime == GOOGLE_APPS + "presentation" -> DriveRead.Export("text/plain")
    mime == GOOGLE_APPS + "spreadsheet" -> DriveRead.Export("text/csv")
    mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" -> DriveRead.DownloadText
    mime == "application/pdf" || mime.startsWith("image/") || mime.startsWith("audio/") -> DriveRead.DownloadForAnalysis(mime)
    else -> null
}
