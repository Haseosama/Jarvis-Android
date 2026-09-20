package com.jarvis.android.rest

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatHistoryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun user(text: String) = userTurn(text)
    private fun model(text: String): JsonObject = modelTurn(buildJsonObject {
        put("role", "model")
        put("parts", kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("text", text) }) })
    })

    @Test
    fun `turns that fit are all kept`() {
        val turns = listOf(user("a"), model("b"), user("c"), model("d"))
        assertEquals(turns, trimTurns(turns, 10_000))
    }

    @Test
    fun `trimming drops the oldest turns and starts on a plain user turn`() {
        val turns = listOf(user("a".repeat(400)), model("b".repeat(400)), user("c"), model("d"))
        val kept = trimTurns(turns, 300)
        assertEquals(listOf(user("c"), model("d")), kept)
    }

    @Test
    fun `turns with binary data are never kept`() {
        val image = buildJsonObject {
            put("role", "user")
            put("parts", kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("inlineData", "AAAA") }) })
        }
        val kept = trimTurns(listOf(user("a"), image, model("b")), 10_000)
        assertTrue(kept.none { "inlineData" in it.toString() })
    }

    @Test
    fun `a saved chat is read back and a missing or broken file gives nothing`() {
        val file = File(temporary.root, "chat.json")
        val store = ChatHistoryStore(file)
        assertNull(store.load())
        val chat = SavedChat(listOf(SavedMessage("USER", "salut"), SavedMessage("ASSISTANT", "bonjour")), listOf(user("salut"), model("bonjour")))
        store.save(chat)
        assertEquals(chat, store.load())
        file.writeText("pas du json")
        assertNull(store.load())
        store.save(chat)
        assertNotNull(store.load())
        store.clear()
        assertNull(store.load())
    }
}
