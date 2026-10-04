package com.jarvis.android.media

import android.content.Context

/** One entry of the car's list: a folder ([playable] false) or something to play. */
internal data class CarNode(val id: String, val title: String, val subtitle: String = "", val playable: Boolean = true)

/**
 * What played lately, kept on the phone (the "car" preferences): the stations, for the car's list, and the last thing played, for the
 * phone's « play again » after a restart.
 */
internal object PlayedLately {
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
}
