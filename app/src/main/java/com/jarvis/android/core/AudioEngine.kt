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
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    track?.pause()
                }
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
        try {
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "Microphone indisponible." }
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
            .build()
        val focusGranted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (focusGranted) focusRequest = request
        inSetup = false
        if (!focusGranted) return@synchronized false
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
        true
    }

    fun abandonAudioFocus() {
        if (inSetup) return
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    suspend fun playChunk(pcm16: ByteArray) {
        val player = synchronized(playbackLock) { track } ?: return
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
        track?.let {
            it.pause()
            it.flush()
            it.play()
        }
    }

    fun stopPlayback() = synchronized(playbackLock) {
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
