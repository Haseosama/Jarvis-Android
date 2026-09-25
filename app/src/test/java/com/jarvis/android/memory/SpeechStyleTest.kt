package com.jarvis.android.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le tri entre « ce que cette personne aime » et « comment elle veut qu'on lui parle ». Se tromper coûte
 * dans les deux sens : rater une consigne la laisse sans effet, en inventer une transforme un goût en ordre.
 */
class SpeechStyleTest {

    @Test
    fun `a request about how to speak is an instruction`() {
        assertTrue(isSpeechPreference("tutoiement", "Préfère être tutoyé"))
        assertTrue(isSpeechPreference("longueur_reponses", "Réponses courtes, pas de préambule"))
        assertTrue(isSpeechPreference("ton", "Direct, sans formules de politesse"))
        assertTrue(isSpeechPreference("emoji", "N'aime pas les emoji"))
        assertTrue(isSpeechPreference("style_explications", "Vulgariser, éviter le jargon"))
        assertTrue(isSpeechPreference("debit_voix", "Parler un peu plus lentement"))
    }

    @Test
    fun `an ordinary taste stays a fact`() {
        assertFalse(isSpeechPreference("boisson", "Café noir sans sucre"))
        assertFalse(isSpeechPreference("sport", "Court deux fois par semaine"))
        assertFalse(isSpeechPreference("musique", "Écoute du jazz en travaillant"))
        assertFalse(isSpeechPreference("film_prefere", "Blade Runner"))
    }

    @Test
    fun `accents and capitals do not change the verdict`() {
        assertTrue(isSpeechPreference("Ton_préféré", "Familier"))
        assertTrue(isSpeechPreference("TUTOIEMENT", "oui"))
    }

    @Test
    fun `the language rule is left alone`() {
        // Une règle impérative fixe déjà la langue plus haut dans l'invite. Une deuxième consigne, tirée d'un
        // souvenir plus ancien, ne pourrait que la contredire.
        assertFalse(isSpeechPreference("langue", "Français"))
        assertFalse(isSpeechPreference("langue_parlee", "Parle français à la maison"))
        assertFalse(isSpeechPreference("accent", "Accent du sud-ouest"))
    }

    @Test
    fun `an unmistakable word in the value is enough`() {
        // La clé ne dit rien, mais « tutoiement » ne peut pas vouloir dire autre chose.
        assertTrue(isSpeechPreference("habitude", "Il préfère le tutoiement"))
    }

    @Test
    fun `an ambiguous word in the value is not enough`() {
        // « courtes » dans du texte libre parle d'autre chose bien plus souvent que du style de réponse.
        assertFalse(isSpeechPreference("anecdote", "Il aime les réponses courtes de son fils"))
        assertFalse(isSpeechPreference("souvenir", "Une soirée très formelle à Paris"))
    }

    @Test
    fun `a short root must be the whole word`() {
        assertTrue(isSpeechPreference("ton", "Sérieux"))
        assertFalse(isSpeechPreference("tondeuse", "Rangée dans le garage"))
    }

    @Test
    fun `no instruction means no block`() {
        assertEquals(emptyList<String>(), speechStyleBlock(emptyMap()))
        assertEquals(emptyList<String>(), speechStyleBlock(mapOf("boisson" to MemEntry("Thé", "2026-01-01"))))
    }

    @Test
    fun `a blank value is ignored`() {
        assertEquals(emptyList<String>(), speechStyleBlock(mapOf("tutoiement" to MemEntry("", "2026-01-01"))))
    }

    @Test
    fun `the block announces itself as instructions and keeps the freshest first`() {
        val block = speechStyleBlock(
            mapOf(
                "ton" to MemEntry("Direct", "2026-01-05"),
                "tutoiement" to MemEntry("Préfère être tutoyé", "2026-09-20"),
                "boisson" to MemEntry("Café noir", "2026-09-24"),
            )
        )
        assertEquals(SPEECH_STYLE_HEADER, block[0])
        assertEquals("- Tutoiement: Préfère être tutoyé", block[1])
        assertEquals("- Ton: Direct", block[2])
        assertEquals(3, block.size)
    }

    @Test
    fun `underscores become spaces in the label`() {
        val block = speechStyleBlock(mapOf("longueur_reponses" to MemEntry("Courtes", "2026-01-01")))
        assertEquals("- Longueur reponses: Courtes", block[1])
    }

    @Test
    fun `a talkative memory cannot flood the prompt`() {
        val many = (1..20).associate { "style_regle_$it" to MemEntry("Consigne numéro $it", "2026-09-%02d".format(it)) }
        val block = speechStyleBlock(many)
        val lines = block.drop(1)

        assertTrue(lines.size <= SPEECH_STYLE_MAX_LINES)
        assertTrue(lines.sumOf { it.length + 1 } <= SPEECH_STYLE_MAX_CHARS)
        // Les plus récentes d'abord : la vingtième consigne est datée du 20 septembre.
        assertEquals("- Style regle 20: Consigne numéro 20", lines[0])
    }

    @Test
    fun `one oversized instruction does not block the shorter ones behind it`() {
        val block = speechStyleBlock(
            mapOf(
                "style_long" to MemEntry("x".repeat(SPEECH_STYLE_MAX_CHARS + 10), "2026-09-20"),
                "tutoiement" to MemEntry("Oui", "2026-09-19"),
            )
        )
        assertEquals(listOf(SPEECH_STYLE_HEADER, "- Tutoiement: Oui"), block)
    }
}
