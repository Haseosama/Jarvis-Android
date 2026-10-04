package com.jarvis.android.recipes

import com.jarvis.android.offline.OfflineAction
import com.jarvis.android.offline.interpret
import com.jarvis.android.text.normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecipesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val crepes = Recipe("Crêpes", 4, listOf("250 g de farine", "4 œufs", "50 cl de lait"), listOf("Mélanger la farine et les œufs.", "Ajouter le lait petit à petit.", "Laisser reposer 1 h.", "Cuire 2 minutes de chaque côté."))

    @Test
    fun `items are split, cleaned of their numbers and bullets`() {
        assertEquals(listOf("250 g de farine", "4 œufs", "50 cl de lait"), splitItems("1. 250 g de farine | 2) 4 œufs\n- 50 cl de lait | "))
        assertEquals(listOf("Cuire"), splitItems("• Cuire"))
    }

    @Test
    fun `each step says where we are, offers a timer for its duration, and the last one says so`() {
        assertEquals("Étape 1 sur 4 : Mélanger la farine et les œufs.", stepText(crepes, 0))
        assertEquals("Étape 3 sur 4 : Laisser reposer 1 h. Dites « minuteur de 60 minutes » si vous en voulez un.", stepText(crepes, 2))
        assertTrue(stepText(crepes, 3).endsWith("Dites « minuteur de 2 minutes » si vous en voulez un. C'était la dernière étape : bon appétit !"))
        assertEquals(20, stepMinutes("Enfourner 20 à 25 minutes"))
        assertNull(stepMinutes("Saler et poivrer"))
        assertEquals("d'omelette aux tomates", ofTitle("Omelette aux tomates"))
        assertEquals("de crêpes", ofTitle("Crêpes"))
        assertEquals("Crêpes pour 4 : 3 ingrédients — 250 g de farine, 4 œufs, 50 cl de lait.", ingredientsText(crepes))
    }

    @Test
    fun `the recipe under way lasts four hours, kept recipes are found by a word of their title`() {
        val store = RecipeStore(File(tmp.root, "r.json"))
        store.setCurrent(RecipeSession(crepes, 1, 1_000))
        assertEquals(1, store.current(now = 1_000 + 60_000)?.step)
        assertNull(store.current(now = 1_000 + RECIPE_IDLE_MS))
        assertTrue(store.keep(crepes))
        assertTrue(store.keep(crepes.copy(title = "Crêpes de ma grand-mère")))
        assertEquals("Crêpes", store.find("crêpes")?.title)
        assertEquals("Crêpes de ma grand-mère", store.find("recette des crêpes de grand-mère")?.title)
        assertNull(store.find("gaufres"))
        assertTrue(store.forget("crêpes"))
        assertEquals(listOf("Crêpes de ma grand-mère"), RecipeStore(File(tmp.root, "r.json")).saved().map { it.title })
    }

    @Test
    fun `offline, next means the next step only while cooking`() {
        assertEquals(mapOf("action" to "next"), recipePhrase(normalize("Étape suivante"), cooking = true))
        assertEquals(mapOf("action" to "goto", "step" to "3"), recipePhrase(normalize("l'étape 3"), cooking = true))
        assertEquals(mapOf("action" to "repeat"), recipePhrase(normalize("Répète"), cooking = true))
        assertNull(recipePhrase(normalize("suivant"), cooking = false))
        assertEquals(mapOf("action" to "load", "name" to "crepes"), recipePhrase(normalize("Ma recette de crêpes"), cooking = false))
        RecipeLive.lastUsed = 0
        assertFalse(RecipeLive.cooking())
        // Not cooking: "suivant" is still the next song.
        assertFalse((interpret("suivant") as? OfflineAction.ToolCall)?.name == "recipe")
    }
}
