package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.budgets.TOTAL_BUDGET
import com.jarvis.android.budgets.budgetKey
import com.jarvis.android.budgets.budgetLine
import com.jarvis.android.budgets.budgetName
import com.jarvis.android.budgets.spentThisMonth
import com.jarvis.android.expenses.formatCents
import com.jarvis.android.expenses.parseAmountCents
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.stringArg
import com.jarvis.android.tool.objectSchema

/** Monthly budgets per category, weighed against the expenses (see budgets/Budgets.kt). */
object BudgetTool : Tool {
    override val name = "budget"
    override val description =
        "Budgets mensuels. set (« mon budget courses c'est 400 € par mois », « budget total 1500 € » : category, amount) ; status (« où en est " +
            "mon budget ? », « il me reste combien pour les restaurants ? » : category facultative) ; remove (category). Chaque dépense notée est " +
            "comptée : notification à 80 % et à 100 %, et le reste est donné après chaque dépense."
    override val parameters = objectSchema {
        string("action", "'status' (défaut), 'set' ou 'remove'.")
        string("category", "Catégorie des dépenses (courses, restaurant, essence…), ou 'total' pour tout.")
        string("amount", "Pour set : montant par mois, ex. '400'.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val store = ctx.budgetStore
        val today = LocalDate.now()
        val raw = args.stringArg("category").trim()
        return when (args.stringArg("action").trim().lowercase().ifEmpty { "status" }) {
            "set", "add" -> {
                if (raw.isEmpty()) return "Pour quelle catégorie ? (ou « total » pour toutes les dépenses)"
                val cents = parseAmountCents(args.stringArg("amount")) ?: return "Montant illisible : donnez-le en euros, ex. 400."
                val key = budgetKey(raw)
                store.update { it.copy(limits = it.limits + (key to cents), alerted = it.alerted - key) }
                "Budget ${budgetName(key)} : ${formatCents(cents)} par mois. " + budgetLine(key, spentThisMonth(ctx, key, today), cents, today) +
                    ". Je vous préviens à 80 % et à 100 %."
            }
            "remove", "delete" -> {
                val key = budgetKey(raw)
                if (key !in store.load().limits) return "Aucun budget « ${budgetName(key)} »."
                store.update { it.copy(limits = it.limits - key, alerted = it.alerted - key) }
                "Budget ${budgetName(key)} supprimé."
            }
            else -> {
                val limits = store.load().limits
                if (limits.isEmpty()) return "Aucun budget défini. Dites par exemple « mon budget courses c'est 400 € par mois »."
                val wanted = if (raw.isEmpty()) limits.keys.sortedBy { if (it == TOTAL_BUDGET) "~" else it } else listOf(budgetKey(raw))
                val lines = wanted.mapNotNull { k -> limits[k]?.let { budgetLine(k, spentThisMonth(ctx, k, today), it, today) } }
                if (lines.isEmpty()) "Aucun budget « ${budgetName(budgetKey(raw))} ». Budgets : ${limits.keys.joinToString(", ") { budgetName(it) }}."
                else "Budgets du mois :\n" + lines.joinToString("\n")
            }
        }
    }
}
