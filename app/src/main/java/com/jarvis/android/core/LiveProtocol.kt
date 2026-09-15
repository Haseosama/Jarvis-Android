package com.jarvis.android.core

import kotlinx.serialization.json.*

/**
 * Wire messages for Gemini's "Live" (BidiGenerateContent) WebSocket API.
 *
 * This mirrors what Mark-LIII's Python `google-genai` SDK sends under the hood —
 * there is no official Kotlin SDK, so this app talks the JSON protocol directly
 * over an OkHttp WebSocket (see [GeminiLiveClient]). Field names follow the
 * proto-JSON (camelCase) mapping used by the public Live API.
 */
object LiveProtocol {

    const val ENDPOINT =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    /** Audio sent TO Gemini: 16-bit PCM, mono, 16 kHz. */
    const val SEND_SAMPLE_RATE = 16000

    /** Audio received FROM Gemini: 16-bit PCM, mono, 24 kHz. */
    const val RECEIVE_SAMPLE_RATE = 24000

    fun buildSetup(
        model: String,
        systemInstruction: String,
        toolDeclarations: List<JsonObject>,
        voiceName: String,
        resumeHandle: String?,
    ): JsonObject = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", model)
            put("generationConfig", buildJsonObject {
                putJsonArray("responseModalities") { add("AUDIO") }
                put("speechConfig", buildJsonObject {
                    put("voiceConfig", buildJsonObject {
                        put("prebuiltVoiceConfig", buildJsonObject {
                            put("voiceName", voiceName)
                        })
                    })
                })
            })
            put("systemInstruction", buildJsonObject {
                putJsonArray("parts") {
                    addJsonObject { put("text", systemInstruction) }
                }
            })
            if (toolDeclarations.isNotEmpty()) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            toolDeclarations.forEach { add(it) }
                        }
                    }
                }
            }
            put("outputAudioTranscription", buildJsonObject {})
            put("inputAudioTranscription", buildJsonObject {})
            put("sessionResumption", buildJsonObject {
                if (resumeHandle != null) put("handle", resumeHandle)
            })
            put("contextWindowCompression", buildJsonObject {
                put("slidingWindow", buildJsonObject {})
            })
        })
    }

    fun buildRealtimeAudio(pcm16: ByteArray): JsonObject = buildJsonObject {
        put("realtimeInput", buildJsonObject {
            put("audio", buildJsonObject {
                put("data", android.util.Base64.encodeToString(pcm16, android.util.Base64.NO_WRAP))
                put("mimeType", "audio/pcm;rate=$SEND_SAMPLE_RATE")
            })
        })
    }

    fun buildClientText(text: String, turnComplete: Boolean = true): JsonObject = buildJsonObject {
        put("clientContent", buildJsonObject {
            putJsonArray("turns") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", text) }
                    }
                }
            }
            put("turnComplete", turnComplete)
        })
    }

    fun buildToolResponse(id: String, name: String, result: String): JsonObject = buildJsonObject {
        put("toolResponse", buildJsonObject {
            putJsonArray("functionResponses") {
                addJsonObject {
                    put("id", id)
                    put("name", name)
                    put("response", buildJsonObject { put("result", result) })
                }
            }
        })
    }
}

/** Parsed events surfaced by [GeminiLiveClient] to the engine. */
sealed class LiveEvent {
    object SetupComplete : LiveEvent()
    data class AudioChunk(val pcm16: ByteArray) : LiveEvent()
    data class OutputTranscript(val text: String) : LiveEvent()
    data class InputTranscript(val text: String) : LiveEvent()
    object TurnComplete : LiveEvent()
    object Interrupted : LiveEvent()
    data class ToolCall(val calls: List<FunctionCall>) : LiveEvent()
    data class ResumptionUpdate(val handle: String) : LiveEvent()
    data class Error(val message: String) : LiveEvent()
    data class Closed(val code: Int, val reason: String) : LiveEvent()
}

data class FunctionCall(val id: String, val name: String, val args: JsonObject)

/** Parses one raw server-frame (JSON text) into zero or more [LiveEvent]s. */
fun parseServerMessage(raw: String): List<LiveEvent> {
    val root = try {
        Json.parseToJsonElement(raw).jsonObject
    } catch (e: Exception) {
        return listOf(LiveEvent.Error("Malformed server frame: ${e.message}"))
    }
    val events = mutableListOf<LiveEvent>()

    if (root.containsKey("setupComplete")) {
        events += LiveEvent.SetupComplete
    }

    root["toolCall"]?.jsonObject?.get("functionCalls")?.jsonArray?.let { calls ->
        val parsed = calls.map { c ->
            val o = c.jsonObject
            FunctionCall(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                args = o["args"]?.jsonObject ?: JsonObject(emptyMap()),
            )
        }
        if (parsed.isNotEmpty()) events += LiveEvent.ToolCall(parsed)
    }

    root["sessionResumptionUpdate"]?.jsonObject?.let { sru ->
        val resumable = sru["resumable"]?.jsonPrimitive?.booleanOrNull ?: false
        val handle = sru["newHandle"]?.jsonPrimitive?.contentOrNull
        if (resumable && handle != null) events += LiveEvent.ResumptionUpdate(handle)
    }

    root["serverContent"]?.jsonObject?.let { sc ->
        if (sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
            events += LiveEvent.Interrupted
        }
        sc["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let {
            events += LiveEvent.OutputTranscript(it)
        }
        sc["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let {
            events += LiveEvent.InputTranscript(it)
        }
        sc["modelTurn"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
            val inline = part.jsonObject["inlineData"]?.jsonObject
            val data = inline?.get("data")?.jsonPrimitive?.contentOrNull
            if (data != null) {
                val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                events += LiveEvent.AudioChunk(bytes)
            }
        }
        if (sc["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
            events += LiveEvent.TurnComplete
        }
    }

    return events
}
