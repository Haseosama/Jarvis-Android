package com.jarvis.android.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GeminiLiveClientTest {

    @Test
    fun `text tool and audio send nothing before onOpen`() = withSession {
        assertEquals("test-key", factory.request.url.queryParameter("key"))
        assertTrue(socket.messages.isEmpty())

        client.sendText("hello")
        assertTrue(socket.messages.isEmpty())
        client.sendToolResponse("id-1", "web_search", "result")
        assertTrue(socket.messages.isEmpty())
        client.sendAudio(byteArrayOf(1, 2, 3, 4))
        assertTrue(socket.messages.isEmpty())
    }

    @Test
    fun `onOpen sends only setup until setupComplete allows text and tools`() = withSession {
        open()
        assertSetupOnly()

        client.sendText("too early")
        assertSetupOnly()
        client.sendToolResponse("early-id", "web_search", "too early")
        assertSetupOnly()

        completeSetup()
        assertSetupOnly()
        assertTextAndToolAllowed()
    }

    @Test
    fun `close blocks sends after a completed handshake`() = withSession {
        open()
        completeSetup()
        assertTextAndToolAllowed()
        val before = socket.messages.toList()

        client.close()
        assertTextAndToolBlocked(before)
    }

    @Test
    fun `remote onClosing immediately blocks sends`() = withSession {
        open()
        completeSetup()
        assertTextAndToolAllowed()
        val before = socket.messages.toList()

        factory.listener.onClosing(socket, 1000, "remote closing")
        assertTextAndToolBlocked(before)
    }

    @Test
    fun `onFailure immediately blocks sends`() = withSession {
        open()
        completeSetup()
        assertTextAndToolAllowed()
        val before = socket.messages.toList()

        factory.listener.onFailure(socket, IOException("connection lost"), null)
        assertTextAndToolBlocked(before)
    }

    @Test
    fun `close before onOpen prevents a late callback from sending setup`() = withSession {
        client.close()
        assertTrue(socket.messages.isEmpty())

        open()
        assertTrue(socket.messages.isEmpty())
        assertTextAndToolBlocked(emptyList())
    }

    private fun withSession(block: suspend Session.() -> Unit) = runBlocking {
        withTimeout(5_000) {
            val session = Session()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                session.client.connect(
                    model = "models/test-model",
                    systemInstruction = "be terse",
                    toolDeclarations = emptyList(),
                    voiceName = "Aoede",
                    resumeHandle = null,
                ).collect { event ->
                    if (event == LiveEvent.SetupComplete) {
                        session.setupComplete.complete(Unit)
                    }
                }
            }
            try {
                session.factory.created.await()
                session.block()
            } finally {
                collector.cancelAndJoin()
                session.client.close()
            }
        }
    }

    private class Session {
        val factory = FakeWebSocketFactory()
        val client = GeminiLiveClient(apiKey = "test-key", http = factory)
        val setupComplete = CompletableDeferred<Unit>()
        val socket: FakeWebSocket
            get() = factory.socket

        fun open() {
            val response = Response.Builder()
                .request(factory.request)
                .protocol(Protocol.HTTP_1_1)
                .code(101)
                .message("Switching Protocols")
                .build()
            factory.listener.onOpen(socket, response)
        }

        suspend fun completeSetup() {
            factory.listener.onMessage(socket, """{"setupComplete":{}}""")
            setupComplete.await()
        }

        fun assertSetupOnly() {
            assertEquals(1, socket.messages.size)
            assertEquals(
                LiveProtocol.buildSetup(
                    model = "models/test-model",
                    systemInstruction = "be terse",
                    toolDeclarations = emptyList(),
                    voiceName = "Aoede",
                    resumeHandle = null,
                ),
                Json.parseToJsonElement(socket.messages.single() as String),
            )
        }

        fun assertTextAndToolAllowed() {
            val before = socket.messages.toList()
            client.sendText("hello")
            client.sendToolResponse("id-1", "web_search", "result")
            assertEquals(before.size + 2, socket.messages.size)
            assertEquals(before, socket.messages.take(before.size))
            assertEquals(
                LiveProtocol.buildClientText("hello"),
                Json.parseToJsonElement(socket.messages[before.size] as String),
            )
            assertEquals(
                LiveProtocol.buildToolResponse("id-1", "web_search", "result"),
                Json.parseToJsonElement(socket.messages[before.size + 1] as String),
            )
        }

        fun assertTextAndToolBlocked(before: List<Any>) {
            client.sendText("blocked")
            assertEquals(before, socket.messages)
            client.sendToolResponse("blocked-id", "web_search", "blocked")
            assertEquals(before, socket.messages)
        }
    }

    private class FakeWebSocketFactory : WebSocket.Factory {
        val created = CompletableDeferred<Unit>()
        lateinit var request: Request
            private set
        lateinit var listener: WebSocketListener
            private set
        lateinit var socket: FakeWebSocket
            private set

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.request = request
            this.listener = listener
            socket = FakeWebSocket(request)
            created.complete(Unit)
            return socket
        }
    }

    private class FakeWebSocket(private val originalRequest: Request) : WebSocket {
        val messages = mutableListOf<Any>()
        val closeCalls = mutableListOf<Pair<Int, String?>>()
        var cancelCalls = 0
            private set

        override fun request(): Request = originalRequest

        override fun queueSize(): Long = 0L

        override fun send(text: String): Boolean {
            messages.add(text)
            return true
        }

        override fun send(bytes: ByteString): Boolean {
            messages.add(bytes)
            return true
        }

        override fun close(code: Int, reason: String?): Boolean {
            closeCalls.add(code to reason)
            return true
        }

        override fun cancel() {
            cancelCalls++
        }
    }
}
