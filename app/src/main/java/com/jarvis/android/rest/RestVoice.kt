package com.jarvis.android.rest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceStage { IDLE, RECORDING, TRANSCRIBING, WORKING, SPEAKING }

/**
 * Push-to-talk on top of the text chat: record, transcribe with Gemini, send the text as a
 * normal chat message, then read the answer aloud. Every step reports a readable error instead
 * of throwing, and a failure never leaves the stage stuck.
 */
internal class RestVoice(
    private val recorder: MicRecorder,
    private val output: SpeechOutput,
    private val transport: GenerateTransport,
    private val textModel: suspend () -> String,
    private val speechModel: suspend () -> String,
    private val voice: suspend () -> String,
    private val sendText: suspend (String) -> String?,
    private val lastReply: () -> String?,
) {
    private val _stage = MutableStateFlow(VoiceStage.IDLE)
    val stage: StateFlow<VoiceStage> = _stage.asStateFlow()

    /** Starts recording; returns null on success, otherwise a message to show. */
    fun startRecording(): String? {
        if (_stage.value != VoiceStage.IDLE) return "Une action vocale est déjà en cours."
        return try {
            recorder.start()
            _stage.value = VoiceStage.RECORDING
            null
        } catch (e: RestChatException) {
            e.message
        }
    }

    /**
     * Ends the recording and runs the whole round trip. Returns null on success, otherwise a
     * message; a message can concern only the last step (the written answer is still kept).
     */
    suspend fun finishRecording(): String? {
        if (_stage.value != VoiceStage.RECORDING) return null
        val samples = try {
            recorder.stop()
        } catch (e: RestChatException) {
            _stage.value = VoiceStage.IDLE
            return e.message
        }
        try {
            _stage.value = VoiceStage.TRANSCRIBING
            val text = try {
                parseTranscription(transport.generate(textModel(), buildTranscriptionRequest(samples)))
            } catch (e: RestChatException) {
                return e.message
            }
            _stage.value = VoiceStage.WORKING
            sendText(text)?.let { return it }
            val reply = lastReply() ?: return null
            _stage.value = VoiceStage.SPEAKING
            try {
                val pcm = parseSpeechResponse(transport.generate(speechModel(), buildSpeechRequest(reply, voice())))
                output.play(pcm)
            } catch (e: RestChatException) {
                return e.message
            }
            return null
        } catch (e: CancellationException) {
            throw e
        } finally {
            _stage.value = VoiceStage.IDLE
        }
    }

    /** Abandons a recording without sending it. */
    fun cancelRecording() {
        if (_stage.value != VoiceStage.RECORDING) return
        try {
            recorder.stop()
        } catch (_: RestChatException) {
        }
        _stage.value = VoiceStage.IDLE
    }

    /** Cuts the spoken answer short. */
    fun stopSpeaking() {
        if (_stage.value == VoiceStage.SPEAKING) output.stop()
    }
}
