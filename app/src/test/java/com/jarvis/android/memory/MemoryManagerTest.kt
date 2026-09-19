package com.jarvis.android.memory

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class MemoryManagerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun file() = File(temporary.root, "memory.json")

    @Test
    fun `missing file is empty and reads do not create it`() = runBlocking {
        val memory = MemoryManager(file())
        assertTrue(memory.load().categories().values.all { it.isEmpty() })
        assertEquals("", memory.formatForPrompt())
        assertFalse(file().exists())
    }

    @Test
    fun `writes replace an existing file and survive a new manager`() = runBlocking {
        val memory = MemoryManager(file())
        memory.remember("ville", "Paris", "identity")
        memory.remember("ville", "Lyon", "identity")
        memory.remember("boisson", "Thé", "preferences")
        val loaded = MemoryManager(file()).load()
        assertEquals("Lyon", loaded.identity.getValue("ville").value)
        assertEquals("Thé", loaded.preferences.getValue("boisson").value)
        assertEquals(listOf("memory.json"), temporary.root.list()!!.toList())
    }

    @Test
    fun `corrupt incompatible and empty files are never overwritten`() = runBlocking {
        val invalid = listOf("", "{", "null", "[]", "{\"notes\":null}", "{\"unknown\":{}}", "{\"notes\":{\"x\":{\"value\":4}}}")
        for (text in invalid) {
            file().writeText(text)
            val memory = MemoryManager(file())
            expectIoFailure { memory.load() }
            expectIoFailure { memory.remember("clé", "valeur") }
            expectIoFailure { memory.forget("clé") }
            expectIoFailure { memory.update(mapOf("notes" to mapOf("clé" to "valeur"))) }
            expectIoFailure { memory.saveSessionSummary("Résumé") }
            expectIoFailure { memory.popLastSession() }
            assertEquals(text, file().readText())
        }
    }

    @Test
    fun `invalid utf8 is preserved`() = runBlocking {
        val bytes = byteArrayOf(0xC3.toByte(), 0x28)
        file().writeBytes(bytes)
        expectIoFailure { MemoryManager(file()).remember("clé", "valeur") }
        assertTrue(bytes.contentEquals(file().readBytes()))
    }

    @Test
    fun `oversized existing file is preserved`() = runBlocking {
        val text = " ".repeat(200_001)
        file().writeText(text)
        expectIoFailure { MemoryManager(file()).remember("clé", "valeur") }
        assertEquals(text, file().readText())
    }

    @Test
    fun `capacity failure preserves all facts and reports refusal`() = runBlocking {
        val memory = MemoryManager(file())
        memory.remember("ancien", "À conserver")
        val original = file().readText()
        var warning = ""
        expectIoFailure {
            memory.update(mapOf("notes" to (1..600).associate { "clé$it" to "x".repeat(380) })) {
                warning = it
            }
        }
        assertTrue(warning.contains("refusée"))
        assertEquals(original, file().readText())
    }

    @Test
    fun `unreadable path is not treated as an empty store`() = runBlocking {
        assertTrue(file().mkdir())
        File(file(), "preserved").writeText("intact")
        expectIoFailure { MemoryManager(file()).remember("clé", "valeur") }
        assertEquals("intact", File(file(), "preserved").readText())
    }

    @Test
    fun `overwrite undo restores exact old value and date`() = runBlocking {
        val original = MemEntry("Paris", "1999-01-02")
        file().writeText(Json.encodeToString(MemoryStore(identity = mutableMapOf("ville" to original))))
        val memory = MemoryManager(file())
        val change = memory.rememberChange("ville", "Lyon", " IDENTITY ")
        assertEquals("identity", change.category)
        memory.restore(change)
        assertEquals(original, MemoryManager(file()).load().identity["ville"])
    }

    @Test
    fun `unknown category falls back to notes including undo`() = runBlocking {
        val memory = MemoryManager(file())
        val change = memory.rememberChange("clé", "valeur", "inconnue")
        assertEquals("notes", change.category)
        memory.restore(change)
        assertTrue(memory.load().notes.isEmpty())
    }

    @Test
    fun `delete undo restores exact entry without touching other keys`() = runBlocking {
        val memory = MemoryManager(file())
        memory.remember("clé", "valeur", "projects")
        val before = memory.load().projects.getValue("clé")
        val change = memory.forgetChange("clé", " PROJECTS ")
        memory.remember("autre", "indépendant")
        memory.restore(change)
        assertEquals(before, memory.load().projects["clé"])
        assertEquals("indépendant", memory.load().notes.getValue("autre").value)
    }

    @Test
    fun `no op changes are identifiable and do not rewrite`() = runBlocking {
        val memory = MemoryManager(file())
        memory.remember("clé", "valeur")
        val original = file().readText()
        assertFalse(memory.rememberChange("clé", "valeur").changed)
        assertFalse(memory.forgetChange("absente").changed)
        assertEquals(original, file().readText())
    }

    @Test
    fun `undo refuses to overwrite an intervening edit`() = runBlocking {
        val memory = MemoryManager(file())
        val change = memory.rememberChange("clé", "première")
        memory.remember("clé", "seconde")
        try {
            memory.restore(change)
            throw AssertionError("Expected conflict")
        } catch (_: IllegalStateException) {
            assertEquals("seconde", memory.load().notes.getValue("clé").value)
        }
    }

    @Test
    fun `undo cannot overwrite a corrupt file`() = runBlocking {
        val memory = MemoryManager(file())
        val change = memory.rememberChange("clé", "valeur")
        file().writeText("broken")
        expectIoFailure { memory.restore(change) }
        assertEquals("broken", file().readText())
    }

    @Test
    fun `search handles accents combining marks non latin and single characters`() = runBlocking {
        val memory = MemoryManager(file())
        memory.remember("boisson", "École et café")
        memory.remember("ville", "東京")
        memory.remember("langue", "Русский")
        for (query in listOf("école", "ECOLE", "e\u0301cole", "cafe")) {
            assertTrue(memory.search(query).contains("notes/boisson:"))
            assertFalse(memory.search(query).contains("notes/ville:"))
        }
        assertTrue(memory.search("東").contains("notes/ville:"))
        assertFalse(memory.search("東").contains("notes/boisson:"))
        assertTrue(memory.search("РУССКИЙ").contains("notes/langue:"))
        assertFalse(memory.search("!!!").contains("notes/"))
        assertTrue(memory.search("").contains("notes/boisson:"))
    }

    @Test
    fun `truncation preserves surrogate pairs and reports stored value`() = runBlocking {
        val memory = MemoryManager(file())
        val result = memory.remember("clé", "x".repeat(379) + "\uD83D\uDE00" + "z")
        val value = memory.load().notes.getValue("clé").value
        assertEquals("x".repeat(379) + "…", value)
        assertTrue(result.endsWith(value))
    }

    @Test
    fun `sessions retain last three and pop persists`() = runBlocking {
        val memory = MemoryManager(file())
        repeat(4) { memory.saveSessionSummary("Résumé $it", "fr") }
        assertEquals(listOf("Résumé 1", "Résumé 2", "Résumé 3"), memory.load().sessions.map { it.summary })
        assertEquals("Résumé 3", memory.popLastSession()?.summary)
        assertEquals(2, MemoryManager(file()).load().sessions.size)
    }

    private suspend fun expectIoFailure(block: suspend () -> Any?) {
        try {
            block()
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
        }
    }
}
