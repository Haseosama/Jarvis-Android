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
) {
    /** Plays [voice] saying the sample for the interface language; returns null when it played, otherwise a message to show. */
    suspend fun play(voice: String, english: Boolean, name: String): String? {
        val text = sample(english, name)
        val file = File(cacheDir, "voice_samples/${voice}_${if (english) "en" else "fr"}_${text.hashCode().toUInt()}.pcm")
        return try {
            if (file.exists() && file.length() > 0) {
                val pcm = file.readBytes()
                output.play { emit -> emit(pcm) }
                return null
            }
            val got = java.io.ByteArrayOutputStream()
            output.play { emit ->
                transport.stream(speechModel(), buildSpeechRequest(text, voice)) { event ->
                    val pcm = parseSpeechChunk(event)
                    if (pcm.isNotEmpty()) {
                        if (got.size() + pcm.size > MAX_SPEECH_BYTES) throw RestChatException(ERROR_AUDIO_TOO_LARGE)
                        got.write(pcm)
                        emit(pcm)
                    }
                }
                if (got.size() == 0) throw RestChatException(ERROR_INVALID_AUDIO)
            }
            file.parentFile?.mkdirs()
            file.writeBytes(got.toByteArray())
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestChatException) {
            e.message
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
