package com.jarvis.android.rest

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal const val ERROR_PERMISSION =
    "Micro non autorisé. Accordez la permission d’enregistrement audio puis réessayez."
internal const val ERROR_MIC_UNAVAILABLE = "Microphone indisponible ou déjà utilisé par une autre application."
internal const val ERROR_PLAYBACK = "Lecture audio impossible."

internal interface MicRecorder {
    /** Starts recording; throws [RestChatException] with a readable message on failure. */
    fun start()

    /** Stops and returns what was recorded (possibly empty). */
    fun stop(): ShortArray
}

internal interface SpeechOutput {
    /** Plays 24 kHz mono PCM and returns when it has finished (or was stopped). */
    suspend fun play(pcm: ByteArray)

    fun stop()
}

/** 16 kHz mono capture into memory, at most [MAX_RECORD_SECONDS] seconds. */
internal class AudioRecorder(private val context: Context) : MicRecorder {
    private val lock = Any()
    private var session: Session? = null

    private class Session(val record: AudioRecord) {
        val running = AtomicBoolean(true)
        val samples = ShortArray(RECORD_SAMPLE_RATE * MAX_RECORD_SECONDS)
        @Volatile var count = 0
        @Volatile var failed = false
        lateinit var thread: Thread
    }

    @SuppressLint("MissingPermission")
    override fun start() = synchronized(lock) {
        if (session != null) throw RestChatException("Un enregistrement est déjà en cours.")
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw RestChatException(ERROR_PERMISSION)
        }
        var record: AudioRecord? = null
        try {
            val min = AudioRecord.getMinBufferSize(
                RECORD_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (min <= 0) throw RestChatException(ERROR_MIC_UNAVAILABLE)
            record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RECORD_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min, 8192))
                .build()
            check(record.state == AudioRecord.STATE_INITIALIZED)
            val next = Session(record)
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            next.thread = Thread({ capture(next) }, "jarvis-rest-recorder")
            session = next
            next.thread.start()
        } catch (e: RestChatException) {
            record?.release()
            throw e
        } catch (_: SecurityException) {
            record?.release()
            throw RestChatException(ERROR_PERMISSION)
        } catch (_: Exception) {
            record?.release()
            session = null
            throw RestChatException(ERROR_MIC_UNAVAILABLE)
        }
    }

    override fun stop(): ShortArray = synchronized(lock) {
        val current = session ?: return ShortArray(0)
        session = null
        current.running.set(false)
        current.thread.join()
        if (current.failed) throw RestChatException(ERROR_MIC_UNAVAILABLE)
        current.samples.copyOf(current.count)
    }

    private fun capture(current: Session) {
        val chunk = ShortArray(2048)
        val deadline = SystemClock.elapsedRealtime() + MAX_RECORD_SECONDS * 1000L
        try {
            while (current.running.get() && current.count < current.samples.size &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                val read = current.record.read(
                    chunk, 0, minOf(chunk.size, current.samples.size - current.count), AudioRecord.READ_BLOCKING,
                )
                if (read < 0) throw IllegalStateException()
                chunk.copyInto(current.samples, current.count, 0, read)
                current.count += read
            }
        } catch (_: Exception) {
            current.failed = true
        } finally {
            try {
                current.record.stop()
            } catch (_: RuntimeException) {
            }
            current.record.release()
        }
    }
}

/** Plays a whole PCM buffer through an [AudioTrack], holding audio focus while it plays. */
internal class AudioPlayer(context: Context) : SpeechOutput {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()
    private var track: AudioTrack? = null

    override suspend fun play(pcm: ByteArray) = withContext(Dispatchers.IO) {
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        val player = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SPEECH_SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (_: Exception) {
            throw RestChatException(ERROR_PLAYBACK)
        }
        try {
            check(player.state == AudioTrack.STATE_INITIALIZED)
            check(player.write(pcm, 0, pcm.size) == pcm.size)
            manager.requestAudioFocus(focus)
            synchronized(lock) { track = player }
            player.play()
            val totalFrames = pcm.size / 2
            while (player.playState == AudioTrack.PLAYSTATE_PLAYING && player.playbackHeadPosition < totalFrames) {
                delay(50)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw RestChatException(ERROR_PLAYBACK)
        } finally {
            synchronized(lock) { if (track === player) track = null }
            try {
                player.stop()
            } catch (_: Exception) {
            }
            player.release()
            manager.abandonAudioFocusRequest(focus)
        }
    }

    override fun stop() {
        synchronized(lock) {
            try {
                track?.stop()
            } catch (_: Exception) {
            }
        }
    }
}
