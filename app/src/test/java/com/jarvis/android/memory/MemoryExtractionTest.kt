package com.jarvis.android.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryExtractionTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `a json answer gives a summary and facts by category`() {
        val e = parseExtraction("""{"summary":"Il a parlé de son voyage.","facts":{"identity":{"Prénom":"Léa"},"preferences":{"boisson favorite":"thé vert"},"inconnu":{"x":"y"}}}""")!!
        assertEquals("Il a parlé de son voyage.", e.summary)
        assertEquals(mapOf("prenom" to "Léa"), e.facts["identity"])
        assertEquals(mapOf("boisson_favorite" to "thé vert"), e.facts["preferences"])
        assertNull(e.facts["inconnu"])
    }

    @Test fun `a code fence is accepted and a plain sentence is the summary`() {
        assertEquals("ok", parseExtraction("```json\n{\"summary\":\"ok\",\"facts\":{}}\n```")!!.summary)
        val plain = parseExtraction("Il a demandé la météo.")!!
        assertEquals("Il a demandé la météo.", plain.summary)
        assertTrue(plain.facts.isEmpty())
        assertNull(parseExtraction("   "))
        assertNull(parseExtraction("""{"summary":"","facts":{}}"""))
    }

    @Test fun `sensitive values are refused and the count is capped`() {
        val many = (1..12).joinToString(",") { "\"k$it\":\"v$it\"" }
        val e = parseExtraction("""{"summary":"s","facts":{"notes":{"carte":"4970 1234 5678 9012","code":"mon mot de passe est x",$many}}}""")!!
        val notes = e.facts.getValue("notes")
        assertEquals(MAX_EXTRACTED_FACTS, notes.size)
        assertTrue("carte" !in notes && "code" !in notes)
    }

    @Test fun `the request asks for json and lists what is known`() {
        val text = buildExtractionRequest("Utilisateur : bonjour", "identity/name: Léa").toString()
        assertTrue(text.contains("application/json"))
        assertTrue(text.contains("identity/name: Léa"))
    }

    @Test fun `keys are cleaned`() {
        assertEquals("annee_de_naissance", cleanFactKey(" Année de naissance! "))
        assertEquals("", cleanFactKey("???"))
        assertEquals(40, cleanFactKey("a".repeat(80)).length)
    }

    @Test fun `waiting conversations are kept until processed, the newest few for a week`() {
        val pending = PendingTranscripts(File(tmp.root, "p"), maxFiles = 2, maxAgeMs = 1000)
        val now = 1_700_000_000_000L
        val a = pending.add("un", now - 10)
        pending.add("deux", now - 5)
        pending.add("trois", now)
        val listed = pending.list(now)
        assertEquals(2, listed.size)
        assertTrue(!a.exists())
        assertEquals("trois", listed.last().readText())
    }

    @Test fun `expired conversations are dropped`() {
        val pending = PendingTranscripts(File(tmp.root, "q"), maxAgeMs = 1000)
        val old = pending.add("vieux", 1L)
        old.setLastModified(1L)
        assertTrue(pending.list(1_000_000L).isEmpty())
        assertTrue(!old.exists())
    }

    @Test fun `recent conversations reach the prompt and survive a reload`() = runBlocking {
        val file = File(tmp.root, "m.json")
        val memory = MemoryManager(file)
        assertEquals("", memory.formatForPrompt())
        memory.saveSessionSummary("Il a préparé son voyage à Lisbonne.")
        memory.update(mapOf("identity" to mapOf("name" to "Léa")))
        val reloaded = MemoryManager(file).formatForPrompt()
        assertNotNull(reloaded)
        assertTrue(reloaded.contains("Name: Léa"))
        assertTrue(reloaded.contains("Il a préparé son voyage à Lisbonne."))
    }

    @Test fun `a summary alone is enough to build a prompt`() = runBlocking {
        val memory = MemoryManager(File(tmp.root, "s.json"))
        memory.saveSessionSummary("Il a demandé la météo.")
        assertTrue(memory.formatForPrompt().contains("Il a demandé la météo."))
    }
}
