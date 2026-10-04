package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.expenses.ExpensePeriod
import com.jarvis.android.expenses.formatCents
import com.jarvis.android.expenses.parseAmountCents
import com.jarvis.android.text.normalize
import com.jarvis.android.subscriptions.MONTHLY
import com.jarvis.android.subscriptions.Subscription
import com.jarvis.android.subscriptions.SubscriptionAlerts
import com.jarvis.android.subscriptions.YEARLY
import com.jarvis.android.subscriptions.describeSubscriptions
import com.jarvis.android.subscriptions.detectRecurring
import com.jarvis.android.subscriptions.dueWords
import com.jarvis.android.subscriptions.nextDue
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.ZoneId
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.intArg
import com.jarvis.android.tool.stringArg
import com.jarvis.android.tool.objectSchema

/** Subscriptions and regular payments: listed, warned the day before, spotted among the expenses (see subscriptions/). */
object SubscriptionsTool : Tool {
    override val name = "subscriptions"
    override val description =
        "Abonnements et prélèvements réguliers. list (« mes abonnements », « combien me coûtent mes abonnements ? ») ; add (« ajoute " +
            "l'abonnement Netflix, 13,49 € le 5 de chaque mois » : name, amount, day, period monthly ou yearly, month pour yearly) ; remove " +
            "(name ou choice) ; detect (« trouve mes abonnements » : cherche les paiements réguliers parmi les dépenses notées, à proposer à " +
            "l'utilisateur avant de les ajouter). Jarvis prévient la veille de chaque prélèvement."
    override val parameters = objectSchema {
        string("action", "'list' (défaut), 'add', 'remove' ou 'detect'.")
        string("name", "Nom : Netflix, Spotify, forfait téléphone, assurance…")
        string("amount", "Montant, ex. '13,49'.")
        integer("day", "Jour du prélèvement dans le mois (1 à 31).")
        string("period", "'monthly' (défaut) ou 'yearly'.")
        integer("month", "Pour yearly : mois du prélèvement (1 à 12).")
        integer("choice", "Pour remove : numéro dans la liste.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val store = ctx.subscriptionStore
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return when (args.stringArg("action").trim().lowercase().ifEmpty { "list" }) {
            "add" -> {
                val name = args.stringArg("name").trim().take(60)
                val cents = parseAmountCents(args.stringArg("amount")) ?: return "Montant illisible : donnez-le en euros, ex. 13,49."
                if (name.isEmpty()) return "Il faut le nom de l'abonnement."
                val period = if (args.stringArg("period").trim().lowercase() == YEARLY) YEARLY else MONTHLY
                val day = args.intArg("day", today.dayOfMonth).coerceIn(1, 31)
                val month = args.intArg("month", today.monthValue).coerceIn(1, 12)
                val s = Subscription(System.currentTimeMillis(), name, cents, day, period, month)
                store.update { list -> list.filterNot { normalize(it.name) == normalize(name) } + s }
                SubscriptionAlerts.schedule(ctx.appContext)
                val due = nextDue(s, today)
                "Abonnement noté : $name, ${formatCents(cents)} ${if (period == YEARLY) "par an" else "par mois"}, prochain prélèvement " +
                    "${dueWords(due, today)}." + if (due == today) "" else " Je vous préviendrai la veille."
            }
            "remove", "delete" -> {
                val items = store.all().sortedBy { nextDue(it, today) }
                val i = args.intArg("choice", 0)
                val name = normalize(args.stringArg("name"))
                val target = when {
                    i in 1..items.size -> items[i - 1]
                    name.isNotEmpty() -> items.filter { normalize(it.name).contains(name) }.singleOrNull()
                    else -> null
                } ?: return "Lequel ?\n" + describeSubscriptions(items, today)
                store.update { list -> list.filterNot { it.id == target.id } }
                "Abonnement ${target.name} retiré."
            }
            "detect" -> {
                val expenses = ctx.expenseStore.inPeriod(ExpensePeriod.ALL, today)
                val found = detectRecurring(expenses, zone, store.all())
                if (found.isEmpty()) "Je ne vois aucun paiement régulier parmi les dépenses notées (il en faut au moins deux, à un mois d'écart, du même montant)."
                else "Paiements qui reviennent parmi vos dépenses :\n" + found.take(10).mapIndexed { n, c ->
                    "${n + 1}) ${c.name} : ${formatCents(c.cents)} ${if (c.period == YEARLY) "par an" else "par mois"}, vers le ${c.day} (${c.count} fois)"
                }.joinToString("\n") + "\nDemandez à l'utilisateur lesquels ajouter (add)."
            }
            else -> describeSubscriptions(store.all(), today)
        }
    }
}
