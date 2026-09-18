package com.markliv.android

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

class TtsPlayer(
    context: Context,
    private val onError: () -> Unit = {},
    private val onComplete: () -> Unit = {}
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("markliv-wav-player").apply { start() }
    private val worker = Handler(thread.looper)
    private val lock = Any()
    private val generation = AtomicLong(0)
    private var closed = false
    private var player: MediaPlayer? = null
    private var tempFile: File? = null

    fun play(wav: ByteArray) {
        synchronized(lock) {
            if (closed) return
            val version = generation.incrementAndGet()
            val snapshot = if (AudioTranscriber.isSpeechWav(wav)) wav.copyOf() else null
            worker.post {
                if (version != generation.get()) return@post
                if (!releasePlayback()) {
                    notifyMain(version, onError)
                    return@post
                }
                if (snapshot == null || !AudioTranscriber.isSpeechWav(snapshot)) {
                    notifyMain(version, onError)
                    return@post
                }
                try {
                    val file = File.createTempFile("markliv-speech-", ".wav", appContext.noBackupFilesDir)
                    tempFile = file
                    file.outputStream().use { it.write(snapshot) }
                    if (version != generation.get()) {
                        releasePlayback()
                        return@post
                    }
                    val current = MediaPlayer()
                    player = current
                    current.setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    FileInputStream(file).use { current.setDataSource(it.fd) }
                    current.setOnPreparedListener {
                        if (version != generation.get() || player !== current) return@setOnPreparedListener
                        try {
                            current.start()
                        } catch (_: RuntimeException) {
                            finish(current, version, false)
                        }
                    }
                    current.setOnCompletionListener { finish(current, version, true) }
                    current.setOnErrorListener { _, _, _ ->
                        finish(current, version, false)
                        true
                    }
                    current.prepareAsync()
                } catch (_: Exception) {
                    releasePlayback()
                    notifyMain(version, onError)
                } finally {
                    snapshot.fill(0)
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (closed) return
            val version = generation.incrementAndGet()
            worker.post {
                if (!releasePlayback()) notifyMain(version, onError)
            }
        }
    }

    fun shutdown() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val version = generation.incrementAndGet()
            worker.post {
                try {
                    if (!releasePlayback()) notifyMain(version, onError)
                } finally {
                    thread.quitSafely()
                }
            }
        }
    }

    private fun finish(current: MediaPlayer, version: Long, completed: Boolean) {
        if (player !== current) return
        val released = releasePlayback()
        notifyMain(version, if (completed && released) onComplete else onError)
    }

    private fun releasePlayback(): Boolean {
        val current = player
        player = null
        var success = true
        try {
            current?.release()
        } catch (_: RuntimeException) {
            success = false
        }
        try {
            tempFile?.let { Files.deleteIfExists(it.toPath()) }
            tempFile = null
        } catch (_: Exception) {
            success = false
        }
        return success
    }

    private fun notifyMain(version: Long, callback: () -> Unit) {
        mainHandler.post {
            if (version == generation.get()) callback()
        }
    }
}
