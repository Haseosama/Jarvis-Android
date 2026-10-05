package com.jarvis.android.weekly

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.JarvisContainer
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import com.jarvis.android.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Request
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/*
 * Sunday's summary: the week gone by (sleep, steps, spending, and from WeekRecap the drives, reminders and alerts) and the week to
 * come (appointments day by day, the reminders set, the weather), in a few sentences, said aloud or shown, on Sunday at the hour
 * chosen (18 h by default). Each part only when the phone knows it (Health Connect allowed, expenses noted, calendar allowed, position
 * for the weather, driving mode used, reminders set, alerts given).
 */

/** The week gone by, as the phone knows it: the nights (minutes asleep), the steps day by day, the spending by category (cents). */
internal data class PastWeek(val nights: List<Pair<LocalDate, Long>>, val steps: List<Pair<LocalDate, Long>>, val spending: Map<String, Long>)

/** A day of the week to come: its appointments (lines) and its weather (WMO code, highest and lowest °C, rain in mm). */
internal data class NextDay(val date: LocalDate, val events: List<String>, val code: Int?, val tMax: Double?, val tMin: Double?, val rainMm: Double?)

private val FR = Locale.FRANCE
private fun dayName(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("EEEE", FR))
private fun euros(cents: Long) = String.format(FR, "%.2f €", cents / 100.0)

/** A WMO weather code in a word or two. */
internal fun weatherWord(code: Int): String = when (code) {
    0 -> "soleil"
    1, 2 -> "éclaircies"
    3 -> "couvert"
    45, 48 -> "brouillard"
    in 51..57 -> "bruine"
    in 61..67 -> "pluie"
    in 71..77, 85, 86 -> "neige"
    in 80..82 -> "averses"
    in 95..99 -> "orages"
    else -> "variable"
}

internal fun pastWeekWords(w: PastWeek): List<String> {
    val out = ArrayList<String>()
    if (w.nights.isNotEmpty()) {
        val avg = w.nights.map { it.second }.average().toLong()
        val worst = w.nights.minBy { it.second }
        out += "sommeil : ${avg / 60} h ${"%02d".format(avg % 60)} en moyenne sur ${w.nights.size} nuit${if (w.nights.size > 1) "s" else ""}" +
            if (w.nights.size > 2) ", la plus courte ${dayName(worst.first)} (${worst.second / 60} h ${"%02d".format(worst.second % 60)})" else ""
    }
    val steps = w.steps.filter { it.second > 0 }
    if (steps.isNotEmpty()) {
        val total = steps.sumOf { it.second }
        val best = steps.maxBy { it.second }
        out += "pas : ${com.jarvis.android.health.thousands(total)} en tout, ${com.jarvis.android.health.thousands(total / steps.size)} par jour, le plus ${dayName(best.first)} (${com.jarvis.android.health.thousands(best.second)})"
    }
    if (w.spending.isNotEmpty()) {
        val total = w.spending.values.sum()
        val top = w.spending.entries.sortedByDescending { it.value }.take(3).joinToString(", ") { "${it.key} ${euros(it.value)}" }
        out += "dépenses notées : ${euros(total)} ($top)"
    }
    return out
}

internal fun nextWeekWords(days: List<NextDay>): List<String> {
    val out = ArrayList<String>()
    val busy = days.filter { it.events.isNotEmpty() }
    out += if (busy.isEmpty()) "rien à l’agenda"
    else "à l’agenda : " + busy.joinToString(" ; ") { d -> dayName(d.date) + " " + d.events.take(3).joinToString(", ") + if (d.events.size > 3) " (+${d.events.size - 3})" else "" }
    val weather = days.filter { it.code != null }
    if (weather.isNotEmpty()) {
        // neighbouring days with the same weather said once
        val parts = ArrayList<String>()
        var i = 0
        while (i < weather.size) {
            val w = weatherWord(weather[i].code!!)
            var j = i
            while (j + 1 < weather.size && weatherWord(weather[j + 1].code!!) == w) j++
            val names = if (j == i) dayName(weather[i].date) else "${dayName(weather[i].date)} à ${dayName(weather[j].date)}"
            val hi = weather.subList(i, j + 1).mapNotNull { it.tMax }.maxOrNull()
            parts += "$names $w" + (hi?.let { " (${it.toInt()} °C)" } ?: "")
            i = j + 1
        }
        out += "météo : " + parts.joinToString(", ")
    }
    return out
}

internal fun summaryWords(past: List<String>, next: List<String>): String =
    "Bilan de la semaine. " + (if (past.isEmpty()) "Pas de données sur la semaine passée (santé, dépenses, rappels, trajets, alertes)." else past.joinToString(". ") { it.replaceFirstChar { c -> c.uppercase() } } + ".") +
        " La semaine prochaine : " + next.mapIndexed { i, s -> if (i == 0) s else s.replaceFirstChar { c -> c.uppercase() } }.joinToString(". ") + "."

internal object WeeklySummary {
    private fun prefs(c: Context) = c.getSharedPreferences("weekly_summary", Context.MODE_PRIVATE)
    fun mode(c: Context) = prefs(c).getString("mode", "off") ?: "off"
    fun hour(c: Context) = prefs(c).getInt("hour", 18)

    suspend fun pastWeek(ctx: JarvisContainer, today: LocalDate, zone: ZoneId): PastWeek {
        val c = ctx.appContext
        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        var nights = emptyList<Pair<LocalDate, Long>>()
        var steps = emptyList<Pair<LocalDate, Long>>()
        withTimeoutOrNull(10_000) {
            try {
                val client = com.jarvis.android.health.healthClient(c) ?: return@withTimeoutOrNull
                val granted = com.jarvis.android.health.grantedHealth(c)
                nights = days.mapNotNull { d -> com.jarvis.android.health.nightEndingOn(client, d, zone)?.let { d to it.asleepMinutes } }
                steps = days.map { d ->
                    val a = com.jarvis.android.health.activityBetween(client, d.atStartOfDay(zone).toInstant(), d.plusDays(1).atStartOfDay(zone).toInstant(), granted)
                    d to a.steps
                }
            } catch (_: Exception) {
            }
        }
        val spending = try {
            ctx.expenseStore.inPeriod(com.jarvis.android.expenses.ExpensePeriod.WEEK, today).groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.cents } }
        } catch (_: Exception) {
            emptyMap()
        }
        return PastWeek(nights, steps, spending)
    }

    suspend fun nextWeek(ctx: JarvisContainer, today: LocalDate, zone: ZoneId): List<NextDay> {
        val c = ctx.appContext
        val days = (1..7).map { today.plusDays(it.toLong()) }
        val events = if (com.jarvis.android.calendar.hasCalendarPermission(c)) try {
            com.jarvis.android.calendar.readEvents(c, days.first().atStartOfDay(zone).toInstant().toEpochMilli(), days.last().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), 80)
        } catch (_: Exception) {
            emptyList()
        } else emptyList()
        val fix = (com.jarvis.android.location.locate(c, 12 * 3_600_000L) as? com.jarvis.android.location.LocationOutcome.Found)?.fix
        val daily = fix?.let { f ->
            withContext(Dispatchers.IO) {
                try {
                    ctx.http.newCall(Request.Builder().url("https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum&forecast_days=8&timezone=auto".format(Locale.US, f.latitude, f.longitude)).build())
                        .execute().use { if (it.isSuccessful) parseDaily(it.body?.string().orEmpty()) else emptyMap() }
                } catch (_: Exception) {
                    emptyMap()
                }
            }
        }.orEmpty()
        return days.map { d ->
            val w = daily[d]
            NextDay(d, com.jarvis.android.calendar.linesForDay(events, d, zone), w?.code, w?.tMax, w?.tMin, w?.rain)
        }
    }

    internal data class Daily(val code: Int?, val tMax: Double?, val tMin: Double?, val rain: Double?)

    internal fun parseDaily(json: String): Map<LocalDate, Daily> {
        val d = (Json.parseToJsonElement(json) as JsonObject)["daily"] as? JsonObject ?: return emptyMap()
        fun arr(k: String) = (d[k] as? JsonArray).orEmpty()
        return arr("time").mapIndexed { i, t ->
            LocalDate.parse((t as JsonPrimitive).content) to Daily(
                (arr("weather_code").getOrNull(i) as? JsonPrimitive)?.intOrNull, (arr("temperature_2m_max").getOrNull(i) as? JsonPrimitive)?.doubleOrNull,
                (arr("temperature_2m_min").getOrNull(i) as? JsonPrimitive)?.doubleOrNull, (arr("precipitation_sum").getOrNull(i) as? JsonPrimitive)?.doubleOrNull,
            )
        }.toMap()
    }

    private fun at(ms: Long, zone: ZoneId) = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), zone)

    /** What Jarvis did in the week gone by: the drives, the reminders that rang, the alerts it gave. */
    fun recapPast(c: Context, today: LocalDate, zone: ZoneId): List<String> {
        val from = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val reminders = try { com.jarvis.android.reminders.ReminderService.list(c) } catch (_: Exception) { emptyList() }
        val rang = reminders.filter {
            it.triggerAt in from..now && it.status in setOf(
                com.jarvis.android.reminders.ReminderStatus.DELIVERED, com.jarvis.android.reminders.ReminderStatus.BLOCKED, com.jarvis.android.reminders.ReminderStatus.FAILED,
            )
        }.map { RecapReminder(at(it.triggerAt, zone), it.text, it.status == com.jarvis.android.reminders.ReminderStatus.DELIVERED) }
        val journal = com.jarvis.android.journal.Journal.since(c, from)
        val drives = journal.filter { it.kind == com.jarvis.android.journal.DRIVE }.map { at(it.at, zone) to (it.endAt - it.at) / 60_000L }
        val alerts = journal.filter { it.kind != com.jarvis.android.journal.DRIVE }.map { it.kind to it.text }
        return listOfNotNull(drivesWords(drives), pastRemindersWords(rang), alertsWords(alerts))
    }

    /** The reminders set for the seven days after [today]. */
    fun nextReminders(c: Context, today: LocalDate, zone: ZoneId): String? {
        val from = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(8).atStartOfDay(zone).toInstant().toEpochMilli()
        val reminders = try { com.jarvis.android.reminders.ReminderService.list(c) } catch (_: Exception) { emptyList() }
        return nextRemindersWords(
            reminders.filter { it.status == com.jarvis.android.reminders.ReminderStatus.SCHEDULED && it.triggerAt >= from && it.triggerAt < to }
                .map { RecapReminder(at(it.triggerAt, zone), it.text) },
        )
    }

    suspend fun compose(ctx: JarvisContainer): String {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val c = ctx.appContext
        val next = nextWeekWords(nextWeek(ctx, today, zone)).toMutableList()
        nextReminders(c, today, zone)?.let { next.add(1, it) } // after the appointments
        return summaryWords(pastWeekWords(pastWeek(ctx, today, zone)) + recapPast(c, today, zone), next)
    }

    suspend fun deliver(ctx: JarvisContainer, speak: Boolean): String {
        val text = compose(ctx)
        val c = ctx.appContext
        if (speak) com.jarvis.android.driving.DrivingMode.speak(c, text, thenRelease = true)
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("jarvis_weekly", tr("Bilan de la semaine"), NotificationManager.IMPORTANCE_DEFAULT))
        try {
            NotificationManagerCompat.from(c).notify(
                7_999,
                NotificationCompat.Builder(c, "jarvis_weekly").setSmallIcon(android.R.drawable.ic_menu_agenda).setContentTitle(tr("Bilan de la semaine"))
                    .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build(),
            )
        } catch (_: SecurityException) {
        }
        return text
    }

    /** The next Sunday at [hour] (today if it is Sunday and not past). */
    internal fun nextSunday(now: LocalDateTime, hour: Int): LocalDateTime {
        val today = now.toLocalDate().atTime(hour, 0)
        return if (now.dayOfWeek == DayOfWeek.SUNDAY && now.isBefore(today)) today
        else now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.SUNDAY)).atTime(hour, 0)
    }

    fun set(c: Context, mode: String, hour: Int) {
        prefs(c).edit().putString("mode", mode).putInt("hour", hour).apply()
        program(c)
    }

    private fun program(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (mode(c) == "off") { am.cancel(intent(c)); return }
        val at = nextSunday(LocalDateTime.now(), hour(c)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) throw SecurityException("not allowed")
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c))
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c))
        }
    }

    fun afterBoot(c: Context) = program(c)

    private fun intent(c: Context) = PendingIntent.getBroadcast(c, 8_001, Intent(c, WeeklySummaryReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    suspend fun ring(c: Context) {
        val m = mode(c)
        if (m != "off") try { deliver((c.applicationContext as JarvisApp).container, m == "speak") } catch (_: Exception) {}
        program(c)
    }
}

class WeeklySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch { try { WeeklySummary.ring(context.applicationContext) } finally { pending.finish() } }
    }
}

/** "Fais-moi le bilan de la semaine", "lis-moi le bilan chaque dimanche soir". */
object WeeklySummaryTool : Tool {
    override val name = "weekly_summary"
    override val description =
        "Le bilan de la semaine : la semaine passée (sommeil moyen et nuit la plus courte, pas, trajets en voiture, dépenses notées par " +
            "catégorie, rappels sonnés, alertes données par Jarvis) et la semaine prochaine (rendez-vous de l’agenda jour par jour, rappels " +
            "prévus, météo des 7 jours). action « now » (défaut) : le bilan tout de " +
            "suite ; « set » (mode : speak à voix haute, notify en notification, off ; hour : l’heure du dimanche, 18 par défaut) : " +
            "chaque dimanche automatiquement ; « status »."
    override val parameters = objectSchema {
        string("action", "now, set ou status.")
        string("mode", "Pour set : speak, notify ou off.")
        string("hour", "Pour set : l’heure du dimanche (0 à 23, 18 par défaut).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val c = ctx.appContext
        fun status() = when (WeeklySummary.mode(c)) {
            "speak" -> "Bilan de la semaine : dit à voix haute chaque dimanche à ${WeeklySummary.hour(c)} h."
            "notify" -> "Bilan de la semaine : en notification chaque dimanche à ${WeeklySummary.hour(c)} h."
            else -> "Bilan automatique du dimanche : désactivé."
        }
        return when (args.stringArg("action").trim().lowercase()) {
            "set" -> {
                val mode = when (args.stringArg("mode").trim().lowercase()) { "speak", "voice", "aloud" -> "speak"; "off", "none" -> "off"; else -> "notify" }
                val hour = args.stringArg("hour").trim().removeSuffix("h").trim().toIntOrNull()?.coerceIn(0, 23) ?: WeeklySummary.hour(c)
                WeeklySummary.set(c, mode, hour)
                status()
            }
            "status" -> status()
            else -> WeeklySummary.compose(ctx) + " Dites-le en quelques phrases."
        }
    }
}
