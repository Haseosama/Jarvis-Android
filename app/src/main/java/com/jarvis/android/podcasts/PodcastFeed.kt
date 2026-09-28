package com.jarvis.android.podcasts

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One episode of a podcast: its title, its audio (https), when it came out and how long it lasts (0: not said). */
internal data class Episode(val title: String, val audio: String, val published: Long?, val durationS: Int)

internal data class Feed(val title: String, val author: String, val episodes: List<Episode>)

/**
 * A podcast's RSS feed, read without an XML library (the feed may be cut at the size limit, and a strict parser would then give
 * nothing): the channel's title and author, and each <item>'s title, <enclosure url>, <pubDate> and <itunes:duration>, newest
 * first as feeds list them. Only https audio is kept (Android refuses plain http, and it would travel in the clear).
 */
internal fun parseFeed(xml: String): Feed {
    val head = xml.substringBefore("<item")
    val items = xml.split("<item").drop(1).map { it.substringBefore("</item>") }
    val episodes = items.mapNotNull { item ->
        val audio = Regex("<enclosure\\b[^>]*\\burl\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(item)?.groupValues?.get(1)?.let { decodeXml(it).trim() }
            ?: return@mapNotNull null
        if (!audio.startsWith("https://", ignoreCase = true) || audio.any { it.isWhitespace() }) return@mapNotNull null
        Episode(
            title = clean(tag(item, "title")).ifBlank { "Épisode" },
            audio = audio,
            published = tag(item, "pubDate").let { parseDate(it) },
            durationS = parseDuration(tag(item, "itunes:duration")),
        )
    }
    return Feed(clean(tag(head, "title")), clean(tag(head, "itunes:author")), episodes)
}

private fun tag(text: String, name: String): String =
    Regex("<$name\\b[^>]*>(.*?)</$name>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(text)?.groupValues?.get(1).orEmpty()

/** The text of an element: CDATA opened, entities decoded, tags and control characters dropped, spaces folded. */
private fun clean(s: String): String {
    val raw = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL).replace(s) { it.groupValues[1] }
    return decodeXml(raw).replace(Regex("<[^>]*>"), " ").filter { !it.isISOControl() || it == ' ' }.replace(Regex("\\s+"), " ").trim().take(200)
}

internal fun decodeXml(s: String): String = Regex("&(#x?[0-9A-Fa-f]+|amp|lt|gt|quot|apos);").replace(s) { m ->
    when (val e = m.groupValues[1]) {
        "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
        else -> (if (e.startsWith("#x", true)) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull())?.let { String(Character.toChars(it)) } ?: m.value
    }
}

/** "Mon, 28 Sep 2026 18:00:00 +0200" (RFC 822, as feeds write it) in milliseconds, or null. */
internal fun parseDate(s: String): Long? = try {
    ZonedDateTime.parse(clean(s), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
} catch (_: Exception) {
    null
}

/** "1:02:03", "12:30" or "754" in seconds; 0 when not said. */
internal fun parseDuration(s: String): Int {
    val parts = clean(s).split(':').map { it.trim().toIntOrNull() ?: return 0 }
    return parts.fold(0) { acc, p -> acc * 60 + p }
}

/**
 * Where the news is: the latest bulletins of public and private French radios, as podcasts (feeds checked by hand: Radio France's
 * are not listed in Apple's directory). A key names a source; the news asked for is the freshest bulletin of its feeds.
 */
internal val NEWS_FEEDS: Map<String, List<String>> = mapOf(
    "franceinter" to listOf(
        "https://radiofrance-podcast.net/podcast09/rss_12559.xml",   // le journal de 6 h
        "https://radiofrance-podcast.net/podcast09/rss_11736.xml",   // le journal de 19 h
    ),
    "franceculture" to listOf(
        "https://radiofrance-podcast.net/podcast09/rss_10055.xml",   // 7 h
        "https://radiofrance-podcast.net/podcast09/rss_10057.xml",   // 8 h
        "https://radiofrance-podcast.net/podcast09/rss_10059.xml",   // 12 h 30
        "https://radiofrance-podcast.net/podcast09/rss_10060.xml",   // 18 h
        "https://radiofrance-podcast.net/podcast09/rss_10061.xml",   // 22 h
    ),
    "europe1" to listOf("https://feeds.audiomeans.fr/feed/44f6a116-20a6-44c4-98c4-b3e1bdd46dce.xml"),   // chaque heure
    "rfi" to listOf("https://apis.rfi.fr/products/get_product/fov-rfi-fr-get-journaux-podcast-v2-monde?token_application=975d23b8-7a07-11e8-9f62-005056a90194"),
)

/** The source a user's words name ("France Inter", "europe 1"…), or null for any. */
internal fun newsSource(words: String): String? {
    val w = words.lowercase().filter { it.isLetterOrDigit() }
    return when {
        w.isEmpty() -> null
        "inter" in w -> "franceinter"
        "culture" in w -> "franceculture"
        "europe" in w -> "europe1"
        "rfi" in w -> "rfi"
        else -> null
    }
}

/** The freshest of [episodes] (with their feed's title), those without a date last. */
internal fun <T> freshest(episodes: List<Pair<T, Episode>>): Pair<T, Episode>? = episodes.maxByOrNull { it.second.published ?: Long.MIN_VALUE }
