package com.jarvis.android.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A radio station of the Radio Browser directory (a free, open list of stations and their streams). */
internal data class RadioStation(val name: String, val stream: String, val tags: String, val country: String, val codec: String, val bitrate: Int)

/**
 * The stations of a Radio Browser answer, the most listened first as asked: only those streamed over https (a plain http stream is
 * refused by Android, and would travel in the clear), each stream once, the name cleaned (it comes from the web).
 */
internal fun parseStations(json: String): List<RadioStation> {
    val list = try { Json.parseToJsonElement(json) as? JsonArray } catch (_: Exception) { null } ?: return emptyList()
    return list.mapNotNull { e ->
        val o = e.jsonObject
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val stream = s("url_resolved").ifEmpty { s("url") }
        if (!stream.startsWith("https://", ignoreCase = true) || stream.any { it.isWhitespace() }) return@mapNotNull null
        val name = s("name").filter { !it.isISOControl() }.take(80).ifBlank { return@mapNotNull null }
        RadioStation(name, stream, s("tags").take(120), s("countrycode"), s("codec"), o["bitrate"]?.jsonPrimitive?.intOrNull ?: 0)
    }.distinctBy { it.stream }
}

private val SERVERS = listOf("de1.api.radio-browser.info", "nl1.api.radio-browser.info", "at1.api.radio-browser.info")

/**
 * The stations for [query]: by name in [country], else by genre (the exact tag: "rain" is not "Bahrain") there, else by name and
 * by genre everywhere. Throws [IOException] when the directory does not answer.
 */
internal suspend fun findStations(http: OkHttpClient, query: String, country: String?): List<RadioStation> =
    search(http, "name", query, country)
        .ifEmpty { search(http, "tag", query.lowercase(), country) }
        .ifEmpty { if (country != null) search(http, "name", query, null) else emptyList() }
        .ifEmpty { if (country != null) search(http, "tag", query.lowercase(), null) else emptyList() }

/** Stations whose [by] (name or tag) matches [value], the most listened first, from the first server that answers. */
private suspend fun search(http: OkHttpClient, by: String, value: String, country: String?): List<RadioStation> = withContext(Dispatchers.IO) {
    var last: IOException? = null
    for (host in SERVERS) {
        val url = "https://$host/json/stations/search".toHttpUrl().newBuilder()
            .addQueryParameter(by, value).addQueryParameter("limit", "20").addQueryParameter("hidebroken", "true")
            .addQueryParameter("order", "clickcount").addQueryParameter("reverse", "true")
            .apply { if (by == "tag") addQueryParameter("tagExact", "true") }
            .apply { country?.let { addQueryParameter("countrycode", it) } }.build()
        try {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-Android").build()).execute().use { r ->
                if (r.isSuccessful) return@withContext parseStations(r.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            last = e
        }
    }
    throw last ?: IOException("no server")
}
