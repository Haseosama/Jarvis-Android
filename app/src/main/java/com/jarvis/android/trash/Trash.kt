package com.jarvis.android.trash

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
import com.jarvis.android.calendar.eventDay
import com.jarvis.android.calendar.hasCalendarPermission
import com.jarvis.android.calendar.readEvents
import com.jarvis.android.i18n.tr
import com.jarvis.android.journal.AlertKind
import com.jarvis.android.journal.Journal
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/*
 * The bin reminder: every evening at the hour chosen (20 h by default), when a bin is collected tomorrow, a notification says which
 * one to put out. The calendar is the days said by voice, the commune's .ics link when there is one (fetched again each week), and
 * the phone calendar's collection events. Each reminder is noted in the journal for Sunday's summary.
 */
internal object TrashReminder {
    private const val CHANNEL = "jarvis_trash"
    private const val ICS_MAX_AGE_MS = 6L * 86_400_000L
    private val lock = Any()

    private fun prefs(c: Context) = c.getSharedPreferences("trash", Context.MODE_PRIVATE)
    private fun icsFile(c: Context) = File(c.applicationContext.filesDir, "trash.ics")

    fun schedule(c: Context): TrashSchedule = trashFromJson(prefs(c).getString("schedule", null))

    fun update(c: Context, change: (TrashSchedule) -> TrashSchedule): TrashSchedule = synchronized(lock) {
        val next = pruned(change(schedule(c)), LocalDate.now())
        prefs(c).edit().putString("schedule", trashToJson(next)).apply()
        next
    }

    fun isOn(c: Context) = prefs(c).getBoolean("on", false)
    fun time(c: Context): LocalTime = LocalTime.of(prefs(c).getInt("hour", 20), prefs(c).getInt("minute", 0))
    fun icsUrl(c: Context): String? = prefs(c).getString("ics_url", null)

    fun setOn(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("on", on).apply()
        program(c)
    }

    fun setTime(c: Context, t: LocalTime) {
        prefs(c).edit().putInt("hour", t.hour).putInt("minute", t.minute).apply()
        program(c)
    }

    fun clearIcs(c: Context) {
        prefs(c).edit().remove("ics_url").remove("ics_at").apply()
        icsFile(c).delete()
    }

    /** Fetches the .ics at [url] and keeps it when it has events; returns how many collection days it gives over the next 8 weeks, or an error said in words. */
    suspend fun setIcs(ctx: JarvisContainer, url: String): Result<Int> {
        val c = ctx.appContext
        val text = fetch(ctx, url) ?: return Result.failure(IllegalStateException("Je n'arrive pas à télécharger ce calendrier."))
        if (!text.contains("BEGIN:VEVENT")) return Result.failure(IllegalStateException("Ce lien ne donne pas un calendrier .ics."))
        withContext(Dispatchers.IO) { icsFile(c).writeText(text) }
        prefs(c).edit().putString("ics_url", url).putLong("ics_at", System.currentTimeMillis()).apply()
        val today = LocalDate.now()
        return Result.success(parseIcs(text, today, today.plusWeeks(8), ZoneId.systemDefault()).map { it.date }.distinct().size)
    }

    private suspend fun fetch(ctx: JarvisContainer, url: String): String? = withContext(Dispatchers.IO) {
        try {
            val u = url.trim().replaceFirst(Regex("^webcal://", RegexOption.IGNORE_CASE), "https://").toHttpUrlOrNull() ?: return@withContext null
            ctx.http.newCall(Request.Builder().url(u).build()).execute().use { r -> if (r.isSuccessful) r.body?.string()?.take(2_000_000) else null }
        } catch (_: Exception) {
            null
        }
    }

    /** Fetches the .ics again when the kept one is a week old; the old one stays on failure. */
    private suspend fun refreshIcs(ctx: JarvisContainer) {
        val c = ctx.appContext
        val url = icsUrl(c) ?: return
        if (System.currentTimeMillis() - prefs(c).getLong("ics_at", 0L) < ICS_MAX_AGE_MS) return
        val text = fetch(ctx, url)?.takeIf { it.contains("BEGIN:VEVENT") } ?: return
        withContext(Dispatchers.IO) { icsFile(c).writeText(text) }
        prefs(c).edit().putLong("ics_at", System.currentTimeMillis()).apply()
    }

    /** The collection days of the .ics and of the phone's calendar from [from] to [to]. */
    fun others(c: Context, from: LocalDate, to: LocalDate): List<TrashDay> {
        val zone = ZoneId.systemDefault()
        val ics = try {
            icsFile(c).takeIf { icsUrl(c) != null && it.exists() }?.readText()?.let { parseIcs(it, from, to, zone) }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        val calendar = if (hasCalendarPermission(c)) try {
            readEvents(c, from.atStartOfDay(zone).toInstant().toEpochMilli(), to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), 300)
                .filter { isTrashTitle(it.title) }
                .map { TrashDay(binFromTitle(it.title), eventDay(it, zone)) }
                .filter { it.date in from..to }
        } catch (_: Exception) {
            emptyList()
        } else emptyList()
        return ics + calendar
    }

    fun upcoming(c: Context, from: LocalDate, days: Int): List<Pair<LocalDate, List<String>>> =
        collections(schedule(c), from, days, others(c, from, from.plusDays(days.toLong())))

    suspend fun ring(c: Context) {
        try {
            if (!isOn(c)) return
            val ctx = (c.applicationContext as JarvisApp).container
            refreshIcs(ctx)
            val tomorrow = LocalDate.now().plusDays(1)
            val bins = binsOn(schedule(c), tomorrow, others(c, tomorrow, tomorrow))
            val p = prefs(c)
            if (bins.isEmpty() || p.getString("told", null) == tomorrow.toString()) return
            p.edit().putString("told", tomorrow.toString()).apply()
            val text = eveningWords(bins, tomorrow)
            Journal.alert(c, AlertKind.TRASH, text)
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, tr("Collecte des poubelles"), NotificationManager.IMPORTANCE_DEFAULT))
            try {
                NotificationManagerCompat.from(c).notify(
                    7_976,
                    NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_delete).setContentTitle(tr("Poubelles à sortir"))
                        .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build(),
                )
            } catch (_: SecurityException) {
            }
        } catch (_: Exception) {
        } finally {
            program(c)
        }
    }

    private fun program(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (!isOn(c)) { am.cancel(intent(c)); return }
        val at = nextRing(LocalDateTime.now(), time(c)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) throw SecurityException("not allowed")
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c))
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c))
        }
    }

    fun afterBoot(c: Context) = program(c)

    private fun intent(c: Context) = PendingIntent.getBroadcast(c, 8_002, Intent(c, TrashReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

class TrashReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch { try { TrashReminder.ring(context.applicationContext) } finally { pending.finish() } }
    }
}

/** "Le bac jaune c'est le mardi des semaines paires", "quelle poubelle demain ?", "rappelle-moi de sortir les poubelles". */
object TrashTool : Tool {
    override val name = "poubelles"
    override val description =
        "Collecte des poubelles : quel bac sortir, et un rappel la veille au soir (notification, 20 h par défaut). Le calendrier vient des " +
            "jours dits par l'utilisateur, d'un lien .ics de la commune, et des événements « collecte » de l'agenda du téléphone. " +
            "action « add » : un bac (bac, ex. « bac jaune », « ordures ménagères », « verre ») avec TOUS ses jours en une fois (jours) et " +
            "frequence (« chaque semaine », « semaines paires », « semaines impaires », « une semaine sur deux » avec date = un jour de " +
            "collecte connu, « premier lundi du mois », « dernier vendredi du mois ») ; il remplace ce qui était dit pour ce bac. " +
            "Sans jours mais avec date : une collecte ponctuelle (encombrants). « skip » (date, bac facultatif) : pas de collecte ce jour " +
            "(férié). « remove » (bac, ou « tout »). « ics » (url) : le calendrier de la commune ; url vide pour l'enlever. « next » : les " +
            "prochaines collectes (bac facultatif). « heure » (heure, ex. « 19h30 »). « off » / « on » : le rappel du soir. « status » " +
            "(défaut) : le calendrier et l'heure du rappel. Si l'utilisateur ne connaît pas ses jours, conseille le site de sa commune ou " +
            "de son intercommunalité, souvent avec un calendrier à télécharger."
    override val parameters = objectSchema {
        string("action", "add, skip, remove, ics, next, heure, on, off ou status.")
        string("bac", "Le bac tel que dit : « bac jaune », « ordures ménagères », « verre », « encombrants »…")
        string("jours", "Pour add : les jours de collecte, tous à la fois (« mardi et vendredi », « du lundi au vendredi »).")
        string("frequence", "Pour add : « chaque semaine » (défaut), « semaines paires », « semaines impaires », « une semaine sur deux », « premier mardi du mois »…")
        string("date", "Une date (« 14/11 », « le 14 novembre », « jeudi ») : collecte ponctuelle, jour sans collecte, ou une semaine de collecte pour « une semaine sur deux ».")
        string("url", "Pour ics : le lien du calendrier .ics de la commune.")
        string("heure", "Pour heure : l'heure du rappel la veille (« 19h30 », « 21 h »).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val c = ctx.appContext
        val today = LocalDate.now()
        val bin = binLabel(args.stringArg("bac"))
        return when (args.stringArg("action").trim().lowercase()) {
            "add", "ajouter" -> add(c, args, bin, today)
            "skip", "sauf" -> {
                val date = parseDate(args.stringArg("date"), today) ?: return "Quel jour n'y a-t-il pas de collecte ?"
                TrashReminder.update(c) { it.copy(skips = it.skips + TrashDay(bin, date)) }
                "Noté : pas de collecte ${if (bin.isBlank()) "" else "de $bin "}${dayWords(date, today)}."
            }
            "remove", "supprimer" -> {
                val before = TrashReminder.schedule(c)
                val after = TrashReminder.update(c) { withoutBin(it, bin) }
                when {
                    isAllBins(bin) -> "J'ai effacé tout le calendrier des poubelles dit à la voix."
                    after == before -> "Je n'avais rien pour « $bin »."
                    else -> "J'ai enlevé $bin du calendrier."
                }
            }
            "ics" -> {
                val url = args.stringArg("url").trim()
                if (url.isEmpty()) { TrashReminder.clearIcs(c); return "J'ai oublié le calendrier .ics de la commune." }
                TrashReminder.setIcs(ctx, url).fold(
                    onSuccess = { n ->
                        TrashReminder.setOn(c, true)
                        "Calendrier de la commune enregistré : $n jours de collecte dans les 8 prochaines semaines. Rappel la veille à ${timeWords(TrashReminder.time(c))}."
                    },
                    onFailure = { it.message.orEmpty() },
                )
            }
            "next", "prochaine", "prochaines" -> {
                val list = TrashReminder.upcoming(c, today, 21).let { l -> if (bin.isBlank()) l else l.mapNotNull { (d, b) -> b.filter { sameBin(it, bin) }.takeIf { it.isNotEmpty() }?.let { d to it } } }
                when {
                    list.isEmpty() && bin.isNotBlank() -> "Aucune collecte de $bin connue dans les 3 semaines. " + emptyHint(c)
                    list.isEmpty() -> "Aucune collecte connue dans les 3 semaines. " + emptyHint(c)
                    else -> upcomingWords(if (bin.isBlank()) list.take(5) else list.take(3), today)
                }
            }
            "heure", "hour", "time" -> {
                val t = parseTime(args.stringArg("heure").ifBlank { args.stringArg("date") }) ?: return "À quelle heure, la veille, voulez-vous le rappel ?"
                TrashReminder.setTime(c, t)
                TrashReminder.setOn(c, true)
                "Le rappel des poubelles sonnera la veille à ${timeWords(t)}."
            }
            "off" -> { TrashReminder.setOn(c, false); "Je ne vous rappellerai plus les poubelles le soir (le calendrier reste noté)." }
            "on" -> { TrashReminder.setOn(c, true); "Rappel des poubelles la veille à ${timeWords(TrashReminder.time(c))}." }
            else -> status(c, today)
        }
    }

    private fun add(c: Context, args: JsonObject, bin: String, today: LocalDate): String {
        if (bin.isBlank()) return "Quel bac ? (bac jaune, ordures ménagères, verre…)"
        val days = parseDays(args.stringArg("jours"))
        val date = args.stringArg("date").takeIf { it.isNotBlank() }?.let { parseDate(it, today) }
        if (days.isEmpty()) {
            date ?: return "Quels jours passe la collecte de $bin ?"
            TrashReminder.update(c) { it.copy(dates = it.dates + TrashDay(bin, date)) }
            TrashReminder.setOn(c, true)
            return "Noté : collecte de $bin ${dayWords(date, today)}. Je vous le rappellerai la veille à ${timeWords(TrashReminder.time(c))}."
        }
        val (cadence, nth) = parseCadence(args.stringArg("frequence"))
        // every other week needs a week to count from: the date given, else the next such day, said so it can be corrected
        var assumed = false
        val anchor = if (cadence == Cadence.BIWEEKLY) date ?: today.with(java.time.temporal.TemporalAdjusters.nextOrSame(days.min())).also { assumed = true } else null
        val rule = TrashRule(bin, days, cadence, anchor, nth)
        val replaced = TrashReminder.schedule(c).rules.any { sameBin(it.bin, bin) }
        TrashReminder.update(c) { withRule(it, rule) }
        TrashReminder.setOn(c, true)
        val next = TrashReminder.upcoming(c, today, 62).firstOrNull { (_, b) -> b.any { sameBin(it, bin) } }?.first
        return buildString {
            append(if (replaced) "C'est corrigé, " else "Noté, ")
            append(ruleWords(rule)).append(".")
            if (assumed) append(" Je compte ${dayWords(anchor!!, today)} comme jour de collecte : dites-moi si c'est l'autre semaine.")
            next?.let { append(" Prochaine collecte ${dayWords(it, today)} ; rappel la veille à ${timeWords(TrashReminder.time(c))}.") }
        }
    }

    private fun emptyHint(c: Context): String =
        if (TrashReminder.schedule(c).isEmpty && TrashReminder.icsUrl(c) == null) "Dites-moi les jours de chaque bac, ou donnez-moi le lien .ics du calendrier de votre commune." else ""

    private fun status(c: Context, today: LocalDate): String {
        val s = TrashReminder.schedule(c)
        val parts = ArrayList<String>()
        scheduleWords(s, today).takeIf { it.isNotEmpty() }?.let { parts += "Calendrier : $it." }
        TrashReminder.icsUrl(c)?.let { parts += "Calendrier .ics de la commune : $it." }
        if (hasCalendarPermission(c)) parts += "Les événements « collecte » de l'agenda comptent aussi."
        val next = TrashReminder.upcoming(c, today, 14)
        if (next.isNotEmpty()) parts += "Prochaines collectes : " + upcomingWords(next.take(4), today)
        if (parts.isEmpty()) return "Aucun calendrier des poubelles. " + emptyHint(c)
        parts += if (TrashReminder.isOn(c)) "Rappel la veille à ${timeWords(TrashReminder.time(c))}." else "Rappel du soir désactivé."
        return parts.joinToString(" ")
    }
}
