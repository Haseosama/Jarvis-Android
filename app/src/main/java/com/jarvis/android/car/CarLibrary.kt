package com.jarvis.android.car

import android.content.Context
import com.jarvis.android.JarvisContainer
import com.jarvis.android.podcasts.PodcastSubscriptions
import com.jarvis.android.video.VideoHistory
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One entry of the car's list: a folder ([playable] false) or something to play. */
internal data class CarNode(val id: String, val title: String, val subtitle: String = "", val playable: Boolean = true)

/**
 * What Jarvis shows in the car (Android Auto) and plays from it: the news, radios (the ones played lately, then the best-known French
 * stations), the podcasts the user follows (their latest episode), and the episodes left on the way. Sound only: a car shows no video.
 */
internal object CarLibrary {
    const val ROOT = "root"
    const val NEWS = "news"
    const val RADIOS = "radios"
    const val PODCASTS = "podcasts"
    const val RESUME = "resume"

    /** The root asked for by the phone's media controls, to offer playing again what played last. */
    const val RECENT = "recent"

    /** Well-known French stations, found again by their name when chosen. */
    val STATIONS = listOf(
        "FIP", "France Inter", "franceinfo", "France Culture", "France Musique", "RTL", "Europe 1", "RMC", "Nostalgie", "NRJ",
        "RFM", "Chérie FM", "Radio Classique", "TSF Jazz", "Skyrock", "Fun Radio", "RTL2", "Virgin Radio",
    )

    private val NEWS_SOURCES = listOf("" to "Le plus récent", "franceinter" to "France Inter", "franceculture" to "France Culture", "europe1" to "Europe 1", "rfi" to "RFI")

    /** The entries under [parent], from what the phone keeps: stations played lately, podcasts followed, episodes left on the way. */
    fun children(parent: String, recentRadios: List<String>, subs: List<PodcastSubscriptions.Sub>, left: List<VideoHistory.Entry>): List<CarNode> = when (parent) {
        ROOT -> buildList {
            add(CarNode(NEWS, "Infos", "Le dernier journal", playable = false))
            add(CarNode(RADIOS, "Radios", "En direct", playable = false))
            if (subs.isNotEmpty()) add(CarNode(PODCASTS, "Mes podcasts", "${subs.size} suivi(s)", playable = false))
            if (left.any { it.audio }) add(CarNode(RESUME, "Reprendre", "Là où vous vous étiez arrêté", playable = false))
        }
        NEWS -> NEWS_SOURCES.map { (key, title) -> CarNode("news:$key", title, "Journal") }
        RADIOS -> (recentRadios + STATIONS).distinctBy { it.lowercase() }.map { CarNode("radio:$it", it, if (it in recentRadios) "Écoutée récemment" else "Radio") }
        PODCASTS -> subs.map { CarNode("podcast:${it.feed}", it.title, "Dernier épisode · ${it.author}") }
        RESUME -> left.filter { it.audio && it.url != null }.take(12).map { CarNode("resume:${it.key}", it.title, it.artist.ifBlank { "Podcast" }) }
        else -> emptyList()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("car", Context.MODE_PRIVATE)

    /** The stations played lately, newest first (for the car's list). */
    fun recentRadios(context: Context): List<String> = prefs(context).getString("radios", "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun rememberRadio(context: Context, name: String) {
        val list = (listOf(name) + recentRadios(context).filter { !it.equals(name, ignoreCase = true) }).take(8)
        prefs(context).edit().putString("radios", list.joinToString("\n")).apply()
        rememberLast(context, CarNode("radio:$name", name, "Radio"))
    }

    /** What played last (a station, an episode), for the phone's « play again » after a restart. */
    fun rememberLast(context: Context, node: CarNode) {
        prefs(context).edit().putString("last_id", node.id).putString("last_title", node.title).putString("last_subtitle", node.subtitle).apply()
    }

    fun last(context: Context): CarNode? {
        val p = prefs(context)
        val id = p.getString("last_id", null) ?: return null
        return CarNode(id, p.getString("last_title", "").orEmpty(), p.getString("last_subtitle", "").orEmpty())
    }

    /** An episode's media id: its audio, title and podcast (no list to look it up in). */
    fun episodeId(url: String, title: String, artist: String) = "episode:" + listOf(url, title, artist).joinToString("\u0001")

    /** Plays an entry of the car's list ([mediaId]) or what was asked for aloud ([query], through an assistant): with its sound. */
    suspend fun play(ctx: JarvisContainer, mediaId: String?, query: String?) {
        fun args(vararg kv: Pair<String, String>) = buildJsonObject { kv.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
        when {
            mediaId?.startsWith("news:") == true -> com.jarvis.android.podcasts.PodcastTool.run(args("action" to "news", "source" to mediaId.removePrefix("news:")), ctx)
            mediaId?.startsWith("radio:") == true -> com.jarvis.android.actions.RadioTool.run(args("action" to "play", "query" to mediaId.removePrefix("radio:")), ctx)
            mediaId?.startsWith("podcast:") == true -> com.jarvis.android.podcasts.PodcastTool.playFeed(ctx, mediaId.removePrefix("podcast:"))
            mediaId?.startsWith("episode:") == true -> mediaId.removePrefix("episode:").split('\u0001').let { p ->
                val v = com.jarvis.android.video.VideoPanel.Video(url = p[0], title = p.getOrElse(1) { "" }, artist = p.getOrElse(2) { "" }, podcast = true)
                ctx.videoPanel.show(v.copy(startAt = ctx.videoHistory.resumeAt(v)))
                ctx.videoPanel.setSound(true)
            }
            mediaId?.startsWith("resume:") == true -> ctx.videoHistory.all().firstOrNull { it.key == mediaId.removePrefix("resume:") }?.let {
                ctx.videoPanel.show(it.video())
                ctx.videoPanel.setSound(true)
            }
            // "joue FIP sur Jarvis": a station of that name, else a podcast; nothing said: the news
            else -> {
                val q = query.orEmpty().trim()
                if (q.isEmpty()) {
                    com.jarvis.android.podcasts.PodcastTool.run(args("action" to "news"), ctx)
                } else {
                    val station = try { com.jarvis.android.actions.RadioTool.findStations(ctx.http, q, "FR").firstOrNull() } catch (_: Exception) { null }
                    if (station != null && station.name.contains(q, ignoreCase = true)) com.jarvis.android.actions.RadioTool.run(args("action" to "play", "query" to q), ctx)
                    else com.jarvis.android.podcasts.PodcastTool.run(args("action" to "play", "query" to q), ctx)
                }
            }
        }
    }
}
