package com.markliv.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

class RecorderException(message: String) : Exception(message)

class Recorder {

    private val lock = Any()
    private var session: Recording? = null

    fun start(context: Context) {
        synchronized(lock) {
            if (session != null) {
                throw RecorderException("Terminez l'enregistrement précédent avant de recommencer.")
            }
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw RecorderException(ERROR_PERMISSION)
            }
            var record: AudioRecord? = null
            try {
                val manager = context.getSystemService(AudioManager::class.java)
                if (manager.activeRecordingConfigurations.isNotEmpty()) {
                    throw RecorderException(ERROR_UNAVAILABLE)
                }
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer <= 0) throw RecorderException(ERROR_UNAVAILABLE)
                record = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_SAMPLES * 4))
                    .build()
                if (record.state != AudioRecord.STATE_INITIALIZED) throw RecorderException(ERROR_UNAVAILABLE)
                val next = Recording(record)
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING || isSilenced(record)) {
                    throw RecorderException(ERROR_UNAVAILABLE)
                }
                next.thread = Thread({ readSamples(next) }, "markliv-recorder")
                session = next
                next.thread.start()
            } catch (_: SecurityException) {
                release(record)
                session = null
                throw RecorderException(ERROR_PERMISSION)
            } catch (_: Exception) {
                release(record)
                session = null
                throw RecorderException(ERROR_UNAVAILABLE)
            }
        }
    }

    fun stop(): ShortArray {
        synchronized(lock) {
            val current = session ?: return ShortArray(0)
            current.running.set(false)
            LockSupport.unpark(current.thread)
            var interrupted = false
            while (current.thread.isAlive) {
                try {
                    current.thread.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            session = null
            if (interrupted) Thread.currentThread().interrupt()
            try {
                current.error?.let { throw RecorderException(it) }
                return current.samples.copyOf(current.count)
            } finally {
                current.samples.fill(0)
            }
        }
    }

    fun sampleRate(): Int = SAMPLE_RATE

    fun isRecording(): Boolean = synchronized(lock) { session?.running?.get() == true }

    private fun readSamples(current: Recording) {
        val chunk = ShortArray(CHUNK_SAMPLES)
        val deadline = SystemClock.elapsedRealtime() + MAX_DURATION_MS
        try {
            while (current.running.get() && current.count < MAX_SAMPLES && SystemClock.elapsedRealtime() < deadline) {
                if (isSilenced(current.record)) throw RecorderException(ERROR_UNAVAILABLE)
                val read = current.record.read(
                    chunk, 0, minOf(chunk.size, MAX_SAMPLES - current.count), AudioRecord.READ_NON_BLOCKING
                )
                if (read < 0) throw RecorderException(ERROR_UNAVAILABLE)
                if (read == 0) {
                    LockSupport.parkNanos(10_000_000L)
                } else {
                    chunk.copyInto(current.samples, current.count, 0, read)
                    current.count += read
                }
            }
        } catch (_: Exception) {
            current.error = ERROR_UNAVAILABLE
        } finally {
            chunk.fill(0)
            release(current.record)
            current.running.set(false)
        }
    }

    private fun isSilenced(record: AudioRecord): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && record.activeRecordingConfiguration?.isClientSilenced == true

    private fun release(record: AudioRecord?) {
        if (record == null) return
        try {
            record.stop()
        } catch (_: RuntimeException) {
        } finally {
            try {
                record.release()
            } catch (_: RuntimeException) {
            }
        }
    }

    private class Recording(val record: AudioRecord) {
        val running = AtomicBoolean(true)
        val samples = ShortArray(MAX_SAMPLES)
        var count = 0
        var error: String? = null
        lateinit var thread: Thread
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = 2048
        const val MAX_SAMPLES = SAMPLE_RATE * 60
        const val MAX_DURATION_MS = 60_000L
        const val ERROR_PERMISSION = "Micro non autorisé. Accordez la permission d'enregistrement audio puis réessayez."
        const val ERROR_UNAVAILABLE = "Microphone indisponible ou déjà utilisé par une autre application."
    }
}
