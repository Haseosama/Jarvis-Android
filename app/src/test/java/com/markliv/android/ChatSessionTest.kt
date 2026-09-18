package com.markliv.android

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionTest {

    private class StubClient : GeminiClient() {
        val received = mutableListOf<List<ChatMessage>>()

        override suspend fun generate(apiKey: String, model: String, messages: List<ChatMessage>): String {
            received.add(messages)
            return "Réponse ${received.size}"
        }
    }

    private fun sessionWithTurns(): Pair<ChatSession, StubClient> {
        val client = StubClient()
        val session = ChatSession(client)
        runBlocking { session.send("key", "gemini", "Premier") }
        return Pair(session, client)
    }

    @Test
    fun sendsDraftWithContextAndAppendsTurn() {
        val (session, client) = sessionWithTurns()
        assertEquals(listOf<ChatMessage>(ChatMessage("user", "Premier")), client.received[0])
        runBlocking { session.send("key", "gemini", "Second") }
        assertEquals(
            listOf(
                ChatMessage("user", "Premier"),
                ChatMessage("assistant", "Réponse 1"),
                ChatMessage("user", "Second")
            ),
        client.received[1]
    )
    assertEquals(4, session.messages.size)
}

    @Test
    fun trimsDraftAndStoredTexts() {
        val client = StubClient()
        val session = ChatSession(client)
        runBlocking { session.send("key", "gemini", "  Bonjour  ") }
        assertEquals(ChatMessage("user", "Bonjour"), client.received[0].first())
        assertEquals(ChatMessage("assistant", "Réponse 1"), session.messages[1])
    }

    @Test
    fun rejectsBlankDraftWithoutNetworkCall() {
        val client = StubClient()
        val session = ChatSession(client)
        assertThrows(GeminiException::class.java) {
            runBlocking { session.send("key", "gemini", "   ") }
        }
        assertEquals(0, client.received.size)
        assertEquals(0, session.messages.size)
    }

    @Test
    fun resetClearsHistory() {
        val (session, client) = sessionWithTurns()
        session.reset()
        runBlocking { session.send("key", "gemini", "Après reset") }
        assertEquals(listOf<ChatMessage>(ChatMessage("user", "Après reset")), client.received[1])
        assertEquals(2, session.messages.size)
    }
}
