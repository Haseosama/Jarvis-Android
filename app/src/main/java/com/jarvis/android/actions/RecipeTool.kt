package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.recipes.Recipe
import com.jarvis.android.recipes.RecipeSession
import com.jarvis.android.recipes.ingredientsText
import com.jarvis.android.recipes.splitItems
import com.jarvis.android.recipes.stepText
import kotlinx.serialization.json.JsonObject

/** Cooking step by step, by voice (see recipes/Recipes.kt). */
object RecipeTool : Tool {
    override val name = "recipe"
    override val description =
        "Cuisiner pas à pas. « Qu'est-ce que je peux cuisiner avec des œufs, des tomates et du riz ? » : proposez 2 ou 3 idées, puis quand " +
            "l'utilisateur en choisit une, action start avec title, servings, ingredients et steps (chacun séparé par « | », étapes courtes, une " +
            "action chacune). Ensuite : next (« étape suivante »), previous, repeat, goto (step), ingredients, missing (items : ce qui manque, " +
            "ajouté à la liste de courses), save (« garde cette recette »), load (name : « ma recette de crêpes »), saved (les recettes gardées), " +
            "forget (name), stop. Lisez l'étape telle quelle ; quand elle propose un minuteur, proposez-le (timer)."
    override val parameters = objectSchema {
        string("action", "'start', 'next' (défaut), 'previous', 'repeat', 'goto', 'ingredients', 'missing', 'save', 'load', 'saved', 'forget' ou 'stop'.")
        string("title", "Pour start : nom de la recette.")
        integer("servings", "Pour start : nombre de personnes.")
        string("ingredients", "Pour start : ingrédients avec quantités, séparés par « | ».")
        string("steps", "Pour start : étapes dans l'ordre, séparées par « | ».")
        integer("step", "Pour goto : numéro de l'étape (à partir de 1).")
        string("items", "Pour missing : ingrédients à acheter, séparés par « | » (vide : tous les ingrédients).")
        string("name", "Pour load / forget : nom de la recette gardée.")
    }

    private const val NONE = "Aucune recette en cours : choisissez-en une d'abord (ou « ma recette de … » pour une recette gardée)."

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val store = ctx.recipeStore
        val now = System.currentTimeMillis()
        fun go(s: RecipeSession, i: Int): String {
            val step = i.coerceIn(0, s.recipe.steps.lastIndex.coerceAtLeast(0))
            store.setCurrent(s.copy(step = step, updatedAt = now))
            return stepText(s.recipe, step)
        }
        val current = store.current(now)
        return when (args.stringArg("action").trim().lowercase().ifEmpty { "next" }) {
            "start" -> {
                val title = args.stringArg("title").trim().take(80).ifEmpty { "Recette" }
                val ingredients = splitItems(args.stringArg("ingredients"))
                val steps = splitItems(args.stringArg("steps"))
                if (steps.isEmpty()) return "Il faut les étapes de la recette (steps, séparées par « | »)."
                val r = Recipe(title, args.intArg("servings", 0).coerceIn(0, 50), ingredients, steps)
                store.setCurrent(RecipeSession(r, -1, now))
                ingredientsText(r) + " ${steps.size} étapes. Dites « étape suivante » quand vous êtes prêt, « répète » ou « l'étape d'avant » à tout moment."
            }
            "next" -> current?.let { go(it, it.step + 1) } ?: NONE
            "previous", "back" -> current?.let { go(it, it.step - 1) } ?: NONE
            "repeat" -> current?.let { go(it, it.step.coerceAtLeast(0)) } ?: NONE
            "goto" -> current?.let { go(it, args.intArg("step", 1) - 1) } ?: NONE
            "ingredients" -> current?.let { ingredientsText(it.recipe) } ?: NONE
            "missing" -> {
                val items = splitItems(args.stringArg("items")).ifEmpty { current?.recipe?.ingredients.orEmpty() }
                if (items.isEmpty()) return "Dites ce qui manque (items)."
                // The shopping list the user already keeps: "courses" if there is one, else the default list if it is in use.
                val lists = ctx.taskListStore.all().keys
                val target = if ("courses" in lists || com.jarvis.android.tasks.DEFAULT_LIST_NAME !in lists) "courses" else com.jarvis.android.tasks.DEFAULT_LIST_NAME
                var added = 0
                for (item in items) if (ctx.taskListStore.add(target, item)) added++
                "$added article${if (added > 1) "s" else ""} ajouté${if (added > 1) "s" else ""} à la liste « $target » : ${items.joinToString(", ")}."
            }
            "save" -> {
                val r = current?.recipe ?: return NONE
                if (store.keep(r)) "Recette « ${r.title} » gardée : dites « ma recette ${com.jarvis.android.recipes.ofTitle(r.title)} » pour la retrouver."
                else "Le carnet est plein (50 recettes) : oubliez-en une d'abord (forget)."
            }
            "load" -> {
                val r = store.find(args.stringArg("name")) ?: return "Aucune recette gardée ne correspond. " + savedList(ctx)
                store.setCurrent(RecipeSession(r, -1, now))
                ingredientsText(r) + " ${r.steps.size} étapes. Dites « étape suivante » pour commencer."
            }
            "saved", "list" -> savedList(ctx)
            "forget", "delete" -> if (store.forget(args.stringArg("name").trim())) "Recette oubliée." else "Aucune recette gardée de ce nom. " + savedList(ctx)
            "stop", "end" -> {
                store.setCurrent(null)
                "Recette terminée."
            }
            else -> "Action inconnue."
        }
    }

    private fun savedList(ctx: JarvisContainer): String {
        val saved = ctx.recipeStore.saved()
        return if (saved.isEmpty()) "Aucune recette gardée pour l'instant." else "Recettes gardées : " + saved.joinToString(", ") { it.title } + "."
    }
}
