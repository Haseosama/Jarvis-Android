package com.jarvis.android.subscriptions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.android.JarvisApp
import com.jarvis.android.expenses.Expense
import com.jarvis.android.expenses.formatCents
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.offline.normalize
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/*
 * Subscriptions and other regular payments: "Netflix 13,49 € le 5 de chaque mois". Jarvis says the day before that one
 * is due (with a button to note it as an expense), lists them with what they cost a month, puts today's and tomorrow's
 * in the morning briefing, and can spot them among the expenses noted by voice or from receipts.
 */

internal const val MONTHLY = "monthly"
internal const val YEARLY = "yearly"
internal const val SUBSCRIPTION_CATEGORY = "abonnements"

@Serializable
internal data class Subscription(
    val id: Long,
    val name: String,
    val cents: Long,
    val day: Int,
    val period: String = MONTHLY,
    val month: Int = 1,
    val notifiedFor: String = "",
)

@Serializable
private data class SubscriptionData(val items: List<Subscription> = emptyList())

internal class SubscriptionStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun all(): List<Subscription> = try {
        if (file.exists()) json.decodeFromString<SubscriptionData>(file.readText()).items else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun update(change: (List<Subscription>) -> List<Subscription>): List<Subscription> {
        val next = change(all())
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(SubscriptionData(next)))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {
        }
        return next
    }
}

/** The next day [s] is due, today included; the 31st is the last day of a shorter month. */
internal fun nextDue(s: Subscription, today: LocalDate): LocalDate {
    fun on(ym: YearMonth) = ym.atDay(s.day.coerceIn(1, ym.lengthOfMonth()))
    return if (s.period == YEARLY) {
        val thisYear = on(YearMonth.of(today.year, s.month.coerceIn(1, 12)))
        if (thisYear.isBefore(today)) on(YearMonth.of(today.year + 1, s.month.coerceIn(1, 12))) else thisYear
    } else {
        val thisMonth = on(YearMonth.from(today))
        if (thisMonth.isBefore(today)) on(YearMonth.from(today).plusMonths(1)) else thisMonth
    }
}

/** What they cost a month, yearly ones spread over twelve. */
internal fun monthlyCents(items: List<Subscription>): Long =
    items.sumOf { if (it.period == YEARLY) it.cents / 12 else it.cents }

internal fun dueWords(due: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, due)) {
    0L -> "aujourd'hui"
    1L -> "demain"
    else -> "le ${due.dayOfMonth}/${due.monthValue.toString().padStart(2, '0')}"
}

internal fun describeSubscriptions(items: List<Subscription>, today: LocalDate): String {
    if (items.isEmpty()) return "Aucun abonnement noté."
    val sorted = items.sortedBy { nextDue(it, today) }
    val lines = sorted.mapIndexed { i, s ->
        "${i + 1}) ${s.name} : ${formatCents(s.cents)} ${if (s.period == YEARLY) "par an" else "par mois"}, prochain prélèvement ${dueWords(nextDue(s, today), today)}"
    }
    return "${items.size} abonnement${if (items.size > 1) "s" else ""}, ${formatCents(monthlyCents(items))} par mois en tout :\n" + lines.joinToString("\n")
}

internal data class Candidate(val name: String, val cents: Long, val day: Int, val period: String, val count: Int)

/**
 * Regular payments among the expenses: the same shop or label (else category) at least twice, about a month apart
 * (26 to 35 days) or a year apart (350 to 380), for about the same amount (within 10 %).
 */
internal fun detectRecurring(expenses: List<Expense>, zone: ZoneId, known: List<Subscription> = emptyList()): List<Candidate> {
    val knownKeys = known.map { normalize(it.name) }.toSet()
    return expenses.groupBy { normalize(it.note).ifBlank { it.category } }
        .filter { (key, list) -> key.isNotBlank() && key !in knownKeys && list.size >= 2 }
        .mapNotNull { (key, list) ->
            val sorted = list.sortedBy { it.at }
            val days = sorted.map { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
            val gaps = days.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }
            val period = when {
                gaps.all { it in 26..35 } -> MONTHLY
                gaps.all { it in 350..380 } -> YEARLY
                else -> return@mapNotNull null
            }
            val median = sorted.map { it.cents }.sorted()[sorted.size / 2]
            if (sorted.any { abs(it.cents - median) > median / 10 }) return@mapNotNull null
            val last = sorted.last()
            val name = last.note.ifBlank { key }.replaceFirstChar { it.uppercase() }
            Candidate(name, last.cents, days.last().dayOfMonth, period, sorted.size)
        }
        .sortedByDescending { it.cents }
}

/** Today's and tomorrow's payments, for the morning briefing. */
internal fun dueSoonLines(items: List<Subscription>, today: LocalDate): List<String> =
    items.map { it to nextDue(it, today) }.filter { ChronoUnit.DAYS.between(today, it.second) <= 1 }
        .map { (s, due) -> "prélèvement ${s.name} ${formatCents(s.cents)} ${dueWords(due, today)}" }

internal object SubscriptionAlerts {
    private const val CHANNEL = "jarvis_subscriptions"

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "subscriptions", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<SubscriptionWorker>(12, TimeUnit.HOURS).build(),
        )
    }

    /** Warns once for each payment due tomorrow (or today, if the day before was missed). */
    fun check(context: Context, today: LocalDate = LocalDate.now()) {
        val store = (context.applicationContext as JarvisApp).container.subscriptionStore
        val due = store.all().map { it to nextDue(it, today) }
            .filter { (s, d) -> ChronoUnit.DAYS.between(today, d) <= 1 && s.notifiedFor != d.toString() }
        if (due.isEmpty()) return
        store.update { list -> list.map { s -> due.firstOrNull { it.first.id == s.id }?.let { s.copy(notifiedFor = it.second.toString()) } ?: s } }
        for ((s, d) in due) {
            val words = when (ChronoUnit.DAYS.between(today, d)) {
                0L -> tr("aujourd’hui")
                1L -> tr("demain")
                else -> "${d.dayOfMonth}/${d.monthValue.toString().padStart(2, '0')}"
            }
            notify(context, s, words)
        }
    }

    private fun notify(context: Context, s: Subscription, whenWords: String) {
        try {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, tr("Abonnements"), NotificationManager.IMPORTANCE_DEFAULT))
            val id = (s.id % 1_000_000).toInt() + 9_000_000
            val log = PendingIntent.getBroadcast(
                context, id, Intent(context, SubscriptionReceiver::class.java).setAction(SubscriptionReceiver.ACTION_LOG).putExtra("id", s.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            NotificationManagerCompat.from(context).notify(
                id,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle(trf("Prélèvement {0} : {1}", whenWords, s.name))
                    .setContentText(formatCents(s.cents))
                    .addAction(0, tr("Noter la dépense"), log)
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (_: SecurityException) {
        }
    }
}

class SubscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SubscriptionAlerts.check(applicationContext)
        return Result.success()
    }
}

/** The "Noter la dépense" button: the payment becomes an expense in the "abonnements" category. */
class SubscriptionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOG) return
        val id = intent.getLongExtra("id", -1)
        val container = (context.applicationContext as JarvisApp).container
        val s = container.subscriptionStore.all().firstOrNull { it.id == id } ?: return
        val pending = goAsync()
        Thread {
            try {
                runBlocking { container.expenseStore.add(s.cents, SUBSCRIPTION_CATEGORY, s.name) }
                NotificationManagerCompat.from(context).cancel((id % 1_000_000).toInt() + 9_000_000)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_LOG = "com.jarvis.android.SUBSCRIPTION_LOG"
    }
}
