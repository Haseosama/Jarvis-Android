package com.jarvis.android.video

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Where each video was left, for "reprends la vidéo d'hier" and for starting a video again where it stopped: the last
 * [MAX_KEPT] videos not watched to their end, newest first. Kept on the phone only; [save] writes it where [store] says.
 */
class VideoHistory(private val load: () -> String? = { null }, private val store: (String) -> Unit = {}) {
    @Serializable
    data class Entry(
        val key: String,
        val youtubeId: String? = null,
        val url: String? = null,
        val title: String = "",
        val positionS: Int = 0,
        val durationS: Int = 0,
        /** When it was last watched, in milliseconds since 1970. */
        val at: Long = 0L,
    ) {
        fun video(): VideoPanel.Video = VideoPanel.Video(youtubeId = youtubeId, url = url, title = title, startAt = positionS)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var entries: List<Entry> = try {
        load()?.let { json.decodeFromString<List<Entry>>(it) } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
    private var lastSaved = 0L

    @Synchronized
    fun all(): List<Entry> = entries

    /**
     * [video] is at [positionS] of [durationS] seconds, at [now]. A video near its start (nothing to come back to) or near its end
     * (watched) is forgotten; the others are kept, newest first. Written out at most every [SAVE_EVERY_MS] unless [force].
     */
    @Synchronized
    fun record(video: VideoPanel.Video, positionS: Int, durationS: Int, now: Long, force: Boolean = false) {
        val key = video.key ?: return
        val rest = entries.filter { it.key != key }
        val finished = durationS > 0 && positionS >= durationS - END_MARGIN_S
        entries = if (positionS < MIN_POSITION_S || finished) rest
        else (listOf(Entry(key, video.youtubeId, video.url, video.title, positionS, durationS, now)) + rest).take(MAX_KEPT)
        if (force || now - lastSaved >= SAVE_EVERY_MS) save(now)
    }

    /** Where [video] was left, in seconds (0: from the start). */
    @Synchronized
    fun resumeAt(video: VideoPanel.Video): Int = video.key?.let { k -> entries.firstOrNull { it.key == k }?.positionS } ?: 0

    /** The video last left before its end, if any. */
    @Synchronized
    fun latest(): Entry? = entries.firstOrNull()

    @Synchronized
    fun save(now: Long = System.currentTimeMillis()) {
        lastSaved = now
        try { store(json.encodeToString(entries)) } catch (_: Exception) {}
    }

    companion object {
        const val MAX_KEPT = 30
        const val MIN_POSITION_S = 15
        const val END_MARGIN_S = 20
        const val SAVE_EVERY_MS = 15_000L
    }
}
