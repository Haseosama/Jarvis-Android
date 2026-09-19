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
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal const val ERROR_PERMISSION =
    "Micro non autorisé. Accordez la permission d’enregistrement audio puis réessayez."
internal const val ERROR_MIC_UNAVAILABLE = "Microphone indisponible ou déjà utilisé par une autre application."
private const val TAG = "JarvisRestVoice"
internal const val ERROR_PLAYBACK = "Lecture audio impossible."

internal interface MicRecorder {
    /** Starts recording; throws [RestChatException] with a readable message on failure. */
    fun start()

    /** Stops and returns what was recorded (possibly empty). */
    fun stop(): ShortArray
}

internal interface SpeechOutput {
    /**
     * Plays the 24 kHz mono PCM that [source] hands to its `emit` callback, starting as soon as
     * enough has arrived rather than waiting for the end. Returns when everything has been played
     * or [stop] was called; errors thrown by [source] are passed on.
     */
    suspend fun play(source: suspend (emit: suspend (ByteArray) -> Unit) -> Unit)

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

private class PlaybackStopped : Exception()

private const val PREBUFFER_BYTES = SPEECH_SAMPLE_RATE * 2 * 3 / 10 // 0.3 s

/** Streams PCM to an [AudioTrack] while it is still being produced, holding audio focus. */
internal class AudioPlayer(context: Context) : SpeechOutput {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()
    private var track: AudioTrack? = null
    @Volatile private var stopRequested = false

    override suspend fun play(source: suspend (suspend (ByteArray) -> Unit) -> Unit) = withContext(Dispatchers.IO) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(
            SPEECH_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val player = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SPEECH_SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, SPEECH_SAMPLE_RATE * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack impossible à créer", e)
            throw RestChatException(ERROR_PLAYBACK)
        }
        var written = 0L
        var started = false
        try {
            check(player.state == AudioTrack.STATE_INITIALIZED)
            manager.requestAudioFocus(focus)
            synchronized(lock) {
                stopRequested = false
                track = player
            }
            source { chunk ->
                var offset = 0
                while (offset < chunk.size) {
                    currentCoroutineContext().ensureActive()
                    if (stopRequested) throw PlaybackStopped()
                    val count = player.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (stopRequested) throw PlaybackStopped()
                    check(count >= 0) { "write=$count" }
                    offset += count
                }
                written += chunk.size
                // A short head start absorbs network jitter before the first sound.
                if (!started && written >= PREBUFFER_BYTES) {
                    player.play()
                    started = true
                }
            }
            if (!started) player.play()
            val totalFrames = written / 2
            val deadline = SystemClock.elapsedRealtime() + totalFrames * 1000 / SPEECH_SAMPLE_RATE + 3_000
            while (!stopRequested && player.playbackHeadPosition < totalFrames &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                delay(50)
            }
        } catch (_: PlaybackStopped) {
            // Stopped on request: not an error.
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestChatException) {
            throw e
        } catch (e: Exception) {
            if (!stopRequested) {
                Log.w(TAG, "Lecture impossible (état ${player.state}, $written octets)", e)
                throw RestChatException(ERROR_PLAYBACK)
            }
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
            stopRequested = true
            try {
                track?.pause()
                track?.flush()
            } catch (_: Exception) {
            }
        }
    }
}
