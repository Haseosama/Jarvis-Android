package com.jarvis.android.core

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Microphone capture + speaker playback, matching the sample rates the Live API
 * expects (16 kHz in / 24 kHz out — see [LiveProtocol]). Equivalent of Mark-LIII's
 * `sounddevice` mic stream and audio-out player, but through Android's native APIs.
 */
class AudioEngine {

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    @Volatile private var capturing = false

    /** Chunk size in frames — small enough for low latency, matches Mark-LIII's CHUNK_SIZE. */
    private val chunkFrames = 1024

    @SuppressLint("MissingPermission")
    fun micFrames(): Flow<ByteArray> = callbackFlow {
        val minBuf = AudioRecord.getMinBufferSize(
            LiveProtocol.SEND_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, chunkFrames * 2 * 4)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            LiveProtocol.SEND_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )
        record = rec
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            close(IllegalStateException("AudioRecord failed to initialize"))
            return@callbackFlow
        }
        capturing = true
        rec.startRecording()

        val thread = Thread({
            val buf = ByteArray(chunkFrames * 2) // 16-bit mono
            while (capturing) {
                val read = rec.read(buf, 0, buf.size)
                if (read > 0) {
                    trySend(buf.copyOf(read))
                }
            }
        }, "JarvisMicThread")
        thread.start()

        awaitClose {
            capturing = false
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            rec.release()
            record = null
        }
    }.conflate()

    fun startPlayback() {
        val minBuf = AudioTrack.getMinBufferSize(
            LiveProtocol.RECEIVE_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        track = AudioTrack.Builder()
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
        track?.play()
    }

    fun playChunk(pcm16: ByteArray) {
        track?.write(pcm16, 0, pcm16.size)
    }

    /** Drops any buffered-but-unplayed audio — used when the user barges in. */
    fun flushPlayback() {
        track?.pause()
        track?.flush()
        track?.play()
    }

    fun stopPlayback() {
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        track?.release()
        track = null
    }

    fun isSpeaking(): Boolean = track?.playState == AudioTrack.PLAYSTATE_PLAYING
}
