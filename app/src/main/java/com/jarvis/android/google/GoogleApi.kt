package com.jarvis.android.google

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private const val GMAIL = "https://gmail.googleapis.com/gmail/v1/users/me"
private const val DRIVE = "https://www.googleapis.com/drive/v3"
internal const val MAX_DRIVE_BYTES = 15_000_000

/** The Gmail and Drive REST calls Jarvis makes, with the user's access token. Every failure comes back as a [GoogleException] with a sentence to say. */
internal class GoogleApi(private val context: Context, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun token(cleanup: Boolean = false): String = GoogleAuth.accessToken(context, cleanup).getOrThrow()

    /** [cleanup]: with the token that may also sort the mail (spam, the bin, filters). */
    private suspend fun <T> call(request: Request.Builder, cleanup: Boolean = false, read: (okhttp3.Response) -> T): T = withContext(Dispatchers.IO) {
        try {
            http.newCall(request.header("Authorization", "Bearer ${token(cleanup)}").build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = runCatching { json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull }.getOrNull()
                    throw GoogleException(
                        when (response.code) {
                            401 -> throw GoogleException(GoogleAuth.EXPIRED, needsReconnect = true)
                            403 -> "Google refuse l’accès (${detail ?: "autorisation manquante"}) : l’API Gmail ou Drive doit être activée dans Google Cloud, et l’autorisation accordée."
                            404 -> "Introuvable."
                            429 -> "Trop de requêtes : réessayez dans un moment."
                            else -> "Erreur Google ${response.code}" + (detail?.let { " : $it" } ?: "")
                        }
                    )
                }
                read(response)
            }
        } catch (e: IOException) {
            throw GoogleException("Pas de connexion à Google.")
        }
    }

    private suspend fun getJson(url: String): JsonObject = call(Request.Builder().url(url)) { json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject }

    // ── Gmail ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    suspend fun mailList(query: String, unreadOnly: Boolean, max: Int): List<MailSummary> {
        val url = "$GMAIL/messages".toHttpUrl().newBuilder().addQueryParameter("q", gmailQuery(query, unreadOnly)).addQueryParameter("maxResults", max.coerceIn(1, 15).toString()).build()
        val ids = (getJson(url.toString())["messages"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
        return ids.map { id ->
            val meta = "$GMAIL/messages/$id".toHttpUrl().newBuilder().addQueryParameter("format", "metadata")
                .addQueryParameter("metadataHeaders", "From").addQueryParameter("metadataHeaders", "Subject").addQueryParameter("metadataHeaders", "Date").build()
            parseMailSummary(getJson(meta.toString()))
        }
    }

    /** One message: its headers and its text. */
    suspend fun mailRead(id: String): Pair<MailSummary, String> {
        val url = "$GMAIL/messages/${id.trim()}".toHttpUrl().newBuilder().addQueryParameter("format", "full").build()
        val message = getJson(url.toString())
        return parseMailSummary(message) to extractBody(message)
    }

    /** Creates a draft in Gmail. Nothing is sent: the user reads it and sends it from Gmail. */
    suspend fun mailDraft(to: String, subject: String, body: String): String {
        val payload = buildJsonObject { putJsonObject("message") { put("raw", buildRawMessage(to, subject, body)) } }
        val request = Request.Builder().url("$GMAIL/drafts").post(payload.toString().toRequestBody("application/json".toMediaType()))
        return call(request) { json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    }

    /** Really sends the mail — only called by GmailTool when the user has switched "Envoyer les mails sans confirmation" on and just asked for it. */
    suspend fun mailSend(to: String, subject: String, body: String): String {
        val payload = buildJsonObject { put("raw", buildRawMessage(to, subject, body)) }
        val request = Request.Builder().url("$GMAIL/messages/send").post(payload.toString().toRequestBody("application/json".toMediaType()))
        return call(request) { json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    }

    // ── Sorting the mail (see MailCleanupTool) ──────────────────────────────────────────────────────────────────────────

    /** The ids of up to [max] mails matching [query], newest first; [withSpam]: the spam and the bin are searched too. */
    suspend fun mailIds(query: String, max: Int, withSpam: Boolean = false): List<String> {
        val out = ArrayList<String>()
        var page: String? = null
        do {
            val url = "$GMAIL/messages".toHttpUrl().newBuilder().addQueryParameter("q", query)
                .addQueryParameter("maxResults", minOf(100, max - out.size).toString())
                .apply { if (withSpam) addQueryParameter("includeSpamTrash", "true"); page?.let { addQueryParameter("pageToken", it) } }.build()
            val o = getJson(url.toString())
            out += (o["messages"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            page = o["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (page != null && out.size < max)
        return out.take(max)
    }

    /** The headers sorting needs (sender, list headers, Gmail's authentication results) and the labels of mail [id]. */
    suspend fun mailHeaders(id: String): MailHeaders {
        val url = "$GMAIL/messages/${id.trim()}".toHttpUrl().newBuilder().addQueryParameter("format", "metadata").apply {
            listOf("From", "Subject", "Date", "List-Unsubscribe", "List-Unsubscribe-Post", "Authentication-Results").forEach { addQueryParameter("metadataHeaders", it) }
        }.build()
        val o = getJson(url.toString())
        val headers = (o["payload"]?.jsonObject?.get("headers") as? JsonArray).orEmpty().mapNotNull { h ->
            val name = h.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            name.lowercase() to h.jsonObject["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }
        fun first(n: String) = headers.firstOrNull { it.first == n }?.second
        return MailHeaders(
            id = o["id"]?.jsonPrimitive?.contentOrNull ?: id,
            from = first("from").orEmpty(), subject = first("subject").orEmpty(), date = first("date").orEmpty(),
            listUnsubscribe = first("list-unsubscribe"), listUnsubscribePost = first("list-unsubscribe-post"),
            authResults = headers.filter { it.first == "authentication-results" }.map { it.second },
            labels = (o["labelIds"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
        )
    }

    /** Moves mails [ids] to the spam (out of the inbox), as Gmail's « Signaler comme spam » does. */
    suspend fun mailToSpam(ids: List<String>) {
        if (ids.isEmpty()) return
        val payload = buildJsonObject {
            put("ids", JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("addLabelIds", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("SPAM"))))
            put("removeLabelIds", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("INBOX"))))
        }
        call(Request.Builder().url("$GMAIL/messages/batchModify").post(payload.toString().toRequestBody("application/json".toMediaType())), cleanup = true) {}
    }

    /** Moves mail [id] to the bin (Gmail empties it after 30 days; until then it can be taken back). */
    suspend fun mailToTrash(id: String) {
        call(Request.Builder().url("$GMAIL/messages/${id.trim()}/trash").post(ByteArray(0).toRequestBody(null)), cleanup = true) {}
    }

    /** A filter: the next mails from [from] skip the inbox and go to the bin. */
    suspend fun filterToTrash(from: String): String {
        val payload = buildJsonObject {
            putJsonObject("criteria") { put("from", from) }
            putJsonObject("action") {
                put("addLabelIds", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("TRASH"))))
                put("removeLabelIds", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("INBOX"))))
            }
        }
        val request = Request.Builder().url("$GMAIL/settings/filters").post(payload.toString().toRequestBody("application/json".toMediaType()))
        return call(request, cleanup = true) { json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    }

    // ── Drive ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    data class DriveFile(val id: String, val name: String, val mime: String, val modified: String, val size: Long)

    private fun parseDriveFile(o: JsonObject) = DriveFile(
        o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        o["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["modifiedTime"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        o["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
    )

    suspend fun driveSearch(text: String, max: Int): List<DriveFile> {
        val url = "$DRIVE/files".toHttpUrl().newBuilder().addQueryParameter("q", driveQuery(text)).addQueryParameter("pageSize", max.coerceIn(1, 20).toString())
            .addQueryParameter("orderBy", "modifiedTime desc").addQueryParameter("fields", "files(id,name,mimeType,modifiedTime,size)").build()
        return (getJson(url.toString())["files"] as? JsonArray).orEmpty().map { parseDriveFile(it.jsonObject) }
    }

    suspend fun driveInfo(id: String): DriveFile {
        val url = "$DRIVE/files/${id.trim()}".toHttpUrl().newBuilder().addQueryParameter("fields", "id,name,mimeType,modifiedTime,size").build()
        return parseDriveFile(getJson(url.toString()))
    }

    suspend fun driveExport(id: String, mime: String): String {
        val url = "$DRIVE/files/${id.trim()}/export".toHttpUrl().newBuilder().addQueryParameter("mimeType", mime).build()
        return call(Request.Builder().url(url)) { it.body?.string().orEmpty() }
    }

    suspend fun driveDownload(id: String): ByteArray {
        val url = "$DRIVE/files/${id.trim()}".toHttpUrl().newBuilder().addQueryParameter("alt", "media").build()
        return call(Request.Builder().url(url)) { response ->
            val body = response.body ?: return@call ByteArray(0)
            val declared = body.contentLength()
            if (declared > MAX_DRIVE_BYTES) throw GoogleException("Fichier trop volumineux (15 Mo maximum).")
            body.bytes().also { if (it.size > MAX_DRIVE_BYTES) throw GoogleException("Fichier trop volumineux (15 Mo maximum).") }
        }
    }

    /** Uploads [bytes] as a new file in the user's Drive (only files created by Jarvis are visible to it with this permission). */
    suspend fun driveUpload(name: String, mime: String, bytes: ByteArray): DriveFile {
        val meta = buildJsonObject { put("name", name) }.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta).addPart(bytes.toRequestBody(mime.toMediaType())).build()
        val url = "https://www.googleapis.com/upload/drive/v3/files".toHttpUrl().newBuilder().addQueryParameter("uploadType", "multipart").addQueryParameter("fields", "id,name,mimeType,modifiedTime,size").build()
        return call(Request.Builder().url(url).post(body)) { parseDriveFile(json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject) }
    }
}
