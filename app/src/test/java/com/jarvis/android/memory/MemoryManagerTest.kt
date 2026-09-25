package com.jarvis.android.memory

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            expectIoFailure { memory.markLastSessionBriefed() }
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
    fun `a question in french finds a fact stored under an english key`() = runBlocking {
        val memory = MemoryManager(file())
        memory.remember("sister_name", "Sa sœur s'appelle Camille", "relationships")
        memory.remember("job", "Il est développeur back-end", "identity")
        assertTrue(memory.search("quel est le prénom de ma sœur ?").contains("relationships/sister name:"))
        assertTrue(memory.search("où je travaille").contains("identity/job:"))
        // La question ne parle ni du métier ni de la sœur : rien ne doit remonter.
        assertTrue(memory.search("la recette du gâteau au chocolat").startsWith("Nothing stored about"))
    }

    @Test
    fun `an unknown subject answers with the subjects on file`() = runBlocking {
        val memory = MemoryManager(file())
        memory.remember("boisson", "Café noir", "preferences")
        val answer = memory.search("mon numéro de sécurité sociale")
        assertTrue(answer.startsWith("Nothing stored about"))
        // Le modèle peut relancer avec le bon mot au lieu d'affirmer qu'il ne sait rien.
        assertTrue(answer.contains("boisson"))
    }

    @Test
    fun `two spellings of the same fact do not become two contradictory memories`() = runBlocking {
        val memory = MemoryManager(file())
        memory.remember("city", "Bordeaux", "identity")
        memory.remember("ville", "Lyon", "identity")
        assertEquals(1, memory.load().identity.size)
        assertEquals("Lyon", memory.load().identity.getValue("city").value)

        memory.remember("Chien", "Pixel", "relationships")
        memory.remember("chiens", "Pixel, un berger australien", "relationships")
        assertEquals(1, memory.load().relationships.size)

        // Hors identité, deux clés voisines peuvent désigner deux personnes : rien n'est fusionné.
        memory.remember("ami_paul", "Paul, son collègue", "relationships")
        memory.remember("ami_pauline", "Pauline, sa voisine", "relationships")
        assertEquals(3, memory.load().relationships.size)
    }

    @Test
    fun `forgetting a fact works whatever spelling of the key is used`() = runBlocking {
        val memory = MemoryManager(file())
        memory.remember("city", "Bordeaux", "identity")
        assertTrue(memory.forgetChange("ville", "identity").changed)
        assertTrue(memory.load().identity.isEmpty())
    }

    @Test
    fun `a lasting fact is not pushed out of the prompt by fresh throwaway notes`() = runBlocking {
        val filler = "à traiter dans la semaine ".repeat(6)
        file().writeText(
            Json.encodeToString(
                MemoryStore(
                    relationships = mutableMapOf("sister_name" to MemEntry("Sa sœur s'appelle Camille", "2025-02-11")),
                    notes = (1..6).associate {
                        "note$it" to MemEntry("Rappel de passage numéro $it : $filler".take(135), "2026-09-24")
                    }.toMutableMap(),
                )
            )
        )
        val prompt = MemoryManager(file(), now = { day("2026-09-25") }).formatForPrompt()

        assertTrue(prompt.contains("Sister name: Sa sœur s'appelle Camille"))
        // Le budget doit bien être saturé, sinon le test ne prouverait rien : au moins une note est
        // renvoyée à l'index « ALSO REMEMBERED » au lieu d'être écrite en entier.
        assertTrue(prompt.contains("ALSO REMEMBERED"))
    }

    @Test
    fun `every category keeps a place in the prompt`() = runBlocking {
        // Six notes fraîches remplissent exactement le budget : sans réservation par catégorie, les
        // préférences et les relations n'apparaîtraient plus du tout.
        val long = "x".repeat(138)
        file().writeText(
            Json.encodeToString(
                MemoryStore(
                    preferences = mutableMapOf("boisson" to MemEntry("Café noir", "2026-09-01")),
                    relationships = mutableMapOf("soeur" to MemEntry("Camille", "2026-09-01")),
                    notes = (1..6).associate { "note$it" to MemEntry(long, "2026-09-24") }.toMutableMap(),
                )
            )
        )
        val prompt = MemoryManager(file(), now = { day("2026-09-25") }).formatForPrompt()
        assertTrue(prompt.contains("Boisson: Café noir"))
        assertTrue(prompt.contains("Soeur: Camille"))
    }

    private fun day(text: String): Long =
        java.time.LocalDate.parse(text).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `truncation preserves surrogate pairs and reports stored value`() = runBlocking {
        val memory = MemoryManager(file())
        val result = memory.remember("clé", "x".repeat(379) + "\uD83D\uDE00" + "z")
        val value = memory.load().notes.getValue("clé").value
        assertEquals("x".repeat(379) + "…", value)
        assertTrue(result.endsWith(value))
    }

    @Test
    fun `sessions keep a year of history instead of the last three`() = runBlocking {
        val memory = MemoryManager(file())
        repeat(MAX_SESSION_SUMMARIES + 2) { memory.saveSessionSummary("Résumé $it", "fr") }
        val kept = memory.load().sessions.map { it.summary }
        assertEquals(MAX_SESSION_SUMMARIES, kept.size)
        assertEquals("Résumé 2", kept.first())
        assertEquals("Résumé ${MAX_SESSION_SUMMARIES + 1}", kept.last())
    }

    @Test
    fun `a briefed summary stays in history and is not offered twice`() = runBlocking {
        val memory = MemoryManager(file())
        memory.saveSessionSummary("Résumé ancien")
        memory.saveSessionSummary("Résumé récent")
        assertEquals("Résumé récent", memory.peekLastSession()?.summary)
        assertEquals("Résumé récent", memory.markLastSessionBriefed()?.summary)

        // Le briefing supprimait le résumé ; il doit maintenant rester lisible pour les sessions suivantes.
        val reloaded = MemoryManager(file())
        assertEquals(listOf("Résumé ancien", "Résumé récent"), reloaded.load().sessions.map { it.summary })
        assertEquals(listOf(false, true), reloaded.load().sessions.map { it.briefed })
        assertEquals("Résumé ancien", reloaded.peekLastSession()?.summary)

        assertEquals("Résumé ancien", reloaded.markLastSessionBriefed()?.summary)
        assertNull(reloaded.markLastSessionBriefed())
        assertNull(reloaded.peekLastSession())
        assertEquals(2, MemoryManager(file()).load().sessions.size)
    }

    @Test
    fun `an old memory file without the briefed flag still loads`() = runBlocking {
        file().writeText("""{"sessions":[{"date":"2026-01-01","summary":"Ancien format"}]}""")
        val memory = MemoryManager(file())
        assertEquals("Ancien format", memory.peekLastSession()?.summary)
        assertTrue(memory.formatForPrompt().contains("Ancien format"))
    }

    private suspend fun expectIoFailure(block: suspend () -> Any?) {
        try {
            block()
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `backup exports and restores on another manager`() = runBlocking {
        val source = MemoryManager(File(temporary.root, "a.json"))
        source.remember("ville", "Lyon", "identity")
        val text = source.exportJson()
        val target = MemoryManager(File(temporary.root, "b.json"))
        assertEquals(null, target.importJson(text))
        assertEquals("Lyon", target.load().identity.getValue("ville").value)
    }

    @Test
    fun `a bad backup is refused and changes nothing`() = runBlocking {
        val memory = MemoryManager(file())
        memory.remember("ville", "Paris", "identity")
        assertTrue(memory.importJson("pas du json")!!.isNotBlank())
        assertEquals("Paris", memory.load().identity.getValue("ville").value)
    }
}
