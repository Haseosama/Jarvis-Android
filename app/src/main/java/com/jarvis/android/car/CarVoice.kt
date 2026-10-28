package com.jarvis.android.car

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.tr
import com.jarvis.android.rest.RestChatException
import com.jarvis.android.rest.buildTranscriptionRequest
import com.jarvis.android.rest.parseTranscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * The car's own microphone (Android Auto shows its listening bar while it is open): what the user says on the "Parler à Jarvis" screen is
 * recorded until they stop talking, written down by Gemini (the same transcription as the phone's push-to-talk, rest/Speech.kt) and asked
 * of the phone's text chat. The car's media is paused for that moment (a short exclusive audio focus), so the radio is not heard as speech.
 */

/** When a recording stops: after a pause once something was said, after a while if nothing is, and at the longest. */
internal class EndOfSpeech(
    private val sampleRate: Int = CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE,
    private val silenceMs: Int = 1_300,
    private val nothingSaidMs: Int = 7_000,
    private val longestMs: Int = 20_000,
    private val speechLevel: Float = 0.06f,
) {
    private var heard = false
    private var totalSamples = 0L
    private var quietSamples = 0L

    val spoke get() = heard

    /** Takes one chunk of [samples] at [level] (0 to 1, see core/AudioLevel.kt); true when the recording should end. */
    fun feed(level: Float, samples: Int): Boolean {
        totalSamples += samples
        if (level >= speechLevel) {
            heard = true
            quietSamples = 0
        } else {
            quietSamples += samples
        }
        val ms = { n: Long -> n * 1000 / sampleRate }
        return ms(totalSamples) >= longestMs ||
            (heard && ms(quietSamples) >= silenceMs) ||
            (!heard && ms(totalSamples) >= nothingSaidMs)
    }
}

/** Little-endian 16-bit PCM bytes as samples. */
internal fun pcmToShorts(pcm: ByteArray): ShortArray {
    val out = ShortArray(pcm.size / 2)
    ByteBuffer.wrap(pcm, 0, out.size * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
    return out
}

internal object CarVoice {
    /** Listens to the car's microphone and returns what was said, or throws [RestChatException] with words to show. */
    suspend fun listen(carContext: CarContext): String = withContext(Dispatchers.IO) {
        if (carContext.carAppApiLevel < 5) throw RestChatException(tr("Cette voiture ne prête pas son micro aux applis : répondez au message de Jarvis dans les notifications."))
        val audio = carContext.getSystemService(AudioManager::class.java)
        val record = try {
            CarAudioRecord.create(carContext)
        } catch (_: SecurityException) {
            throw RestChatException(tr("Jarvis n’a pas le droit au micro : autorisez-le sur le téléphone."))
        }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setOnAudioFocusChangeListener { if (it == AudioManager.AUDIOFOCUS_LOSS) try { record.stopRecording() } catch (_: Exception) {} }
            .build()
        audio.requestAudioFocus(focus)
        val pcm = ByteArrayOutputStream()
        val end = EndOfSpeech()
        try {
            record.startRecording()
            val chunk = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
            while (true) {
                val n = record.read(chunk, 0, chunk.size)
                if (n < 0) break   // the user closed the car's microphone
                if (n == 0) continue
                pcm.write(chunk, 0, n)
                if (end.feed(com.jarvis.android.core.pcm16Level(chunk.copyOf(n)), n / 2)) break
            }
        } finally {
            try { record.stopRecording() } catch (_: Exception) {}
            audio.abandonAudioFocusRequest(focus)
        }
        if (!end.spoke) throw RestChatException(tr("Je n’ai rien entendu."))
        val container = (carContext.applicationContext as JarvisApp).container
        try {
            parseTranscription(container.restChat.transport.generate(container.configStore.snapshotRestModel(), buildTranscriptionRequest(pcmToShorts(pcm.toByteArray()))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestChatException) {
            throw e
        } catch (_: Exception) {
            throw RestChatException(tr("Je n’ai pas pu écrire ce qui a été dit : vérifiez le réseau."))
        }
    }
}
