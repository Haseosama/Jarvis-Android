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
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
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
            AudioRoute.input(context)?.let { rec.preferredDevice = it }
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
        inSetup = true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
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
        val focusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED || result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        if (focusGranted) focusRequest = request
        inSetup = false
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

    fun abandonAudioFocus() {
        if (inSetup) return
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** Wall-clock time (elapsedRealtime) until which queued speech is still coming out of the speaker. */
    @Volatile private var playingUntil = 0L

    /** True while the assistant's voice is audible, with a short tail for the room's echo. */
    fun isPlaybackActive(): Boolean = android.os.SystemClock.elapsedRealtime() < playingUntil + ECHO_TAIL_MS

    /** True when the assistant's voice comes out of the phone's loudspeaker (no headset, Bluetooth or USB audio). */
    fun playsOnLoudspeaker(): Boolean {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val chosen = AudioRoute.output(context)
        if (chosen != null) return chosen.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        return outputs.none { isPrivateOutput(it.type) }
    }

    private fun isPrivateOutput(type: Int) = when (type) {
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
        android.media.AudioDeviceInfo.TYPE_HEARING_AID -> true
        else -> false
    }

    suspend fun playChunk(pcm16: ByteArray) {
        val player = synchronized(playbackLock) { track } ?: return
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

    private companion object {
        const val ECHO_TAIL_MS = 350L
    }
}
