package com.jarvis.android.video

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A video shown where the avatar is (after Mark LV's video player): a YouTube video, played by YouTube's own embedded player, a
 * video file (on the web or on the phone), or photos of the phone one after the other. It starts without sound, since a
 * soundtrack over the assistant's voice is the one way this could make things worse. While its sound is on, the microphone is
 * silenced, so the assistant does not answer the film, except while the floor is open ([openFloor]: the wake word, or a tap on
 * the small face): the sound is then turned down and the user talks to the assistant over it.
 *
 * [clock] gives milliseconds (the tests set it).
 */
class VideoPanel(private val log: (String) -> Unit = {}, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    /**
     * What is shown: a YouTube video (by its id), a video file (by its address) or a slideshow ([photos], their addresses), with
     * its title. [position] of [count] is its place among what was found, for "la suivante".
     */
    data class Video(
        val youtubeId: String? = null,
        val url: String? = null,
        val title: String = "",
        val sound: Boolean = false,
        val paused: Boolean = false,
        val photos: List<String> = emptyList(),
        val fullscreen: Boolean = false,
        val position: Int = 1,
        val count: Int = 1,
        /** The sound turned down while the user talks over it. */
        val ducked: Boolean = false,
    ) {
        val isSlideshow: Boolean get() = photos.isNotEmpty()
    }

    /** What the player is asked to do (by voice, or its buttons), carried out by the one on screen. */
    sealed interface Command {
        data object Pause : Command
        data object Resume : Command
        data class SeekBy(val seconds: Int) : Command
        data object Restart : Command
        /** A slideshow: [by] photos further (back when negative). */
        data class Step(val by: Int) : Command
    }

    private val _commands = kotlinx.coroutines.flow.MutableSharedFlow<Command>(extraBufferCapacity = 8)
    val commands: kotlinx.coroutines.flow.SharedFlow<Command> = _commands

    /** Called when a video appears (or the next one), when it goes (the avatar watches it, then comes back), and when the floor opens. */
    @Volatile var onShown: () -> Unit = {}
    @Volatile var onClosed: () -> Unit = {}
    @Volatile var onFloor: () -> Unit = {}

    private val _video = MutableStateFlow<Video?>(null)
    val video: StateFlow<Video?> = _video.asStateFlow()

    // what was found, for "la suivante" / "la précédente"
    private var list: List<Video> = emptyList()
    private var at = 0

    @Volatile private var floorUntil = 0L

    /** True while a video plays with its sound on. */
    val soundOn: Boolean get() = _video.value?.sound == true

    fun show(v: Video) = showList(listOf(v), 0)

    /** Shows [videos][start] and keeps the others for [step]. Every video starts muted; full screen stays as it was. */
    @Synchronized
    fun showList(videos: List<Video>, start: Int = 0) {
        if (videos.isEmpty()) return
        val wasFull = _video.value?.fullscreen == true
        list = videos.mapIndexed { i, v -> v.copy(position = i + 1, count = videos.size) }
        at = start.coerceIn(list.indices)
        floorUntil = 0L
        _video.value = list[at].let { it.copy(sound = false, paused = false, ducked = false, fullscreen = it.fullscreen || wasFull) }
        onShown()
    }

    /**
     * The next ([by] 1) or previous (-1) of what was found, with the same sound and screen; a slideshow goes to its next photo. False
     * when there is none (or no video).
     */
    @Synchronized
    fun step(by: Int): Boolean {
        val cur = _video.value ?: return false
        if (cur.isSlideshow) return command(Command.Step(by))
        val next = at + by
        if (next !in list.indices) return false
        at = next
        _video.value = list[at].copy(sound = cur.sound, fullscreen = cur.fullscreen, ducked = cur.ducked)
        onShown()
        return true
    }

    /** Asks the player on screen to [c]; false when no video is shown. */
    fun command(c: Command): Boolean {
        if (_video.value == null) return false
        when (c) {
            Command.Pause -> _video.update { it?.copy(paused = true) }
            Command.Resume, Command.Restart -> _video.update { it?.copy(paused = false) }
            is Command.SeekBy, is Command.Step -> {}
        }
        return _commands.tryEmit(c)
    }

    fun setSound(on: Boolean): Boolean {
        val v = _video.value ?: return false
        if (v.isSlideshow) return false
        if (v.sound == on) return true
        if (!on) floorUntil = 0L
        _video.update { it?.copy(sound = on, ducked = false) }
        log(if (on) com.jarvis.android.i18n.tr("Son de la vidéo activé : le micro n’écoute plus que « Jarvis » (ou touchez le petit visage) tant qu’il reste allumé.")
        else com.jarvis.android.i18n.tr("Son de la vidéo coupé : le micro écoute de nouveau."))
        return true
    }

    fun setFullscreen(on: Boolean): Boolean {
        if (_video.value == null) return false
        _video.update { it?.copy(fullscreen = on) }
        return true
    }

    /**
     * The user wants to talk over the video's sound (the wake word was heard, or the small face tapped): for [forMs] the microphone
     * reaches the assistant and the sound is turned down. False when there is no sound to talk over.
     */
    fun openFloor(forMs: Long = FLOOR_OPEN_MS): Boolean {
        if (!soundOn) return false
        floorUntil = maxOf(floorUntil, clock() + forMs)
        _video.update { it?.copy(ducked = true) }
        onFloor()
        return true
    }

    /** Something is still being said (by either side): the floor stays open [forMs] longer, if it is open. */
    fun keepFloor(forMs: Long = FLOOR_KEEP_MS) {
        val now = clock()
        if (now < floorUntil) floorUntil = maxOf(floorUntil, now + forMs)
    }

    /**
     * Whether the microphone may reach the assistant: always, but while the video's sound is on only while the floor is open. Asked
     * for every piece of sound: the floor that has run out closes here, and the sound comes back up.
     */
    fun micOpen(): Boolean {
        val v = _video.value
        if (v?.sound != true) return true
        val open = clock() < floorUntil
        if (!open && v.ducked) _video.update { it?.copy(ducked = false) }
        return open
    }

    @Synchronized
    fun close(): Boolean {
        val had = _video.value != null
        _video.value = null
        list = emptyList()
        floorUntil = 0L
        if (had) onClosed()
        return had
    }

    companion object {
        /** Time to start talking once the floor opens, and how long it stays open after the last word. */
        const val FLOOR_OPEN_MS = 8_000L
        const val FLOOR_KEEP_MS = 4_000L

        private val ID = Regex("^[A-Za-z0-9_-]{11}$")

        /** The id of a YouTube video in a link (watch?v=, youtu.be/, /shorts/, /embed/, /live/), or null. */
        fun youtubeId(link: String): String? {
            val u = try { java.net.URI(link.trim()) } catch (_: Exception) { return null }
            val host = u.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
            val path = u.path.orEmpty()
            val id = when {
                host == "youtu.be" -> path.trim('/').substringBefore('/')
                host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") -> when {
                    path == "/watch" -> u.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
                    listOf("/shorts/", "/embed/", "/live/", "/v/").any { path.startsWith(it) } -> path.split('/').getOrNull(2)
                    else -> null
                }
                else -> null
            }
            return id?.takeIf { ID.matches(it) }
        }

        /** Whether a link is a video file the phone's player can take (by its extension, or a stream playlist). */
        fun isVideoFile(link: String): Boolean {
            val u = try { java.net.URI(link.trim()) } catch (_: Exception) { return false }
            if (u.scheme?.lowercase() !in setOf("http", "https")) return false
            val p = u.path.orEmpty().lowercase()
            return listOf(".mp4", ".webm", ".m4v", ".mkv", ".3gp", ".m3u8", ".mov").any { p.endsWith(it) }
        }
    }
}
