package com.jarvis.android.rest

import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Plays a short sentence in one of Gemini's voices, so the user hears it before choosing it. The sentence is spoken by the same
 * speech endpoint as the chat's spoken replies, and kept on the phone (a few hundred kilobytes per voice): a voice heard once plays
 * again at once, and costs nothing more.
 */
internal class VoicePreview(
    private val transport: GenerateTransport,
    private val speechModel: suspend () -> String,
    private val output: SpeechOutput,
    private val cacheDir: File,
    private val log: (String, Throwable?) -> Unit = { m, e -> android.util.Log.w("JarvisVoicePreview", m, e) },
) {
    /**
     * Plays [voice] saying the sample for the interface language; returns null when it played, otherwise a message to show.
     * [onSound] is called when the sample is ready and starts playing (it may have to be fetched first, a few seconds).
     */
    suspend fun play(voice: String, english: Boolean, name: String, onSound: () -> Unit = {}): String? {
        val text = sample(english, name)
        val file = File(cacheDir, "voice_samples/${voice}_${if (english) "en" else "fr"}_${text.hashCode().toUInt()}.pcm")
        return try {
            val pcm = if (file.exists() && file.length() > 0) {
                file.readBytes()
            } else {
                // one plain request for the whole sentence (a few seconds of audio): simpler and surer than the streamed one of the
                // chat's replies, which some speech models answer with nothing
                parseSpeechResponse(transport.generate(speechModel(), buildSpeechRequest(text, voice))).also {
                    file.parentFile?.mkdirs()
                    file.writeBytes(it)
                }
            }
            onSound()
            // in pieces of a fifth of a second, as the streamed replies come
            output.play { emit -> for (o in pcm.indices step 9_600) emit(pcm.copyOfRange(o, minOf(pcm.size, o + 9_600))) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestChatException) {
            log("sample of $voice: ${e.message}", null)
            e.message
        } catch (e: Exception) {
            log("sample of $voice", e)
            "Échantillon impossible : " + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun stop() = output.stop()

    companion object {
        fun sample(english: Boolean, name: String): String {
            val who = name.trim().ifEmpty { "Jarvis" }.lowercase().replaceFirstChar { it.uppercase() }
            return if (english) "Hello, I'm $who. This is how I would sound if you chose this voice."
            else "Bonjour, je suis $who. Voici comment je parlerais si vous choisissiez cette voix."
        }
    }
}
