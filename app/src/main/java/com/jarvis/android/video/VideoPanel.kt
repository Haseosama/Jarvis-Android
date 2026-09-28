package com.jarvis.android.video

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A video shown where the avatar is (after Mark LV's video player): a YouTube video, played by YouTube's own embedded player, or a
 * video file on the web. It starts without sound, since a soundtrack over the assistant's voice is the one way this could make things
 * worse, and while its sound is on the microphone is silenced, so the assistant does not answer the film.
 */
class VideoPanel(private val log: (String) -> Unit = {}) {
    /** What is shown: a YouTube video (by its id) or a video file (by its address), with its title. */
    data class Video(val youtubeId: String? = null, val url: String? = null, val title: String = "", val sound: Boolean = false, val paused: Boolean = false)

    /** What the player is asked to do (by voice, or its buttons), carried out by the one on screen. */
    sealed interface Command {
        data object Pause : Command
        data object Resume : Command
        data class SeekBy(val seconds: Int) : Command
        data object Restart : Command
    }

    private val _commands = kotlinx.coroutines.flow.MutableSharedFlow<Command>(extraBufferCapacity = 8)
    val commands: kotlinx.coroutines.flow.SharedFlow<Command> = _commands

    /** Called when a video appears and when it goes (the avatar watches it, then comes back). */
    @Volatile var onShown: () -> Unit = {}
    @Volatile var onClosed: () -> Unit = {}

    private val _video = MutableStateFlow<Video?>(null)
    val video: StateFlow<Video?> = _video.asStateFlow()

    /** True while a video plays with its sound on: the voice session sends silence instead of the microphone. */
    val soundOn: Boolean get() = _video.value?.sound == true

    fun show(v: Video) {
        _video.value = v.copy(sound = false, paused = false)
        onShown()
    }

    /** Asks the player on screen to [c]; false when no video is shown. */
    fun command(c: Command): Boolean {
        if (_video.value == null) return false
        when (c) {
            Command.Pause -> _video.update { it?.copy(paused = true) }
            Command.Resume, Command.Restart -> _video.update { it?.copy(paused = false) }
            is Command.SeekBy -> {}
        }
        return _commands.tryEmit(c)
    }

    fun setSound(on: Boolean): Boolean {
        val v = _video.value ?: return false
        if (v.sound == on) return true
        _video.update { it?.copy(sound = on) }
        log(if (on) com.jarvis.android.i18n.tr("Son de la vidéo activé : le micro est coupé tant qu’il reste allumé.")
        else com.jarvis.android.i18n.tr("Son de la vidéo coupé : le micro écoute de nouveau."))
        return true
    }

    fun close(): Boolean {
        val had = _video.value != null
        _video.value = null
        if (had) onClosed()
        return had
    }

    companion object {
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
