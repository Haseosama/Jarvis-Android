package com.jarvis.android.offline

import com.jarvis.android.core.ConversationMessage
import com.jarvis.android.core.ConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPromptTest {
    private fun user(t: String) = ConversationMessage(ConversationRole.USER, t, complete = true)
    private fun bot(t: String) = ConversationMessage(ConversationRole.ASSISTANT, t, complete = true)
    private fun sys(t: String) = ConversationMessage(ConversationRole.SYSTEM, t, complete = true)

    @Test fun `only what was said is kept, the newest turns, none of the system's own lines`() {
        val messages = (1..10).map { user("message $it") } + sys("annonce")
        val kept = recentOfflineExchanges(messages, turns = 2)
        assertEquals(listOf("message 7", "message 8", "message 9", "message 10"), kept.map { it.text })
        assertTrue(recentOfflineExchanges(listOf(sys("seul"))).isEmpty())
    }

    @Test fun `the prompt carries the system instruction, the history, and ends on the new question`() {
        val history = listOf(user("Quel temps fait-il ?"), bot("Je ne sais pas dire, hors ligne."))
        val prompt = buildLocalPrompt(history, "Et demain ?")
        assertTrue(prompt.startsWith(LOCAL_SYSTEM_PROMPT))
        assertTrue(prompt.contains("Utilisateur : Quel temps fait-il ?"))
        assertTrue(prompt.contains("Jarvis : Je ne sais pas dire, hors ligne."))
        assertTrue(prompt.trimEnd().endsWith("Utilisateur : Et demain ?\nJarvis :".trimEnd()))
    }

    @Test fun `a long history entry and a long question are shortened, and newlines are flattened`() {
        val history = listOf(user("x".repeat(2000) + "\nune deuxième ligne"))
        val prompt = buildLocalPrompt(history, "y".repeat(2000))
        assertFalse(prompt.contains("une deuxième ligne\n"))                    // the embedded newline was flattened away
        val questionLine = prompt.lines().last { it.startsWith("Utilisateur :") && it.contains("y") }
        assertTrue(questionLine.length <= LOCAL_QUESTION_CHARS + "Utilisateur : ".length)
    }

    @Test fun `an empty history still gives a well-formed prompt`() {
        val prompt = buildLocalPrompt(emptyList(), "Bonjour")
        assertEquals(LOCAL_SYSTEM_PROMPT + "\n\nUtilisateur : Bonjour\nJarvis :", prompt)
    }
}
