package com.jarvis.android.avatar

import android.content.Context
import android.os.SystemClock
import com.jarvis.android.core.JarvisState

/** Reduces the assistant's state to what the face expresses. */
internal fun moodFor(state: JarvisState): Mood = when (state) {
    JarvisState.ASLEEP, JarvisState.ERROR -> Mood.ASLEEP
    JarvisState.LISTENING -> Mood.LISTENING
    JarvisState.THINKING -> Mood.THINKING
    JarvisState.CONNECTING, JarvisState.SPEAKING -> Mood.IDLE
}

/**
 * The one avatar of the app: the session engine feeds it the voice (as it is about to be played) and the transcript,
 * and the HUD draws it. The head is loaded from the asset the first time it is needed.
 */
internal class AvatarController(private val context: Context) {
    /** Set from the settings: when off, nothing is analysed and nothing is drawn. */
    @Volatile var enabled = true

    /** Set from the settings: hair on the head (default) or a bare head. */
    @Volatile var hair = true

    /** Debug builds can force the face's mood (see DebugAvatarReceiver); null means "follow the assistant's state". */
    @Volatile var debugMood: Mood? = null

    val timeline = VisemeTimeline()
    private val stream = VisemeStream()

    val mesh: HeadMesh by lazy { HeadMesh.parse(context.assets.open("avatar/head_mesh.bin").use { it.readBytes() }) }
    val avatar: HoloAvatar by lazy { HoloAvatar(mesh) }

    /** Called with each chunk of the assistant's voice (16-bit PCM, 24 kHz), just before it goes to the speaker. */
    fun onSpeech(pcm16: ByteArray) {
        if (!enabled) return
        try {
            val frames = pcmVisemes(pcm16ToShorts(pcm16))
            if (frames.isEmpty()) return
            timeline.push(stream.frames(frames, 0.02f), SystemClock.elapsedRealtimeNanos())
        } catch (_: Exception) {
            // Lip-sync is decoration: a failure must never reach the voice.
        }
    }

    /** The words being spoken, so the lips can close on m, b, p. */
    fun onTranscript(text: String) {
        if (enabled) stream.feedText(text)
    }

    /** The user cut the assistant off, or the session ended: nothing already queued is going to be heard. */
    fun interrupt() {
        timeline.clear()
        stream.reset()
    }
}
