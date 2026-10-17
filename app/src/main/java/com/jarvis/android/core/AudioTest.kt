package com.jarvis.android.core

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The TEST buttons of Settings > Périphériques audio (after Mark LVI's audio panel): the microphone shows a live meter while the
 * user speaks and says the loudest it heard, the speaker plays a short chime. An automatic check on silence passes a muted
 * microphone, a level at zero or a covered one alike; only a person speaking settles it.
 */

private const val TEST_RATE = 16_000

/** The loudness of [count] samples in dB below full scale (−90 for silence). */
internal fun dbfs(samples: ShortArray, count: Int): Double {
    if (count <= 0) return -90.0
    var sum = 0.0
    for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
    val rms = sqrt(sum / count) / 32768.0
    return if (rms <= 0.0) -90.0 else max(-90.0, 20 * log10(rms))
}

/** A meter position from 0 to 1 for a level in dBFS (−60 dB empty, 0 dB full). */
internal fun meterOf(db: Double): Float = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()

/** What the microphone test says, from the loudest moment heard ([peakDb], in dBFS). */
internal fun micVerdict(peakDb: Double): String = when {
    peakDb < -55 -> tr("Rien entendu : le micro est peut-être coupé, son niveau à zéro, ou masqué. Vérifiez aussi qu’une autre application ne le garde pas.")
    peakDb < -38 -> trf("Très faible (crête {0} dB) : parlez plus près, ou choisissez un autre micro.", peakDb.toInt())
    else -> trf("Le micro vous entend bien (crête {0} dB).", peakDb.toInt())
}

/** A two-note chime ([ms] in all) at [rate] Hz, faded in and out so it does not click. */
internal fun chimeSamples(rate: Int = TEST_RATE, ms: Int = 900): ShortArray {
    val n = rate * ms / 1000
    val half = n / 2
    return ShortArray(n) { i ->
        val freq = if (i < half) 660.0 else 880.0
        val local = if (i < half) i else i - half
        val len = if (i < half) half else n - half
        val fade = minOf(1.0, local / (rate * 0.02), (len - local) / (rate * 0.04))
        (sin(2 * PI * freq * i / rate) * 0.35 * fade * Short.MAX_VALUE).toInt().toShort()
    }
}

private fun device(context: Context, key: String, inputs: Boolean): AudioDeviceInfo? {
    if (key.isBlank()) return null
    val all = context.getSystemService(AudioManager::class.java).getDevices(if (inputs) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS).toList()
    return pickByKey(all, key) { deviceKey(it.type, it.productName.toString(), it.address) }
}

/**
 * Listens for [seconds] on the microphone chosen by [inputKey] (empty: automatic), handing the level (0 to 1) to [onLevel] about
 * twenty times a second, and returns the verdict. The caller has checked the microphone permission.
 */
@SuppressLint("MissingPermission")
internal suspend fun testMicrophone(context: Context, inputKey: String, seconds: Int = 5, onLevel: (Float) -> Unit): String = withContext(Dispatchers.IO) {
    val min = AudioRecord.getMinBufferSize(TEST_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (min <= 0) return@withContext tr("Ce téléphone refuse d’enregistrer en 16 kHz.")
    val record = try {
        AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, TEST_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, TEST_RATE / 5))
    } catch (e: Exception) {
        return@withContext trf("Micro inaccessible : {0}", e.message.orEmpty())
    }
    try {
        if (record.state != AudioRecord.STATE_INITIALIZED) return@withContext tr("Micro inaccessible : une autre application l’utilise peut-être (la session vocale de Jarvis aussi).")
        device(context, inputKey, inputs = true)?.let { record.preferredDevice = it }
        record.startRecording()
        val buffer = ShortArray(TEST_RATE / 20)
        var peak = -90.0
        val end = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < end && currentCoroutineContext().isActive) {
            val read = record.read(buffer, 0, buffer.size)
            if (read < 0) return@withContext tr("Le micro a cessé d’envoyer du son.")
            val db = dbfs(buffer, read)
            peak = max(peak, db)
            onLevel(meterOf(db))
        }
        micVerdict(peak)
    } finally {
        onLevel(0f)
        try { record.stop() } catch (_: Exception) { }
        record.release()
    }
}

/** Plays the chime on the output chosen by [outputKey] (empty: automatic); a sentence when it could not. */
internal suspend fun testSpeaker(context: Context, outputKey: String): String? = withContext(Dispatchers.IO) {
    val samples = chimeSamples()
    val track = try {
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(TEST_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
    } catch (e: Exception) {
        return@withContext trf("Son impossible : {0}", e.message.orEmpty())
    }
    try {
        device(context, outputKey, inputs = false)?.let { track.preferredDevice = it }
        track.write(samples, 0, samples.size)
        track.play()
        delay(samples.size * 1000L / TEST_RATE + 150)
        val am = context.getSystemService(AudioManager::class.java)
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) tr("Le volume multimédia est à zéro : montez-le pour entendre Jarvis.") else null
    } finally {
        try { track.stop() } catch (_: Exception) { }
        track.release()
    }
}
