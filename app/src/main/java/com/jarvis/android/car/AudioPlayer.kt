package com.jarvis.android.car

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.jarvis.android.video.VideoPanel
import com.jarvis.android.video.volumeOf

/**
 * Sound without a picture (a radio, a podcast, the news) played outside the screen: the video panel says what, this player plays it,
 * inside the media service (JarvisMediaService.kt, in the foreground while it plays), so it goes on with the screen off, the app
 * closed, and in the car with the phone locked. The panel still shows its card; its commands (pause, 30 s on, the start again), its
 * volume (the sound on or off, turned down while the user talks), its end and the sleep timer's fading all come through here.
 */
internal class AudioPlayer(private val context: Context, private val panel: VideoPanel, scope: CoroutineScope) {
    private var player: MediaPlayer? = null
    private var url: String? = null
    private var prepared = false

    init {
        scope.launch(Dispatchers.Main) {
            panel.video.collect { v ->
                if (v == null || !v.audioOnly || v.url == null) { stop(); return@collect }
                if (v.url != url) load(v)
                applyVolume()
                val mp = player ?: return@collect
                if (prepared) try { if (v.paused && mp.isPlaying) mp.pause() else if (!v.paused && !mp.isPlaying) mp.start() } catch (_: IllegalStateException) {}
            }
        }
        scope.launch(Dispatchers.Main) {
            panel.commands.collect { c ->
                val mp = player?.takeIf { prepared && panel.video.value?.audioOnly == true } ?: return@collect
                try {
                    when (c) {
                        VideoPanel.Command.Restart -> { mp.seekTo(0); mp.start() }
                        is VideoPanel.Command.SeekBy ->
                            if (mp.duration > 0) mp.seekTo((mp.currentPosition + c.seconds * 1000L).coerceIn(0L, mp.duration.toLong()), MediaPlayer.SEEK_CLOSEST)
                        else -> {}   // pause and resume come through the panel's state
                    }
                } catch (_: IllegalStateException) {}
            }
        }
        // where it is (kept to come back to it) and, falling asleep, the fading
        scope.launch(Dispatchers.Main) {
            var ticks = 0
            while (true) {
                delay(1_000)
                val mp = player ?: continue
                if (!prepared) continue
                if (++ticks % 5 == 0) try { if (mp.isPlaying) panel.progress(mp.currentPosition / 1000, maxOf(0, mp.duration / 1000)) } catch (_: IllegalStateException) {}
                if (panel.video.value?.sleep == true) applyVolume()
            }
        }
    }

    private fun load(v: VideoPanel.Video) {
        release()
        url = v.url
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(v.url)
            mp.setOnPreparedListener {
                prepared = true
                applyVolume()
                if (v.startAt > 0) it.seekTo(v.startAt * 1000L, MediaPlayer.SEEK_CLOSEST)
                if (panel.video.value?.paused != true) it.start()
            }
            mp.setOnCompletionListener { panel.ended() }
            mp.setOnErrorListener { _, what, extra -> android.util.Log.w("JarvisAudio", "player error $what/$extra"); panel.ended(); true }
            mp.prepareAsync()
        } catch (e: Exception) {
            android.util.Log.w("JarvisAudio", "cannot open", e)
            panel.ended()
            return
        }
        // in the foreground while it plays: the screen may go off, the app may go (and in the car, the phone is locked)
        try {
            ContextCompat.startForegroundService(context, Intent(context, com.jarvis.android.car.JarvisMediaService::class.java).setAction(com.jarvis.android.car.JarvisMediaService.ACTION_PLAYING))
        } catch (e: Exception) {
            android.util.Log.w("JarvisAudio", "foreground refused: plays while the app is open", e)
        }
    }

    /** The sound asked for (none, turned down while the user talks, or full), faded out before a sleep timer stops it. */
    private fun applyVolume() {
        val v = panel.video.value ?: return
        val fade = if (v.sleep) panel.timerLeftMs()?.let { (it / SLEEP_FADE_MS.toFloat()).coerceIn(0.05f, 1f) } ?: 1f else 1f
        val level = volumeOf(v) / 100f * fade
        try { player?.setVolume(level, level) } catch (_: IllegalStateException) {}
    }

    private fun release() {
        prepared = false
        player?.let { try { it.release() } catch (_: Exception) {} }
        player = null
        url = null
    }

    private fun stop() {
        if (player == null) return
        release()
        try {
            context.startService(Intent(context, com.jarvis.android.car.JarvisMediaService::class.java).setAction(com.jarvis.android.car.JarvisMediaService.ACTION_STOPPED))
        } catch (_: Exception) {
        }
    }

    companion object {
        /** How long the sound takes to fade out before a sleep timer stops it. */
        const val SLEEP_FADE_MS = 180_000L
    }
}
