package com.jarvis.android.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Le rapprochement des mots, sans fichier ni mémoire : ce sont ces règles qui décident si Jarvis retrouve
 * un souvenir à partir d'une question dictée, ou répond qu'il ne sait pas alors qu'il sait.
 */
class MemoryRecallTest {

    private val facts = listOf(
        Triple("identity", "job", "Il est développeur back-end dans une PME de logistique"),
        Triple("identity", "city", "Il habite à Bordeaux depuis 2019"),
        Triple("identity", "birthday", "Né le 14 mars"),
        Triple("relationships", "sister_name", "Sa sœur s'appelle Camille, elle vit à Toulouse"),
        Triple("relationships", "conjointe", "Sa compagne Julie est infirmière"),
        Triple("relationships", "chien", "Il a un berger australien nommé Pixel"),
        Triple("preferences", "boisson_preferee", "Il prend un café noir sans sucre le matin"),
        Triple("preferences", "sport", "Il court deux fois par semaine au parc bordelais"),
        Triple("notes", "medecin", "Son médecin traitant est le docteur Lemoine"),
        Triple("notes", "voiture", "Sa voiture est une Peugeot grise"),
    )

    /** La meilleure réponse à une question, ou null quand rien ne correspond. */
    private fun best(question: String): String? {
        val query = MemoryQuery.of(question)
        return facts
            .map { (category, key, value) -> "$category/$key" to scoreMemoryEntry(query, category, key, value) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun matches(question: String): List<String> {
        val query = MemoryQuery.of(question)
        return facts
            .map { (category, key, value) -> "$category/$key" to scoreMemoryEntry(query, category, key, value) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    @Test
    fun `a spoken question finds the fact it is about`() {
        assertEquals("relationships/sister_name", best("quel est le prénom de ma sœur ?"))
        assertEquals("relationships/chien", best("comment s'appelle mon chien"))
        assertEquals("preferences/boisson_preferee", best("qu'est-ce que je bois le matin ?"))
        assertEquals("notes/medecin", best("le nom de mon docteur"))
        assertEquals("identity/birthday", best("c'est quand mon anniversaire"))
    }

    @Test
    fun `a french question reaches a key written in english by the extractor`() {
        // L'extraction écrit `job`, `city`, `sister_name` ; l'utilisateur, lui, dit « métier » et « ville ».
        assertEquals("identity/job", best("tu sais où je travaille ?"))
        assertEquals("identity/job", best("mon métier"))
        assertEquals("identity/city", best("j'habite où déjà"))
        assertEquals("relationships/conjointe", best("ma compagne"))
    }

    @Test
    fun `plurals and conjugations still match`() {
        assertEquals("notes/voiture", best("voitures"))
        assertEquals("relationships/chien", best("mes chiens"))
        assertEquals("preferences/sport", best("courir"))
    }

    @Test
    fun `a dictation slip still finds the fact`() {
        assertEquals("relationships/sister_name", best("camile"))
        assertEquals("notes/medecin", best("docteur lemoinne"))
    }

    @Test
    fun `filler words of the question bring back nothing on their own`() {
        // « de », « ma », « est »… étaient comptés comme des mots-clés et ramenaient la moitié de la mémoire.
        assertTrue(MemoryQuery.of("est-ce que tu te souviens de ce que je t'ai dit").isEmpty.not())
        assertEquals(listOf("relationships/sister_name"), matches("et le prénom de ma sœur, c'est quoi déjà ?"))
        assertTrue(matches("est-ce que tu peux me dire ce que je t'avais demandé").isEmpty())
    }

    @Test
    fun `an unrelated question brings nothing back`() {
        assertTrue(matches("mon numéro de sécurité sociale").isEmpty())
        assertTrue(matches("la recette du gâteau au chocolat").isEmpty())
        assertTrue(matches("xyzzy").isEmpty())
    }

    @Test
    fun `a question without a single word matches nothing`() {
        assertTrue(MemoryQuery.of("!!!").isEmpty)
        assertEquals(0, scoreMemoryEntry(MemoryQuery.of("!!!"), "notes", "voiture", "Sa voiture est une Peugeot grise"))
    }

    @Test
    fun `a question made only of filler words is searched as it stands`() {
        // Sans repli, « et moi ? » ne chercherait rien du tout.
        assertFalse(MemoryQuery.of("et moi ?").isEmpty)
    }

    @Test
    fun `covering more of the question ranks higher than repeating one word`() {
        val query = MemoryQuery.of("le prénom de la sœur")
        val both = scoreMemoryEntry(query, "relationships", "sister_name", "Sa sœur s'appelle Camille")
        val one = scoreMemoryEntry(query, "relationships", "voisine", "La sœur de la voisine, la sœur de son ami, sa sœur")
        assertTrue("attendu $both > $one", both > one)
    }

    @Test
    fun `accents case and non latin scripts are handled`() {
        assertEquals(memoryTokens("École"), memoryTokens("ecole"))
        assertEquals(memoryTokens("École"), memoryTokens("E\u0301COLE"))
        assertEquals(listOf("東京"), memoryTokens("東京"))
        assertTrue(scoreMemoryEntry(MemoryQuery.of("東"), "notes", "ville", "東京") > 0)
        assertEquals(0, scoreMemoryEntry(MemoryQuery.of("東"), "notes", "boisson", "École et café"))
    }

    @Test
    fun `a plural and its singular reduce to the same stem`() {
        assertEquals(memoryStem("voiture"), memoryStem("voitures"))
        assertEquals(memoryStem("chien"), memoryStem("chiens"))
        assertEquals(memoryStem("livre"), memoryStem("livres"))
        assertEquals(memoryStem("projet"), memoryStem("projets"))
    }

    @Test
    fun `stemming leaves short and non latin words alone`() {
        assertEquals("mer", memoryStem("mer"))
        assertEquals("ami", memoryStem("ami"))
        assertEquals("pays", memoryStem("pays"))
        assertEquals("東京", memoryStem("東京"))
        assertFalse(memoryStem("chat") == memoryStem("chien"))
    }

    @Test
    fun `edit distance stays within its budget`() {
        assertTrue(withinEditDistance("camille", "camile", 1))
        assertTrue(withinEditDistance("camille", "camil", 2))
        assertFalse(withinEditDistance("camille", "camil", 1))
        // Deux mots seulement voisins ne doivent pas se confondre.
        assertFalse(withinEditDistance("camille", "camion", 2))
        assertFalse(withinEditDistance("chat", "chien", 0))
        assertTrue(withinEditDistance("chat", "chat", 0))
        assertFalse(withinEditDistance("a", "abcd", 2))
    }

    @Test
    fun `two spellings of the same fact share a canonical key`() {
        assertEquals(memoryCanonicalKey("sister_name"), memoryCanonicalKey("prenom_soeur"))
        assertEquals(memoryCanonicalKey("sister_name"), memoryCanonicalKey("soeur prénom"))
        assertEquals(memoryCanonicalKey("city"), memoryCanonicalKey("ville"))
        assertEquals(memoryCanonicalKey("job"), memoryCanonicalKey("métier"))
        assertEquals(memoryCanonicalKey("job"), memoryCanonicalKey("travail"))
    }

    @Test
    fun `two different facts keep different keys`() {
        assertFalse(memoryCanonicalKey("sister_name") == memoryCanonicalKey("brother_name"))
        assertFalse(memoryCanonicalKey("ami_paul") == memoryCanonicalKey("ami_pauline"))
        assertFalse(memoryCanonicalKey("city") == memoryCanonicalKey("address"))
        assertFalse(memoryLiteralKey("chien") == memoryLiteralKey("chat"))
    }

    @Test
    fun `the strict key form only forgives spelling`() {
        assertEquals(memoryLiteralKey("Ville"), memoryLiteralKey("ville"))
        assertEquals(memoryLiteralKey("sister name"), memoryLiteralKey("sister_name"))
        assertEquals(memoryLiteralKey("chiens"), memoryLiteralKey("chien"))
        // Le rapprochement par équivalents, lui, n'est pas du ressort de la forme stricte.
        assertFalse(memoryLiteralKey("ville") == memoryLiteralKey("city"))
    }

    @Test
    fun `what defines the person outweighs a passing note`() {
        val today = LocalDate.of(2026, 9, 25)
        val oldFact = memoryPromptScore("relationships", "2025-02-11", today)
        val freshNote = memoryPromptScore("notes", "2026-09-25", today)
        assertTrue("attendu $oldFact > $freshNote", oldFact > freshNote)
    }

    @Test
    fun `freshness decays and survives a missing date`() {
        val today = LocalDate.of(2026, 9, 25)
        assertEquals(1.0, memoryFreshness("2026-09-25", today), 0.001)
        assertEquals(0.5, memoryFreshness("2026-08-26", today), 0.001)
        assertEquals(0.0, memoryFreshness("", today), 0.001)
        assertEquals(0.0, memoryFreshness("0000-00-00", today), 0.001)
        // Une date future ne vaut pas plus qu'aujourd'hui.
        assertEquals(1.0, memoryFreshness("2027-01-01", today), 0.001)
    }

    @Test
    fun `a fresher fact of the same category comes first`() {
        val today = LocalDate.of(2026, 9, 25)
        assertTrue(memoryPromptScore("notes", "2026-09-25", today) > memoryPromptScore("notes", "2025-09-25", today))
        assertTrue(memoryImportance("identity") > memoryImportance("notes"))
    }
}
