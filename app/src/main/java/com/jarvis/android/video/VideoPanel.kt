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
        /** Where to start, in seconds (where it was left last time). */
        val startAt: Int = 0,
        /** YouTube's subtitles in this language (a code: "fr", "en"…), none when null. */
        val subtitles: String? = null,
        /** A radio station live ([url] is its stream): sound only, no place to come back to. */
        val radio: Boolean = false,
        /** A podcast's episode ([url] is its audio): sound only, [artist] is the podcast. */
        val podcast: Boolean = false,
        /** Who it is from (a podcast's name), shown under the title and on the lock screen. */
        val artist: String = "",
        /** Falling asleep to it: the screen dims and does not stay on, the sound fades out before the timer stops it. */
        val sleep: Boolean = false,
        /** The live sky instead of a video (space/SkyView.kt): "all", "satellites", "planes" or "pass:<name>". */
        val sky: String? = null,
    ) {
        /** Sound without a picture: a radio or a podcast. */
        val audioOnly: Boolean get() = radio || podcast

        val isSlideshow: Boolean get() = photos.isNotEmpty()

        /** What names it for "where was it left": its YouTube id or its address; none for a slideshow or a radio. */
        val key: String? get() = if (radio) null else youtubeId?.let { "yt:$it" } ?: url
    }

    /** When the video stops by itself: at a time ([at], on the panel's clock), or at the end of the one playing ([atEnd]). */
    data class Timer(val at: Long? = null, val atEnd: Boolean = false)

    /** What the player is asked to do (by voice, or its buttons), carried out by the one on screen. */
    sealed interface Command {
        data object Pause : Command
        data object Resume : Command
        data class SeekBy(val seconds: Int) : Command
        data object Restart : Command
        /** A slideshow: [by] photos further (back when negative). */
        data class Step(val by: Int) : Command
        /** YouTube's subtitles in [language], or none (null). */
        data class Subtitles(val language: String?) : Command
        /** A slideshow's photo seen [scale] times larger (1: whole), around the point [x], [y] (0 to 1 across and down). */
        data class Zoom(val scale: Float, val x: Float = 0.5f, val y: Float = 0.5f) : Command
    }

    private val _commands = kotlinx.coroutines.flow.MutableSharedFlow<Command>(extraBufferCapacity = 8)
    val commands: kotlinx.coroutines.flow.SharedFlow<Command> = _commands

    /** Called when a video appears (or the next one), when it goes (the avatar watches it, then comes back), and when the floor opens. */
    @Volatile var onShown: () -> Unit = {}
    @Volatile var onClosed: () -> Unit = {}
    @Volatile var onFloor: () -> Unit = {}

    /** Called every few seconds while a video plays: where it is and how long it is, in seconds (0: not known). */
    @Volatile var onProgress: (Video, Int, Int) -> Unit = { _, _, _ -> }

    /** Set by the screen while a player is shown: a picture of what it shows now, as a JPEG at most so many pixels on a side. */
    @Volatile var grabFrame: (suspend (Int) -> ByteArray?)? = null

    /** How much the slideshow's photo is zoomed now (1: whole), as the slideshow last said: "zoome encore" starts from it. */
    @Volatile var photoZoom: Float = 1f

    /** Where the video on screen is, in seconds, as its player last said. */
    @Volatile var positionS: Int = 0
        private set

    private val _timer = MutableStateFlow<Timer?>(null)
    val timer: StateFlow<Timer?> = _timer.asStateFlow()

    private val _video = MutableStateFlow<Video?>(null)
    val video: StateFlow<Video?> = _video.asStateFlow()

    // what was found, for "la suivante" / "la précédente"
    private var list: List<Video> = emptyList()
    private var at = 0

    @Volatile private var floorUntil = 0L

    /** True while a video plays with its sound on. */
    val soundOn: Boolean get() = _video.value?.let { it.sound && !it.paused } == true

    /**
     * The wake word the user can say over the sound ("« Hey Jarvis »"), or null when the offline wake word is not installed (then only
     * a tap on the small face, or a pause, gives the microphone back). Set by the container.
     */
    @Volatile var wakePhrase: () -> String? = { null }

    /** How the user talks over the sound, in words for them: the wake word if there is one, a tap on the face, or a pause. */
    fun talkOverWords(): String = wakePhrase()?.let {
        com.jarvis.android.i18n.trf("dites {0}, touchez le petit visage ou mettez en pause", it)
    } ?: com.jarvis.android.i18n.tr("touchez le petit visage ou mettez en pause (installez le mot d’activation hors ligne pour le faire à la voix)")

    fun show(v: Video) = showList(listOf(v), 0)

    /** Shows [videos][start] and keeps the others for [step]. Every video starts muted; full screen stays as it was. */
    @Synchronized
    fun showList(videos: List<Video>, start: Int = 0) {
        if (videos.isEmpty()) return
        val wasFull = _video.value?.fullscreen == true
        val subtitles = _video.value?.subtitles
        list = videos.mapIndexed { i, v -> v.copy(position = i + 1, count = videos.size) }
        at = start.coerceIn(list.indices)
        floorUntil = 0L
        positionS = list[at].startAt
        _video.value = list[at].let {
            it.copy(sound = false, paused = false, ducked = false, fullscreen = it.fullscreen || wasFull, subtitles = it.subtitles ?: subtitles)
        }
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
        positionS = list[at].startAt
        _video.value = list[at].copy(sound = cur.sound, fullscreen = cur.fullscreen, ducked = cur.ducked, subtitles = cur.subtitles)
        onShown()
        return true
    }

    /** Asks the player on screen to [c]; false when no video is shown. */
    fun command(c: Command): Boolean {
        if (_video.value == null) return false
        when (c) {
            Command.Pause -> _video.update { it?.copy(paused = true) }
            Command.Resume, Command.Restart -> _video.update { it?.copy(paused = false) }
            is Command.Subtitles -> _video.update { it?.copy(subtitles = c.language) }
            is Command.SeekBy, is Command.Step, is Command.Zoom -> {}
        }
        return _commands.tryEmit(c)
    }

    /** The player says where it is ([position] and [duration] in seconds, 0 when not known). */
    fun progress(position: Int, duration: Int) {
        val v = _video.value ?: return
        if (v.isSlideshow || v.radio) return
        positionS = position
        onProgress(v, position, duration)
    }

    /**
     * The video on screen came to its end: the panel closes if the timer asked for that, a video file goes on to the next one found
     * (or closes after the last), YouTube stays on its end screen.
     */
    fun ended() {
        val v = _video.value ?: return
        when {
            _timer.value?.atEnd == true -> { log(com.jarvis.android.i18n.tr("Minuterie : la vidéo est finie, elle se ferme.")); close() }
            v.url != null -> if (!step(1)) close()
        }
    }

    /** Stops the video in [minutes], or at the end of the one playing ([atEnd]); no timer when both are unset. False with no video. */
    fun setTimer(minutes: Int?, atEnd: Boolean = false): Boolean {
        if (_video.value == null) return false
        _timer.value = when {
            atEnd -> Timer(atEnd = true)
            minutes != null && minutes > 0 -> Timer(at = clock() + minutes * 60_000L)
            else -> null
        }
        return true
    }

    /** How long before the timer stops the video, in milliseconds; null with no timer at a time. */
    fun timerLeftMs(): Long? = _timer.value?.at?.let { maxOf(0L, it - clock()) }

    /** Closes the video when its timer is up; true when it did. */
    fun checkTimer(): Boolean {
        val at = _timer.value?.at ?: return false
        if (clock() < at) return false
        log(com.jarvis.android.i18n.tr("Minuterie : la vidéo s’arrête."))
        close()
        return true
    }

    fun setSound(on: Boolean): Boolean {
        val v = _video.value ?: return false
        if (v.isSlideshow) return false
        if (v.sound == on) return true
        if (!on) floorUntil = 0L
        _video.update { it?.copy(sound = on, ducked = false) }
        log(if (on) com.jarvis.android.i18n.trf("Son activé : pour parler à Jarvis pendant ce temps, {0}.", talkOverWords())
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
        // nothing to answer by mistake: no sound, or the sound paused
        if (v?.sound != true || v.paused) return true
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
        _timer.value = null
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
