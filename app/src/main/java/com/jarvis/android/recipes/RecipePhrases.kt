package com.jarvis.android.recipes

/*
 * The offline phrasings of a recipe being cooked, on normalized text. "Suivant" or "répète" mean the recipe only while
 * one is under way (otherwise "suivant" is the next song); "ma recette de crêpes" works at any time.
 */

private val NEXT = Regex("^(?:jarvis )?(?:etape suivante|suivant|suivante|et apres|ensuite|la suite|prochaine etape|on continue|c est fait etape suivante)$")
private val PREVIOUS = Regex("^(?:jarvis )?(?:etape precedente|l etape d avant|reviens en arriere|retour|precedent|precedente)$")
private val REPEAT = Regex("^(?:jarvis )?(?:repete|repete l etape|tu peux repeter|redis moi|redis|pardon|quoi)$")
private val GOTO = Regex("^(?:jarvis )?(?:va a l |passe a l |l )?etape (\\d{1,2})$")
private val INGREDIENTS = Regex("^(?:jarvis )?(?:les ingredients|quels ingredients|il faut quoi|redis moi les ingredients)$")
private val STOP = Regex("^(?:jarvis )?(?:fin de la recette|arrete la recette|recette terminee|c est pret|j ai fini la recette)$")
private val LOAD = Regex("^(?:jarvis )?(?:lis |ouvre |on fait |on fait la |)?(?:ma|la) recette (?:de |des |du |d )(.+)$")
private val TIMERS_OFF = Regex("^(?:jarvis )?(?:pas de minuteurs?|sans minuteurs?|arrete les minuteurs automatiques|plus de minuteurs?)$")
private val TIMERS_ON = Regex("^(?:jarvis )?(?:remets les minuteurs|minuteurs automatiques|relance les minuteurs automatiques)$")
private val SAVE = Regex("^(?:jarvis )?(?:garde|enregistre|sauvegarde|note) cette recette$")

/** The recipe action (with its arguments) for [n], or null. [cooking]: a recipe is under way. */
internal fun recipePhrase(n: String, cooking: Boolean): Map<String, String>? {
    LOAD.matchEntire(n)?.let { return mapOf("action" to "load", "name" to it.groupValues[1]) }
    if (!cooking) return null
    return when {
        NEXT.matches(n) -> mapOf("action" to "next")
        PREVIOUS.matches(n) -> mapOf("action" to "previous")
        REPEAT.matches(n) -> mapOf("action" to "repeat")
        INGREDIENTS.matches(n) -> mapOf("action" to "ingredients")
        STOP.matches(n) -> mapOf("action" to "stop")
        SAVE.matches(n) -> mapOf("action" to "save")
        TIMERS_OFF.matches(n) -> mapOf("action" to "timers_off")
        TIMERS_ON.matches(n) -> mapOf("action" to "timers_on")
        else -> GOTO.matchEntire(n)?.let { mapOf("action" to "goto", "step" to it.groupValues[1]) }
    }
}
