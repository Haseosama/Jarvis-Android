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
class GeminiLiveClient(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived socket
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null

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
                )
                webSocket.send(setup.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseServerMessage(text).forEach { trySend(it) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // The Live API sends JSON frames as text; a binary frame here
                // would be unexpected, but decode defensively rather than drop it.
                parseServerMessage(bytes.utf8()).forEach { trySend(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(LiveEvent.Closed(code, reason))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(LiveEvent.Error(t.message ?: "socket failure"))
                close()
            }
        }

        socket = http.newWebSocket(request, listener)

        awaitClose {
            socket?.close(1000, "client closing")
            socket = null
        }
    }

    fun sendAudio(pcm16: ByteArray) {
        socket?.send(LiveProtocol.buildRealtimeAudio(pcm16).toString())
    }

    fun sendText(text: String) {
        socket?.send(LiveProtocol.buildClientText(text).toString())
    }

    fun sendToolResponse(id: String, name: String, result: String) {
        socket?.send(LiveProtocol.buildToolResponse(id, name, result).toString())
    }

    fun close() {
        socket?.close(1000, "client closing")
        socket = null
    }
}
