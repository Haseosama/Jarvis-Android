package com.jarvis.android.core

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Thin client over Gemini's Live (BidiGenerateContent) WebSocket protocol.
 * Equivalent of the `session` object Mark-LIII gets from `google-genai`'s
 * `client.aio.live.connect(...)` — there is no first-party Android/Kotlin SDK
 * for the Live API, so this talks the JSON wire protocol directly.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val http: WebSocket.Factory = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build(),
) {

    private val lock = Any()
    private var socket: WebSocket? = null
    private var ready = false
    private var closed = false

    /**
     * Opens the socket and sends the initial `setup` message. Emits [LiveEvent]s
     * until the socket closes or [close] is called. Collect on a background
     * dispatcher — one flow per session.
     */
    fun connect(
        model: String,
        systemInstruction: String,
        toolDeclarations: List<JsonObject>,
        voiceName: String,
        resumeHandle: String?,
        languageCode: String? = null,
        tuneSpeechDetection: Boolean = false,
    ): Flow<LiveEvent> = callbackFlow {
        val request = Request.Builder()
            .url("${LiveProtocol.ENDPOINT}?key=$apiKey")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = LiveProtocol.buildSetup(
                    model = model,
                    systemInstruction = systemInstruction,
                    toolDeclarations = toolDeclarations,
                    voiceName = voiceName,
                    resumeHandle = resumeHandle,
                    languageCode = languageCode,
                    tuneSpeechDetection = tuneSpeechDetection,
                )
                synchronized(lock) {
                    if (!closed) webSocket.send(setup.toString())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val events = parseServerMessage(text)
                synchronized(lock) {
                    if (closed) return
                    for (event in events) {
                        if (event is LiveEvent.SetupComplete) ready = true
                        if (event is LiveEvent.Error) ready = false
                        trySend(event)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // The Live API sends JSON frames as text; a binary frame here
                // would be unexpected, but decode defensively rather than drop it.
                onMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(lock) {
                    ready = false
                    closed = true
                    webSocket.close(code, reason)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(lock) {
                    ready = false
                    closed = true
                }
                trySend(LiveEvent.Closed(code, reason))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(lock) {
                    ready = false
                    closed = true
                }
                trySend(LiveEvent.Error("Connexion Gemini interrompue. Vérifiez le réseau puis réessayez."))
                close()
            }
        }

        synchronized(lock) {
            if (closed) {
                close()
            } else {
                socket = http.newWebSocket(request, listener)
            }
        }

        awaitClose {
            this@GeminiLiveClient.close()
        }
    }

    fun sendAudio(pcm16: ByteArray): Boolean = sendWhenReady {
        LiveProtocol.buildRealtimeAudio(pcm16)
    }

    fun sendVideoFrame(jpeg: ByteArray): Boolean = sendWhenReady {
        LiveProtocol.buildRealtimeVideo(jpeg)
    }

    fun sendText(text: String): Boolean = sendWhenReady {
        LiveProtocol.buildClientText(text)
    }

    fun sendToolResponse(id: String, name: String, result: String): Boolean = sendWhenReady {
        LiveProtocol.buildToolResponse(id, name, result)
    }

    private fun sendWhenReady(message: () -> JsonObject): Boolean = synchronized(lock) {
        if (closed || !ready) false else socket?.send(message().toString()) ?: false
    }

    fun close() {
        synchronized(lock) {
            ready = false
            closed = true
            socket?.close(1000, "client closing")
            socket = null
        }
    }
}
