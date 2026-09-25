package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.expenses.describeExpense
import com.jarvis.android.expenses.formatCents
import com.jarvis.android.expenses.parseAmountCents
import com.jarvis.android.expenses.parsePeriod
import com.jarvis.android.expenses.summarize
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId

/** Spending noted by voice, summed by period and category. Kept on the phone; works offline too. */
object ExpensesTool : Tool {
    override val name = "expenses"
    override val description =
        "Noter et additionner les dépenses de l'utilisateur, gardées sur le téléphone (marche aussi hors ligne). Actions : add (défaut : " +
            "amount = montant en euros, category = catégorie courte comme restaurant, courses, essence, loisirs, note = détail facultatif), " +
            "summary (total d'une période et par catégorie), list (le détail d'une période), remove_last (annule la dernière dépense notée). " +
            "period : jour, semaine, mois (défaut) ou année. Choisissez vous-même une catégorie simple et stable à partir de ce que dit l'utilisateur."
    override val parameters = objectSchema {
        string("action", "'add' (défaut), 'summary', 'list' ou 'remove_last'.")
        string("amount", "Pour add : le montant, par exemple « 12,50 ».")
        string("category", "Pour add : la catégorie ; pour summary/list : facultatif, pour une seule catégorie.")
        string("note", "Pour add : un détail facultatif (« pizza avec Paul »).")
        string("period", "Pour summary/list : jour, semaine, mois (défaut) ou année.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val store = ctx.expenseStore
        val category = args.stringArg("category").trim()
        return when (args.stringArg("action").trim().lowercase().ifEmpty { "add" }) {
            "add" -> {
                val cents = parseAmountCents(args.stringArg("amount"))
                    ?: return "Montant invalide : indiquez un nombre positif, par exemple 12,50."
                if (store.add(cents, category, args.stringArg("note"))) {
                    "Dépense notée : ${formatCents(cents)} en ${com.jarvis.android.expenses.normalizeCategory(category)}."
                } else {
                    "Impossible de noter cette dépense (montant hors limites ou carnet plein)."
                }
            }
            "summary" -> {
                val period = parsePeriod(args.stringArg("period"))
                summarize(store.inPeriod(period, category = category), period)
            }
            "list" -> {
                val period = parsePeriod(args.stringArg("period"))
                val items = store.inPeriod(period, category = category)
                if (items.isEmpty()) "Aucune dépense notée ${period.label}."
                else items.takeLast(30).joinToString("\n") { describeExpense(it, ZoneId.systemDefault()) } +
                    if (items.size > 30) "\n(les 30 dernières sur ${items.size})" else ""
            }
            "remove_last" -> {
                val removed = store.removeLast() ?: return "Aucune dépense à retirer."
                "Dernière dépense retirée : ${describeExpense(removed, ZoneId.systemDefault())}."
            }
            else -> "Action inconnue : utilisez add, summary, list ou remove_last."
        }
    }
}
