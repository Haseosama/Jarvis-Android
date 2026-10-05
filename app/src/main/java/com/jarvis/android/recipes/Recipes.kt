package com.jarvis.android.recipes

import com.jarvis.android.text.normalize
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/*
 * Cooking hands-free: a recipe (from the model, or kept from last time) read one step at a time — "étape suivante",
 * "répète", "l'étape d'avant" — even offline once it has started; a step with a duration starts its own timer the first
 * time it is read (unless "pas de minuteur"); the missing ingredients go on the shopping list, and a recipe the user
 * liked can be kept ("garde cette recette") and read again later ("ma recette de crêpes").
 */

internal const val RECIPE_IDLE_MS = 4 * 60 * 60_000L
internal const val MAX_SAVED_RECIPES = 50

/** When the recipe under way was last touched, so the offline commands know "suivant" means the next step. */
internal object RecipeLive {
    @Volatile var lastUsed = 0L

    fun cooking(now: Long = System.currentTimeMillis()): Boolean = now - lastUsed < RECIPE_IDLE_MS
}

@Serializable
internal data class Recipe(val title: String, val servings: Int = 0, val ingredients: List<String>, val steps: List<String>)

@Serializable
internal data class RecipeSession(
    val recipe: Recipe,
    val step: Int = 0,
    val updatedAt: Long = 0,
    /** Steps whose timer was already started, so "répète" or going back does not start it twice. */
    val timedSteps: List<Int> = emptyList(),
    /** Off after "pas de minuteur": a step with a duration only offers its timer. */
    val autoTimers: Boolean = true,
)

@Serializable
private data class RecipeBook(val current: RecipeSession? = null, val saved: List<Recipe> = emptyList())

/** "200 g de farine | 3 œufs" or one per line, numbered or not: the items, cleaned. */
internal fun splitItems(text: String): List<String> =
    text.split('|', '\n', ';')
        .map { it.trim().replace(Regex("^(?:\\d{1,2}\\s*[.)\\-]|[-•*])\\s*"), "").trim() }
        .filter { it.isNotEmpty() }
        .map { it.take(300) }
        .take(60)

private val DURATION = Regex("(\\d{1,3})\\s*(?:à\\s*\\d{1,3}\\s*)?(min(?:utes?)?\\b|h(?:eures?)?(?![a-zà-ÿ]))(?:\\s*(\\d{1,2})\\b)?", RegexOption.IGNORE_CASE)

/** The minutes a step mentions ("cuire 20 minutes", "1 h", "1 h 30"), for a timer; null when none. */
internal fun stepMinutes(step: String): Int? = DURATION.find(step)?.let { m ->
    val n = m.groupValues[1].toInt()
    if (m.groupValues[2].lowercase().startsWith("h")) n * 60 + (m.groupValues[3].toIntOrNull() ?: 0) else n
}?.takeIf { it in 1..600 }

/** The minutes of the timer step [i] should start by itself now, or null (none, already started, or switched off). */
internal fun autoTimerMinutes(s: RecipeSession, i: Int): Int? =
    if (!s.autoTimers || i in s.timedSteps) null else s.recipe.steps.getOrNull(i)?.let { stepMinutes(it) }

/** "20 minutes", "1 heure", "1 h 30". */
internal fun timerWords(minutes: Int): String = when {
    minutes < 60 -> "$minutes minute${if (minutes > 1) "s" else ""}"
    minutes % 60 == 0 -> "${minutes / 60} heure${if (minutes >= 120) "s" else ""}"
    else -> "${minutes / 60} h ${minutes % 60}"
}

/** The timer's label, said when it rings: which recipe and which step it was for. */
internal fun stepTimerLabel(r: Recipe, i: Int): String =
    "${r.title.take(60)}, étape ${i + 1} : ${r.steps.getOrNull(i).orEmpty().take(120)}"

/**
 * "Étape 3 sur 7 : …", and a word when it is the last one. [timer] is what to say about the step's timer
 * (started, already running); null offers one when the step has a duration.
 */
internal fun stepText(r: Recipe, i: Int, timer: String? = null): String {
    val step = r.steps.getOrNull(i) ?: return "Cette recette n'a pas d'étape ${i + 1}."
    val said = timer ?: stepMinutes(step)?.let { " Dites « minuteur de $it minutes » si vous en voulez un." }.orEmpty()
    val end = if (i == r.steps.lastIndex) " C'était la dernière étape : bon appétit !" else ""
    return "Étape ${i + 1} sur ${r.steps.size} : $step$said$end"
}

/** "de crêpes", "d'omelette". */
internal fun ofTitle(title: String): String {
    val t = title.trim().lowercase()
    return if (t.firstOrNull()?.let { it in "aeiouyhéèêàâîôû" } == true) "d'$t" else "de $t"
}

internal fun ingredientsText(r: Recipe): String =
    "${r.title}${if (r.servings > 0) " pour ${r.servings}" else ""} : ${r.ingredients.size} ingrédients — " + r.ingredients.joinToString(", ") + "."

internal class RecipeStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    private fun load(): RecipeBook = try {
        if (file.exists()) json.decodeFromString<RecipeBook>(file.readText()) else RecipeBook()
    } catch (_: Exception) {
        RecipeBook()
    }

    @Synchronized
    private fun save(book: RecipeBook) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(book))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {
        }
    }

    /** The recipe being cooked, if it was touched in the last four hours. */
    @Synchronized
    fun current(now: Long = System.currentTimeMillis()): RecipeSession? = load().current?.takeIf { now - it.updatedAt < RECIPE_IDLE_MS }

    @Synchronized
    fun setCurrent(session: RecipeSession?) {
        save(load().copy(current = session))
        RecipeLive.lastUsed = session?.updatedAt ?: 0L
    }

    @Synchronized
    fun saved(): List<Recipe> = load().saved

    /** Keeps [r] (replacing one with the same title); false when the book is full. */
    @Synchronized
    fun keep(r: Recipe): Boolean {
        val book = load()
        val others = book.saved.filterNot { normalize(it.title) == normalize(r.title) }
        if (others.size >= MAX_SAVED_RECIPES) return false
        save(book.copy(saved = others + r))
        return true
    }

    @Synchronized
    fun forget(title: String): Boolean {
        val book = load()
        val kept = book.saved.filterNot { normalize(it.title) == normalize(title) }
        if (kept.size == book.saved.size) return false
        save(book.copy(saved = kept))
        return true
    }

    /** A kept recipe whose title contains every word of [query] ("crêpes" finds "Crêpes de ma grand-mère"). */
    @Synchronized
    fun find(query: String): Recipe? {
        val words = normalize(query).split(' ').filter { it.length > 1 && it !in setOf("de", "du", "des", "la", "le", "les", "ma", "mon", "mes", "recette") }
        if (words.isEmpty()) return null
        return load().saved.filter { r -> val t = normalize(r.title); words.all { w -> t.contains(w.removeSuffix("s")) } }
            .minByOrNull { it.title.length }
    }
}
