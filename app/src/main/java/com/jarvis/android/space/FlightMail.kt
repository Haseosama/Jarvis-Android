package com.jarvis.android.space

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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.android.JarvisApp
import com.jarvis.android.JarvisContainer
import com.jarvis.android.actions.Tool
import com.jarvis.android.actions.objectSchema
import com.jarvis.android.actions.stringArg
import com.jarvis.android.google.GoogleApi
import com.jarvis.android.google.GoogleException
import com.jarvis.android.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/*
 * "Mes vols": the flights of the bookings and boarding passes in the user's Gmail. Flight numbers written next to a travel word, the
 * date and time nearest to them, the gate and terminal when the mail says; each checked against adsbdb (a real route, and where it
 * goes). On the day, a notification a few hours before, and the flight followed (FlightWatch: take-off with its delay, landing).
 */

/** A flight found in a mail: its number, its date, its time (local to the airport it leaves from) if written, gate and terminal. */
internal data class MailFlight(val flight: String, val date: LocalDate, val time: LocalTime?, val gate: String?, val terminal: String?)

private val MONTHS = listOf(
    listOf("janvier", "janv", "january", "jan"), listOf("fevrier", "févr", "février", "fevr", "february", "feb", "fev", "fév"),
    listOf("mars", "march", "mar"), listOf("avril", "avr", "april", "apr"), listOf("mai", "may"), listOf("juin", "june", "jun"),
    listOf("juillet", "juil", "july", "jul"), listOf("aout", "août", "august", "aug"), listOf("septembre", "september", "sept", "sep"),
    listOf("octobre", "october", "oct"), listOf("novembre", "november", "nov"), listOf("decembre", "décembre", "december", "dec", "déc"),
)
private val MONTH_OF: Map<String, Int> = MONTHS.flatMapIndexed { i, names -> names.map { it to i + 1 } }.toMap()
private val MONTH_WORD = MONTHS.flatten().sortedByDescending { it.length }.joinToString("|")

private val FLIGHT_WORD = Regex("(?i)\\b(vol|vols|flight|flights|flug|vuelo|volo|n° de vol|flight no|flight number|numéro de vol)\\b")
private val FLIGHT_NO = Regex("(?<![A-Za-z0-9])([A-Z][A-Z0-9]|[0-9][A-Z])\\s?(\\d{1,4})(?![0-9])")
private val NOT_AIRLINES = setOf("AM", "PM", "UE", "EU", "TO", "NO", "ID", "OK", "TV", "CB", "PC", "PO", "CP", "RN", "RD", "TE", "KM", "HT")

private data class Found<T>(val at: Int, val value: T)

/** The dates written in a text, where they are; a date without its year is the next one after [after]. */
private fun datesIn(text: String, after: LocalDate): List<Found<LocalDate>> {
    val out = ArrayList<Found<LocalDate>>()
    fun add(at: Int, y: Int?, m: Int, d: Int) {
        val year = y?.let { if (it < 100) 2000 + it else it }
        val date = try { LocalDate.of(year ?: after.year, m, d) } catch (_: Exception) { return }
        out += Found(at, if (year == null && date.isBefore(after.minusDays(1))) date.plusYears(1) else date)
    }
    Regex("(?<!\\d)(\\d{1,2})[/.](\\d{1,2})[/.](\\d{4}|\\d{2})(?!\\d)").findAll(text).forEach { add(it.range.first, it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }
    Regex("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)").findAll(text).forEach { add(it.range.first, it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
    Regex("(?i)(?<![\\d:])(\\d{1,2})(?:er)?\\s*($MONTH_WORD)\\.?(?:\\s*(\\d{4}))?(?![a-z])").findAll(text).forEach {
        MONTH_OF[it.groupValues[2].lowercase()]?.let { m -> add(it.range.first, it.groupValues[3].toIntOrNull(), m, it.groupValues[1].toInt()) }
    }
    Regex("(?i)(?<![a-z])($MONTH_WORD)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?(?:\\s+(\\d{4}))?(?!\\d)").findAll(text).forEach {
        MONTH_OF[it.groupValues[1].lowercase()]?.let { m -> add(it.range.first, it.groupValues[3].toIntOrNull(), m, it.groupValues[2].toInt()) }
    }
    return out
}

/** The flights in a mail's subject and text, received on [received]. */
internal fun findFlightsInMail(text: String, received: LocalDate): List<MailFlight> {
    val t = text.replace(' ', ' ').replace(Regex("[ \\t]+"), " ").take(40_000)
    val words = FLIGHT_WORD.findAll(t).map { it.range.first }.toList()
    if (words.isEmpty()) return emptyList()
    val dates = datesIn(t, received)
    if (dates.isEmpty()) return emptyList()
    val times = Regex("(?<![\\d:])([01]?\\d|2[0-3])\\s?[:hH]\\s?([0-5]\\d)(?!\\d)").findAll(t).map { Found(it.range.first, LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt())) }.toList()
    val gate = Regex("(?i)\\b(?:porte|gate)(?:\\s+d.embarquement)?\\s*:?\\s*([A-Z]?\\d{1,3}[A-Z]?)\\b").find(t)?.groupValues?.get(1)
    val terminal = Regex("(?i)\\bterminal\\s*:?\\s*(\\d[A-Z]?|[A-Z]\\d?)\\b").find(t)?.groupValues?.get(1)?.uppercase()
    val out = LinkedHashMap<String, MailFlight>()
    for (m in FLIGHT_NO.findAll(t)) {
        val code = m.groupValues[1]
        if (code in NOT_AIRLINES || m.groupValues[2].length < 2) continue
        val at = m.range.first
        // a gate, a seat or a terminal is not a flight
        if (Regex("(?i)(gate|porte|seat|siège|siege|place|terminal|salle)\\W{0,3}$").containsMatchIn(t.substring(maxOf(0, at - 14), at))) continue
        // a flight number is written near a travel word
        if (words.none { kotlin.math.abs(it - at) < 90 }) continue
        val date = dates.minByOrNull { kotlin.math.abs(it.at - at) }?.takeIf { kotlin.math.abs(it.at - at) < 500 }?.value ?: continue
        // the departure time is written after the flight number (a time just before is often the previous flight's arrival)
        val time = (times.firstOrNull { it.at > at && it.at < at + 300 } ?: times.lastOrNull { it.at < at && it.at > at - 60 })?.value
        val flight = code + m.groupValues[2]
        val key = "$flight|$date"
        if (key !in out) out[key] = MailFlight(flight, date, time, gate, terminal)
    }
    return out.values.take(6)
}

/** A trip's flight kept: what, when (its time in UTC ms; noon when the time is not known), from and to where, the gate. */
@Serializable
internal data class Trip(
    val flight: String, val callsign: String, val date: String, val time: String? = null, val zone: String? = null,
    val origin: String = "", val originCity: String = "", val dest: String = "", val destCity: String = "",
    val destLat: Double? = null, val destLon: Double? = null, val gate: String? = null, val terminal: String? = null,
    val subject: String = "", val departMs: Long = 0L, val announced: Boolean = false,
)

/** "AF1234 Paris (CDG) → New York (JFK), jeudi 15 octobre à 10h35, porte K42, terminal 2E". */
internal fun tripWords(t: Trip): String {
    val day = LocalDate.parse(t.date).format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE))
    return "${t.flight} ${t.originCity.ifBlank { t.origin }} (${t.origin}) → ${t.destCity.ifBlank { t.dest }} (${t.dest}), $day" +
        (t.time?.let { " à ${it.replace(':', 'h')} (heure locale du départ)" } ?: " (heure non trouvée dans le mail)") +
        (t.terminal?.let { ", terminal $it" } ?: "") + (t.gate?.let { ", porte $it" } ?: "")
}

/** When the reminder rings: 3 hours before, or 6 a.m. that day when the time is not known. */
internal fun tripAlarmMs(t: Trip): Long {
    val zone = t.zone?.let { try { ZoneId.of(it) } catch (_: Exception) { null } } ?: ZoneId.systemDefault()
    return if (t.time != null) t.departMs - 3 * 3_600_000L else LocalDate.parse(t.date).atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
}

/** The day a mail was received, from its Date header (RFC 2822), or today. */
internal fun mailDay(header: String): LocalDate = try {
    ZonedDateTime.parse(header.replace(Regex("\\s*\\(.*\\)$"), "").trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate()
} catch (_: Exception) {
    LocalDate.now()
}

internal object FlightMail {
    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs(c: Context) = c.getSharedPreferences("flight_mail", Context.MODE_PRIVATE)
    fun trips(c: Context): List<Trip> = try { prefs(c).getString("trips", null)?.let { json.decodeFromString<List<Trip>>(it) } ?: emptyList() } catch (_: Exception) { emptyList() }
    private fun put(c: Context, list: List<Trip>) { prefs(c).edit().putString("trips", json.encodeToString(list)).apply(); program(c) }
    fun forget(c: Context) = put(c, emptyList())
    /** Keeps one more trip (the debug build's test). */
    internal fun add(c: Context, t: Trip) = put(c, trips(c).filter { it.flight != t.flight || it.date != t.date } + t)
    fun auto(c: Context) = prefs(c).getBoolean("auto", false)

    private const val QUERY = "newer_than:120d (vol OR flight OR \"carte d'embarquement\" OR \"boarding pass\" OR itinéraire OR itinerary OR " +
        "\"e-ticket\" OR \"billet électronique\" OR réservation OR booking OR confirmation) -category:promotions"

    /** Reads the travel mails of the last four months; the flights to come are kept and their reminders set. */
    suspend fun scan(ctx: JarvisContainer): List<Trip> {
        val c = ctx.appContext
        val api = GoogleApi(c, ctx.http)
        val today = LocalDate.now()
        val found = LinkedHashMap<String, Pair<MailFlight, String>>()
        for (s in api.mailList(QUERY, false, 15)) {
            val (_, body) = try { api.mailRead(s.id) } catch (e: GoogleException) { continue }
            findFlightsInMail(s.subject + "\n" + body, mailDay(s.date)).filter { !it.date.isBefore(today) }.forEach { f ->
                val k = "${f.flight}|${f.date}"
                val prev = found[k]?.first
                // the same flight in several mails: keep what each adds (the boarding pass has the gate)
                found[k] = (prev?.copy(time = prev.time ?: f.time, gate = f.gate ?: prev.gate, terminal = f.terminal ?: prev.terminal) ?: f) to s.subject
            }
        }
        val old = trips(c).associateBy { "${it.flight}|${it.date}" }
        val trips = found.values.mapNotNull { (f, subject) ->
            val route = Flights.route(ctx, f.flight) ?: return@mapNotNull null
            val zone = route.originLat?.let { lat -> zoneAt(ctx, lat, route.originLon ?: 0.0) }
            val z = zone?.let { try { ZoneId.of(it) } catch (_: Exception) { null } } ?: ZoneId.systemDefault()
            val depart = LocalDateTime.of(f.date, f.time ?: LocalTime.NOON).atZone(z).toInstant().toEpochMilli()
            Trip(
                f.flight, route.callsign, f.date.toString(), f.time?.toString(), zone, route.route.originCode, route.route.originCity,
                route.route.destinationCode, route.route.destinationCity, route.destLat, route.destLon, f.gate, f.terminal, subject.take(120), depart,
                announced = old["${f.flight}|${f.date}"]?.announced == true,
            )
        }.sortedBy { it.departMs }
        put(c, trips)
        return trips
    }

    /** The time zone of a place (Open-Meteo), e.g. "America/New_York". */
    private suspend fun zoneAt(ctx: JarvisContainer, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        try {
            ctx.http.newCall(Request.Builder().url("https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&timezone=auto&current=cloud_cover".format(Locale.US, lat, lon)).build())
                .execute().use { r -> if (r.isSuccessful) ((Json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject)?.get("timezone") as? JsonPrimitive)?.contentOrNull else null }
        } catch (_: Exception) {
            null
        }
    }

    fun setAuto(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("auto", on).apply()
        val wm = WorkManager.getInstance(c)
        if (on) wm.enqueueUniquePeriodicWork(
            "flight_mail", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<FlightMailWorker>(12, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        ) else wm.cancelUniqueWork("flight_mail")
    }

    /** One alarm, for the soonest trip not told yet. */
    private fun program(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val next = trips(c).filter { !it.announced && it.departMs > System.currentTimeMillis() - 3_600_000L }.minOfOrNull { tripAlarmMs(it) }
        if (next == null) { am.cancel(intent(c)); return }
        val at = maxOf(next, System.currentTimeMillis() + 5_000)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) throw SecurityException("not allowed")
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c))
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c))
        }
    }

    fun afterBoot(c: Context) { if (trips(c).isNotEmpty()) program(c) }

    private fun intent(c: Context) = PendingIntent.getBroadcast(c, 7_990, Intent(c, TripReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** The day of a flight: told, and followed from now to its landing. */
    fun ring(c: Context) {
        val now = System.currentTimeMillis()
        val list = trips(c)
        val due = list.filter { !it.announced && tripAlarmMs(it) <= now + 60_000 }
        due.forEach { t ->
            val text = "Vol " + tripWords(t) + ". Je le suis : je vous dirai quand il décolle (et son retard) et quand il atterrit."
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("jarvis_flights", tr("Vols suivis"), NotificationManager.IMPORTANCE_HIGH))
            try {
                NotificationManagerCompat.from(c).notify(
                    ("trip" + t.flight).hashCode(),
                    NotificationCompat.Builder(c, "jarvis_flights").setSmallIcon(android.R.drawable.ic_menu_send).setContentTitle(tr("Votre vol aujourd’hui"))
                        .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build(),
                )
            } catch (_: SecurityException) {
            }
            FlightWatch.start(
                c, WatchedFlight(t.flight, t.callsign, "${t.destCity.ifBlank { t.dest }} (${t.dest})", t.destLat, t.destLon, t.departMs + 20 * 3_600_000L, scheduledMs = if (t.time != null) t.departMs else 0L),
            )
        }
        // the ones told stay listed until their day is over
        put(c, list.map { t -> if (due.any { it.flight == t.flight && it.date == t.date }) t.copy(announced = true) else t }.filter { LocalDate.parse(it.date).plusDays(1) >= LocalDate.now() })
    }
}

class TripReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch { try { FlightMail.ring(context.applicationContext) } finally { pending.finish() } }
    }
}

class FlightMailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try { FlightMail.scan((applicationContext as JarvisApp).container) } catch (_: Exception) {}
        return Result.success()
    }
}

/** "Quels sont mes prochains vols ?", "trouve mes vols dans mes mails", "suis automatiquement mes vols". */
object MyFlightsTool : Tool {
    override val name = "my_flights"
    override val description =
        "Les vols de l’utilisateur trouvés dans ses mails Gmail (réservations, cartes d’embarquement) : action « scan » : cherche dans " +
            "les mails des 4 derniers mois et garde les vols à venir (numéro, date, heure, trajet, porte et terminal si le mail les donne) ; " +
            "« list » : les vols gardés ; « auto_on » : cherche tout seul deux fois par jour ; « auto_off » ; « forget » : oublie la liste. " +
            "Le jour du vol, une notification 3 heures avant, puis le vol est suivi : décollage (avec son retard par rapport à l’heure " +
            "prévue) et atterrissage. Les portes ne sont connues que si la compagnie les a écrites dans un mail."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "scan, list, auto_on, auto_off ou forget.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val c = ctx.appContext
        fun listed(list: List<Trip>) = list.joinToString("\n") { "- " + tripWords(it) }
        return when (args.stringArg("action").trim().lowercase()) {
            "list" -> FlightMail.trips(c).let { if (it.isEmpty()) "Aucun vol gardé (action scan pour chercher dans les mails)." else "Vos prochains vols :\n" + listed(it) }
            "forget" -> { FlightMail.forget(c); "J’ai oublié la liste des vols." }
            "auto_off" -> { FlightMail.setAuto(c, false); "Je ne cherche plus les vols dans vos mails." }
            else -> {
                val auto = args.stringArg("action").trim().lowercase() == "auto_on"
                if (auto) FlightMail.setAuto(c, true)
                val list = try { FlightMail.scan(ctx) } catch (e: GoogleException) { return e.message ?: "Gmail n’est pas accessible." }
                (if (auto) "Je chercherai vos vols dans vos mails deux fois par jour. " else "") +
                    if (list.isEmpty()) "Aucun vol à venir trouvé dans vos mails des 4 derniers mois."
                    else "Vols à venir trouvés dans vos mails (je préviendrai le jour même et je les suivrai) :\n" + listed(list)
            }
        }
    }
}
