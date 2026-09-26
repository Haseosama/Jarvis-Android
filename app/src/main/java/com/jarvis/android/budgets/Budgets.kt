package com.jarvis.android.budgets

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.android.JarvisContainer
import com.jarvis.android.expenses.Expense
import com.jarvis.android.expenses.ExpensePeriod
import com.jarvis.android.expenses.formatCents
import com.jarvis.android.expenses.normalizeCategory
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/*
 * Budgets: "mon budget courses c'est 400 € par mois" (or a total for everything). Each expense noted — by voice, from a
 * receipt, from a subscription — is weighed against it: a notification at 80 % and at 100 % (once each a month), what is
 * left in Jarvis's answer, and the budgets nearly spent in the morning briefing.
 */

/** The budget for all spending together. */
internal const val TOTAL_BUDGET = "total"

@Serializable
internal data class BudgetData(
    val limits: Map<String, Long> = emptyMap(),
    /** Per budget, the highest alert already given, as "2026-09:80". */
    val alerted: Map<String, String> = emptyMap(),
)

internal class BudgetStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): BudgetData = try {
        if (file.exists()) json.decodeFromString<BudgetData>(file.readText()) else BudgetData()
    } catch (_: Exception) {
        BudgetData()
    }

    @Synchronized
    fun update(change: (BudgetData) -> BudgetData): BudgetData {
        val next = change(load())
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(next))
        } catch (_: Exception) {
        }
        return next
    }
}

/** The budget's key: "total" for everything, else the category as expenses store it. */
internal fun budgetKey(category: String): String {
    val c = normalizeCategory(category)
    return if (c in setOf("total", "tout", "global", "general", "divers total")) TOTAL_BUDGET else c
}

/** 0, 80 or 100: the alert level reached by [spent] out of [limit]. */
internal fun budgetLevel(spent: Long, limit: Long): Int = when {
    limit <= 0 -> 0
    spent >= limit -> 100
    spent * 100 >= limit * 80 -> 80
    else -> 0
}

/** The level to announce now, or 0: only a higher level than the one already given this month. */
internal fun newAlert(level: Int, alerted: String?, month: String): Int {
    val given = alerted?.takeIf { it.startsWith("$month:") }?.substringAfter(':')?.toIntOrNull() ?: 0
    return if (level > given) level else 0
}

internal fun budgetName(key: String) = if (key == TOTAL_BUDGET) "total" else key

/** "courses : 322 € sur 400 € (80 %), il reste 78 € pour 4 jours" */
internal fun budgetLine(key: String, spent: Long, limit: Long, today: LocalDate): String {
    val left = limit - spent
    val daysLeft = YearMonth.from(today).lengthOfMonth() - today.dayOfMonth + 1
    val pct = if (limit > 0) (spent * 100 / limit) else 0
    val rest = if (left >= 0) "il reste ${formatCents(left)} pour $daysLeft jour${if (daysLeft > 1) "s" else ""}"
    else "dépassé de ${formatCents(-left)}"
    return "${budgetName(key)} : ${formatCents(spent)} sur ${formatCents(limit)} ($pct %), $rest"
}

/** What was spent this month under [key]. */
internal suspend fun spentThisMonth(ctx: JarvisContainer, key: String, today: LocalDate = LocalDate.now()): Long =
    ctx.expenseStore.inPeriod(ExpensePeriod.MONTH, today, if (key == TOTAL_BUDGET) null else key).sumOf { it.cents }

internal object Budgets {
    private const val CHANNEL = "jarvis_budgets"

    /** After an expense: the budgets it counts in (its category and the total), with an alert when a level is crossed. */
    suspend fun afterExpense(context: Context, ctx: JarvisContainer, e: Expense) {
        val data = ctx.budgetStore.load()
        val today = LocalDate.now()
        val month = YearMonth.from(today).toString()
        for (key in listOf(e.category, TOTAL_BUDGET).distinct()) {
            val limit = data.limits[key] ?: continue
            val spent = spentThisMonth(ctx, key, today)
            val alert = newAlert(budgetLevel(spent, limit), data.alerted[key], month)
            if (alert == 0) continue
            ctx.budgetStore.update { it.copy(alerted = it.alerted + (key to "$month:$alert")) }
            notify(context, key, alert, budgetLine(key, spent, limit, today))
        }
    }

    /** For Jarvis's answer after noting an expense: where its budget stands, or "". */
    suspend fun noteFor(ctx: JarvisContainer, category: String): String {
        val limits = ctx.budgetStore.load().limits
        val key = normalizeCategory(category)
        val lines = listOf(key, TOTAL_BUDGET).distinct().mapNotNull { k -> limits[k]?.let { budgetLine(k, spentThisMonth(ctx, k), it, LocalDate.now()) } }
        return if (lines.isEmpty()) "" else " Budget " + lines.joinToString(" ; ") + "."
    }

    /** The budgets at 80 % or more, for the morning briefing. */
    suspend fun briefingLines(ctx: JarvisContainer): List<String> {
        val today = LocalDate.now()
        return ctx.budgetStore.load().limits.mapNotNull { (k, limit) ->
            val spent = spentThisMonth(ctx, k, today)
            if (budgetLevel(spent, limit) >= 80) "budget " + budgetLine(k, spent, limit, today) else null
        }
    }

    private fun notify(context: Context, key: String, level: Int, line: String) {
        try {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, tr("Budgets"), NotificationManager.IMPORTANCE_DEFAULT))
            NotificationManagerCompat.from(context).notify(
                key.hashCode(),
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(if (level >= 100) trf("Budget {0} dépassé", budgetName(key)) else trf("Budget {0} : 80 % atteints", budgetName(key)))
                    .setContentText(line)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(line))
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (_: SecurityException) {
        }
    }
}
