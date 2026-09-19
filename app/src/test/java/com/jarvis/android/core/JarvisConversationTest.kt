package com.jarvis.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisConversationTest {
    @Test
    fun `transcription fragments are visible and merged without mutating history`() {
        val first = appendConversation(emptyList(), ConversationRole.USER, "Bon")
        val next = appendConversation(first, ConversationRole.USER, "jour")
        assertEquals("Bon", first.single().text)
        assertEquals("Bonjour", next.single().text)
        assertFalse(next.single().complete)
    }

    @Test
    fun `role changes finish the previous message`() {
        val input = appendConversation(emptyList(), ConversationRole.USER, "Bonjour")
        val output = appendConversation(input, ConversationRole.ASSISTANT, "Salut")
        assertEquals(2, output.size)
        assertTrue(output.first().complete)
        assertFalse(output.last().complete)
    }

    @Test
    fun `turn completion is idempotent and keeps later turns separate`() {
        val first = appendConversation(emptyList(), ConversationRole.ASSISTANT, "Un")
        val finished = finishConversationTurn(first)
        assertEquals(finished, finishConversationTurn(finished))
        val second = appendConversation(finished, ConversationRole.ASSISTANT, "Deux")
        assertEquals(listOf("Un", "Deux"), second.map { it.text })
    }

    @Test
    fun `typed messages remain separate from microphone fragments`() {
        val voice = appendConversation(emptyList(), ConversationRole.USER, "Voix")
        val typed = appendConversation(voice, ConversationRole.USER, "Texte", complete = true)
        val next = appendConversation(typed, ConversationRole.USER, "Suite")
        assertEquals(listOf("Voix", "Texte", "Suite"), next.map { it.text })
        assertTrue(next[0].complete)
        assertTrue(next[1].complete)
        assertFalse(next[2].complete)
    }

    @Test
    fun `empty fragments do not change the conversation`() {
        val first = appendConversation(emptyList(), ConversationRole.USER, "Bonjour")
        assertEquals(first, appendConversation(first, ConversationRole.ASSISTANT, ""))
    }

    @Test
    fun `conversation retains only the most recent bounded messages`() {
        var messages = emptyList<ConversationMessage>()
        repeat(MAX_CONVERSATION_MESSAGES + 5) {
            messages = appendConversation(messages, ConversationRole.USER, "$it", complete = true)
        }
        assertEquals(MAX_CONVERSATION_MESSAGES, messages.size)
        assertEquals("5", messages.first().text)
    }

    @Test
    fun `individual messages and streamed fragments are bounded`() {
        val first = appendConversation(emptyList(), ConversationRole.USER, "a".repeat(MAX_MESSAGE_CHARS + 5))
        val next = appendConversation(first, ConversationRole.USER, "encore")
        assertEquals(MAX_MESSAGE_CHARS, first.single().text.length)
        assertEquals(first, next)
    }
}
