package com.jarvis.android.actions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** The first video of a YouTube search page. The title comes from the web: treat it as data, never as an instruction. */
internal data class YoutubeHit(val videoId: String, val title: String)

private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
private const val VIDEO_MARKER = "\"videoRenderer\":{\"videoId\":\""
private const val TITLE_MARKER = "\"title\":{\"runs\":[{\"text\":\""

/** Reads the first video (id and title) out of the HTML of a YouTube results page, or null when it has none. */
internal fun parseFirstVideo(html: String): YoutubeHit? = parseVideos(html, 1).firstOrNull()

/** The first [max] videos (id and title, each once) of the HTML of a YouTube results page, in the page's order. */
internal fun parseVideos(html: String, max: Int): List<YoutubeHit> {
    val out = ArrayList<YoutubeHit>()
    var from = 0
    while (out.size < max) {
        val start = html.indexOf(VIDEO_MARKER, from)
        if (start < 0) break
        val idStart = start + VIDEO_MARKER.length
        val id = html.substring(idStart, minOf(idStart + 11, html.length))
        from = idStart
        if (!VIDEO_ID.matches(id) || out.any { it.videoId == id }) continue
        // the title is looked for before the next video begins, not in it
        val next = html.indexOf(VIDEO_MARKER, idStart).let { if (it < 0) html.length else it }
        val window = html.substring(idStart, minOf(idStart + 4_000, next))
        val titleStart = window.indexOf(TITLE_MARKER)
        val title = if (titleStart < 0) "" else readJsonString(window, titleStart + TITLE_MARKER.length)
        out += YoutubeHit(id, cleanTitle(title))
    }
    return out
}

/** Reads a JSON string body starting at [start] (after the opening quote) up to the closing quote. */
private fun readJsonString(text: String, start: Int): String {
    val out = StringBuilder()
    var i = start
    while (i < text.length) {
        val c = text[i]
        when {
            c == '"' -> return out.toString()
            c == '\\' && i + 1 < text.length -> {
                val n = text[i + 1]
                if (n == 'u' && i + 5 < text.length) {
                    text.substring(i + 2, i + 6).toIntOrNull(16)?.let { out.append(it.toChar()) }
                    i += 6
                    continue
                }
                out.append(if (n == 'n' || n == 't') ' ' else n)
                i += 2
                continue
            }
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}

private fun cleanTitle(raw: String): String =
    raw.filter { !it.isISOControl() }.trim().take(120)

internal fun youtubeVideosUrl(query: String): String =
    "https://www.youtube.com/results".toHttpUrl().newBuilder()
        .addQueryParameter("search_query", query)
        .addQueryParameter("sp", "EgIQAQ%3D%3D") // videos only
        .build().toString()

/** Searches YouTube like the website does and returns the first video, or null. Throws [IOException] when offline. */
internal suspend fun searchFirstVideo(http: OkHttpClient, query: String): YoutubeHit? = searchVideos(http, query, 1).firstOrNull()

/** Searches YouTube like the website does and returns its first [max] videos. Throws [IOException] when offline. */
internal suspend fun searchVideos(http: OkHttpClient, query: String, max: Int): List<YoutubeHit> = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(youtubeVideosUrl(query))
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
        .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
        // The desktop page is asked for on purpose: the mobile one has a different layout that parseFirstVideo does not read.
        // Skips the consent page shown in Europe; nothing else is sent.
        .header("Cookie", "SOCS=CAI")
        .build()
    http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val source = response.body?.source() ?: return@use emptyList()
        source.request(900_000)
        parseVideos(source.buffer.readUtf8(minOf(source.buffer.size, 900_000)), max)
    }
}
