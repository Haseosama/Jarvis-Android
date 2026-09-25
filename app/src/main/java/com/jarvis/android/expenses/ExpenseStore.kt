package com.jarvis.android.expenses

import com.jarvis.android.offline.normalize
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/*
 * Spending noted by voice — "j'ai dépensé 12 euros au restaurant", "combien j'ai dépensé ce mois-ci ?". Kept on the phone
 * only (file expenses.json), shared by the online tool (actions/ExpensesTool.kt), the offline commands and the settings card,
 * like the shopping lists. Amounts are stored in cents so totals never pick up floating-point crumbs.
 */

internal const val MAX_EXPENSES = 5_000
internal const val MAX_EXPENSE_CENTS = 100_000_000L // one million, a sanity cap against a misheard amount
internal const val DEFAULT_CATEGORY = "divers"

@Serializable
internal data class Expense(val cents: Long, val category: String, val note: String = "", val at: Long)

@Serializable
private data class ExpenseData(val items: MutableList<Expense> = mutableListOf())

/** A period to sum over: from the start of today / this week (Monday) / this month / this year, or everything. */
internal enum class ExpensePeriod(val label: String) { DAY("aujourd'hui"), WEEK("cette semaine"), MONTH("ce mois-ci"), YEAR("cette année"), ALL("en tout") }

/** "semaine", "ce mois-ci", "année"… to a period; anything else (including blank) is this month, the usual question. */
internal fun parsePeriod(text: String?): ExpensePeriod = when (normalize(text.orEmpty())) {
    "jour", "aujourd hui", "ce jour" -> ExpensePeriod.DAY
    "semaine", "cette semaine" -> ExpensePeriod.WEEK
    "annee", "cette annee", "an" -> ExpensePeriod.YEAR
    "tout", "total", "en tout", "depuis le debut" -> ExpensePeriod.ALL
    else -> ExpensePeriod.MONTH
}

/** The first instant of [period] around [today], in epoch milliseconds. */
internal fun periodStart(period: ExpensePeriod, today: LocalDate, zone: ZoneId): Long {
    val day = when (period) {
        ExpensePeriod.DAY -> today
        ExpensePeriod.WEEK -> today.minusDays((today.dayOfWeek.value - 1).toLong())
        ExpensePeriod.MONTH -> today.withDayOfMonth(1)
        ExpensePeriod.YEAR -> today.withDayOfYear(1)
        ExpensePeriod.ALL -> return Long.MIN_VALUE
    }
    return day.atStartOfDay(zone).toInstant().toEpochMilli()
}

/**
 * An amount as said or typed, to cents: "12", "12,50", "12.5", "12 euros 50", "12€50", "1 200". Null when there is no
 * usable number, or it is zero, negative or over [MAX_EXPENSE_CENTS].
 */
internal fun parseAmountCents(text: String): Long? {
    val t = text.lowercase(Locale.ROOT).replace(' ', ' ').trim()
    // "12 euros 50" / "12€50": the cents come after the currency word.
    Regex("^(\\d[\\d ]*)\\s*(?:€|euros?|eur)\\s*(\\d{1,2})$").find(t)?.let { m ->
        val units = m.groupValues[1].replace(" ", "").toLongOrNull() ?: return null
        val cents = m.groupValues[2].padEnd(2, '0').toLong()
        return (units * 100 + cents).takeIf { it in 1..MAX_EXPENSE_CENTS }
    }
    val number = Regex("\\d[\\d ]*(?:[.,]\\d{1,2})?").find(t)?.value?.replace(" ", "") ?: return null
    val parts = number.split(',', '.')
    val units = parts[0].toLongOrNull() ?: return null
    val cents = parts.getOrNull(1)?.padEnd(2, '0')?.toLong() ?: 0L
    return (units * 100 + cents).takeIf { it in 1..MAX_EXPENSE_CENTS }
}

/** "12,50 €" */
internal fun formatCents(cents: Long): String {
    val euros = cents / 100
    val rest = cents % 100
    val grouped = String.format(Locale.FRANCE, "%,d", euros).replace(' ', ' ').replace(' ', ' ')
    return if (rest == 0L) "$grouped €" else "$grouped,${rest.toString().padStart(2, '0')} €"
}

/** A category name as stored: accents and case folded so "Restaurant" and "restaurant" add up together. */
internal fun normalizeCategory(text: String?): String =
    normalize(text.orEmpty()).removePrefix("le ").removePrefix("la ").removePrefix("les ").removePrefix("l ")
        .removePrefix("au ").removePrefix("aux ").removePrefix("du ").removePrefix("des ").removePrefix("de ")
        .trim().take(40).ifEmpty { DEFAULT_CATEGORY }

/** "Ce mois-ci : 84,50 € en 5 dépenses — restaurant 42 €, courses 30 €, essence 12,50 €." */
internal fun summarize(items: List<Expense>, period: ExpensePeriod): String {
    if (items.isEmpty()) return "Aucune dépense notée ${period.label}."
    val total = items.sumOf { it.cents }
    val byCategory = items.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.cents } }
        .entries.sortedByDescending { it.value }
    val parts = byCategory.joinToString(", ") { "${it.key} ${formatCents(it.value)}" }
    val label = period.label.replaceFirstChar { it.uppercase() }
    return "$label : ${formatCents(total)} en ${items.size} dépense(s) — $parts."
}

internal class ExpenseStore(private val file: File, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private fun load(): ExpenseData = try {
        if (file.exists()) json.decodeFromString<ExpenseData>(file.readText()) else ExpenseData()
    } catch (_: Exception) {
        ExpenseData()
    }

    private fun save(data: ExpenseData) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(data))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {
        }
    }

    /** Adds one spending. False when the amount is out of range or the store is full. */
    suspend fun add(cents: Long, category: String?, note: String = "", at: Long = System.currentTimeMillis()): Boolean = mutex.withLock {
        if (cents !in 1..MAX_EXPENSE_CENTS) return@withLock false
        val data = load()
        if (data.items.size >= MAX_EXPENSES) return@withLock false
        data.items += Expense(cents, normalizeCategory(category), note.trim().take(120), at)
        save(data)
        true
    }

    /** The spendings of [period] around [today], oldest first; an optional [category] narrows them. */
    suspend fun inPeriod(period: ExpensePeriod, today: LocalDate = LocalDate.now(zone()), category: String? = null): List<Expense> = mutex.withLock {
        val from = periodStart(period, today, zone())
        val wanted = category?.takeIf { it.isNotBlank() }?.let { normalizeCategory(it) }
        load().items.filter { it.at >= from && (wanted == null || it.category == wanted) }.sortedBy { it.at }
    }

    /** Removes the most recent spending (the usual "no, not that one") and returns it. */
    suspend fun removeLast(): Expense? = mutex.withLock {
        val data = load()
        val last = data.items.maxByOrNull { it.at } ?: return@withLock null
        data.items.remove(last)
        save(data)
        last
    }

    suspend fun clearAll() = mutex.withLock { save(ExpenseData()) }
}

/** "3/09 : 12,50 € (restaurant, pizza)" */
internal fun describeExpense(e: Expense, zone: ZoneId): String {
    val day = Instant.ofEpochMilli(e.at).atZone(zone).toLocalDate()
    val date = "${day.dayOfMonth}/${day.monthValue.toString().padStart(2, '0')}"
    val note = if (e.note.isNotBlank()) ", ${e.note}" else ""
    return "$date : ${formatCents(e.cents)} (${e.category}$note)"
}
