package com.jarvis.android.core

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.util.concurrent.atomic.AtomicBoolean

class AudioEngine(private val context: Context) {
    private val playbackLock = Any()
    private var track: AudioTrack? = null
    private val chunkFrames = 1024
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var inSetup = false

    /** In a car the focus is taken only while the assistant speaks, and lightly (see CarAudio.kt). */
    @Volatile private var carFocus = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var carCache = false
    private var carCacheAt = -10_000L

    /** Whether the car mode applies now (asked again at most every three seconds: Android Auto can connect in the middle of a session). */
    fun carMode(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - carCacheAt > 3_000L) {
            carCache = CarAudio.active(context)
            carCacheAt = now
        }
        return carCache
    }

    private fun builtInMic(): android.media.AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }

    /** Result code of the last audio focus request (0 refused, 1 granted, 2 delayed), for the activity log. */
    @Volatile var lastFocusResult = -1
        private set

    /** Short description of why focus may have been refused: the audio mode (call?) and whether other sound is playing. */
    fun focusDiagnostic(): String {
        val mode = when (audioManager.mode) {
            AudioManager.MODE_NORMAL -> "normal"
            AudioManager.MODE_RINGTONE -> "sonnerie"
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_CALL_SCREENING -> "appel"
            else -> audioManager.mode.toString()
        }
        return "code $lastFocusResult, mode audio $mode, autre son en cours : ${if (audioManager.isMusicActive) "oui" else "non"}, voiture : ${CarAudio.describe(context)}"
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (inSetup) return@OnAudioFocusChangeListener
        synchronized(playbackLock) {
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    track?.let { t ->
                        if (t.playState == AudioTrack.PLAYSTATE_PAUSED) {
                            t.flush()
                            t.play()
                        }
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    track?.pause()
                }
                // A lasting loss (music the assistant itself started) or ducking must not cut a sentence: keep talking.
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun micFrames(): Flow<ByteArray> = callbackFlow {
        val minBuf = AudioRecord.getMinBufferSize(
            LiveProtocol.SEND_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuf > 0) { "Format du microphone indisponible." }
        val car = carMode()
        // In a car, VOICE_COMMUNICATION can make the phone switch the Bluetooth link to a call, which cuts the car's sound.
        val rec = AudioRecord(
            if (car) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            LiveProtocol.SEND_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, chunkFrames * 2 * 4),
        )
        val capturing = AtomicBoolean(false)
        var thread: Thread? = null
        val effects = mutableListOf<android.media.audiofx.AudioEffect>()
        try {
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "Microphone indisponible." }
            // The phone's own echo canceller and noise suppressor: without them the assistant's voice from the
            // speaker re-enters the microphone and the server takes it for the user speaking.
            // Best effort: some phones refuse or throw here, which must never cost the session its microphone.
            try {
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    android.media.audiofx.AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true; effects += it }
                }
            } catch (_: Exception) {
            }
            try {
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    android.media.audiofx.NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true; effects += it }
                }
            } catch (_: Exception) {
            }
            (AudioRoute.input(context) ?: if (car) builtInMic() else null)?.let { rec.preferredDevice = it }
            rec.startRecording()
            check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Capture impossible." }
            capturing.set(true)
            thread = Thread({
                try {
                    val buf = ByteArray(chunkFrames * 2)
                    while (capturing.get()) {
                        val count = rec.read(buf, 0, buf.size, AudioRecord.READ_NON_BLOCKING)
                        when {
                            count > 0 -> if (trySend(buf.copyOf(count)).isClosed) break
                            count < 0 -> error("Capture audio interrompue.")
                            else -> Thread.sleep(10)
                        }
                    }
                } catch (_: InterruptedException) {
                    if (capturing.get()) close(IllegalStateException("Capture audio interrompue."))
                } catch (_: Exception) {
                    close(IllegalStateException("Microphone indisponible."))
                }
            }, "JarvisMicThread").also { it.start() }
            awaitClose {}
        } finally {
            capturing.set(false)
            thread?.interrupt()
            try {
                thread?.join()
            } finally {
                try {
                    if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
                } catch (_: Exception) {
                } finally {
                    effects.forEach { try { it.release() } catch (_: Exception) {} }
                    rec.release()
                }
            }
        }
    }.conflate()

    fun startPlayback(): Boolean = synchronized(playbackLock) {
        if (track != null) return@synchronized true
        val car = carMode()
        carFocus = car
        // Elsewhere the session holds the focus; in a car it is taken while Jarvis speaks (ensureCarFocus), so the car's music comes back.
        val focusGranted = if (car) true else requestFocus(AudioManager.AUDIOFOCUS_GAIN)
        // A refused focus (another app holds it, some Samsung phones) is not fatal: the voice can still play, so go on.
        val minBuf = AudioTrack.getMinBufferSize(
            LiveProtocol.RECEIVE_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuf > 0) { "Format de lecture indisponible." }
        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(LiveProtocol.RECEIVE_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            check(player.state == AudioTrack.STATE_INITIALIZED) { "Lecture audio indisponible." }
            AudioRoute.output(context)?.let { player.preferredDevice = it }
            player.play()
            track = player
        } catch (e: Exception) {
            inSetup = true
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
            inSetup = false
            player.release()
            throw e
        }
        focusGranted
    }

    /** Asks for the audio focus with [gain]; true when it is granted (or will be). */
    private fun requestFocus(gain: Int): Boolean {
        inSetup = true
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .setAcceptsDelayedFocusGain(true)
            .build()
        val result = audioManager.requestAudioFocus(request)
        lastFocusResult = result
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED || result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        if (granted) focusRequest = request
        inSetup = false
        return granted
    }

    /** In a car: takes a light focus for the next sound, and lets it go a moment after the last one. */
    private fun ensureCarFocus() {
        val needed = synchronized(playbackLock) { focusRequest == null }
        if (needed) requestFocus(focusGainFor(true))
        handler.removeCallbacks(releaseCheck)
        handler.postDelayed(releaseCheck, 500L)
    }

    private val releaseCheck = object : Runnable {
        override fun run() {
            if (android.os.SystemClock.elapsedRealtime() < playingUntil + CAR_FOCUS_TAIL_MS) handler.postDelayed(this, 500L) else abandonAudioFocus()
        }
    }

    fun abandonAudioFocus() {
        handler.removeCallbacks(releaseCheck)
        if (inSetup) return
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** Wall-clock time (elapsedRealtime) until which queued speech is still coming out of the speaker. */
    @Volatile private var playingUntil = 0L

    /** True while the assistant's voice is audible, with a short tail for the room's echo. */
    fun isPlaybackActive(): Boolean = android.os.SystemClock.elapsedRealtime() < playingUntil + echoTailMs(carMode())

    /**
     * True when the assistant's voice can reach the microphone: the phone's loudspeaker, or anything in a car, whose speakers are in the room.
     * The microphone is then muted while it speaks (a headset, Bluetooth audio or USB audio out of the car keeps it open, so it can be interrupted).
     */
    fun playsOnLoudspeaker(): Boolean {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return voiceReachesMicrophone(outputs.map { it.type }, AudioRoute.output(context)?.type, carMode())
    }

    suspend fun playChunk(pcm16: ByteArray) {
        val player = synchronized(playbackLock) { track } ?: return
        if (carFocus) ensureCarFocus()
        val now = android.os.SystemClock.elapsedRealtime()
        val chunkMs = pcm16.size * 1000L / (2 * LiveProtocol.RECEIVE_SAMPLE_RATE)
        playingUntil = maxOf(playingUntil, now) + chunkMs
        var offset = 0
        while (offset < pcm16.size) {
            currentCoroutineContext().ensureActive()
            val count = synchronized(playbackLock) {
                if (track !== player) return
                player.write(pcm16, offset, pcm16.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            }
            check(count >= 0) { "Lecture audio interrompue." }
            if (count == 0) delay(10) else offset += count
        }
    }

    fun flushPlayback() = synchronized(playbackLock) {
        playingUntil = 0L
        track?.let {
            it.pause()
            it.flush()
            it.play()
        }
    }

    fun stopPlayback() = synchronized(playbackLock) {
        playingUntil = 0L
        val player = track ?: return@synchronized
        track = null
        try {
            player.pause()
            player.flush()
            player.stop()
        } catch (_: Exception) {
        } finally {
            player.release()
        }
    }

    fun isSpeaking(): Boolean = synchronized(playbackLock) {
        track?.playState == AudioTrack.PLAYSTATE_PLAYING
    }

}
