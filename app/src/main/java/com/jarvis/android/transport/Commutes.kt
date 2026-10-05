package com.jarvis.android.transport

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
import com.jarvis.android.text.normalize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Base64

/*
 * The user's usual trips ("le boulot : Versailles → Paris Saint-Lazare, en semaine à 7 h 40"): kept, asked about in a word ("comment est
 * mon train du boulot ?"), and looked at 45 minutes before each one on its days; a notification when the train is late, cancelled or
 * the line disrupted, with the operator's words. Trains through SNCF's API, local buses and trams through navitia.io (the keys of
 * the Transports card).
 */

/** A usual trip: its name, from and to (as said), its days, its time, trains or local transport, how long before to look. */
@Serializable
internal data class Commute(val name: String, val from: String, val to: String, val days: List<Int>, val time: String, val local: Boolean = false, val leadMin: Int = 45)

/** "en semaine", "lun-ven", "tous les jours", "le week-end", "lundi, mercredi et vendredi" → ISO days (1 Monday … 7 Sunday). */
internal fun parseDays(words: String): List<Int> {
    // "lun-ven": the dash is a range (normalize would drop it)
    val w = normalize(words.replace("-", " a "))
    if (w.isEmpty() || "semaine" in w && "week" !in w) return listOf(1, 2, 3, 4, 5)
    if ("tous" in w || "chaque jour" in w || "quotidien" in w) return (1..7).toList()
    if ("week" in w && "semaine" !in w) return listOf(6, 7)
    val names = listOf("lun" to 1, "mar" to 2, "mer" to 3, "jeu" to 4, "ven" to 5, "sam" to 6, "dim" to 7)
    Regex("(lun|mar|mer|jeu|ven|sam|dim)[a-z]*\\s*(?:-|a|au)\\s*(lun|mar|mer|jeu|ven|sam|dim)").find(w)?.let { m ->
        val a = names.first { it.first == m.groupValues[1] }.second
        val b = names.first { it.first == m.groupValues[2] }.second
        return if (a <= b) (a..b).toList() else ((a..7) + (1..b)).toList()
    }
    val found = names.filter { (k, _) -> Regex("\\b$k").containsMatchIn(w) }.map { it.second }
    return found.ifEmpty { listOf(1, 2, 3, 4, 5) }
}

internal fun daysWords(days: List<Int>): String = when (days.sorted()) {
    listOf(1, 2, 3, 4, 5) -> "en semaine"
    (1..7).toList() -> "tous les jours"
    listOf(6, 7) -> "le week-end"
    else -> days.sorted().joinToString(", ") { listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")[it - 1] }
}

/** When to look at a trip next: [leadMin] before its time, on its next day (today if not past). */
internal fun nextCheck(c: Commute, now: LocalDateTime): LocalDateTime? {
    val t = try { LocalTime.parse(c.time) } catch (_: Exception) { return null }
    for (i in 0..7) {
        val d = now.toLocalDate().plusDays(i.toLong())
        if (d.dayOfWeek.value !in c.days) continue
        val at = d.atTime(t).minusMinutes(c.leadMin.toLong())
        if (at.isAfter(now)) return at
    }
    return null
}

/** "HH:mm" from "7h40", "07:40", "7 h", or null. */
internal fun clockOf(words: String): String? = Regex("(\\d{1,2})\\s*[h:]\\s*(\\d{2})?").find(words.trim().lowercase())?.let { m ->
    "%02d:%02d".format(m.groupValues[1].toInt().coerceIn(0, 23), (m.groupValues[2].toIntOrNull() ?: 0).coerceIn(0, 59))
}

/** What to tell about a trip's train: on time, late, disrupted, with the operator's words. Null when nothing is wrong (and only problems are wanted). */
internal fun commuteWords(c: Commute, j: Journey?, disruptions: List<String>, onlyProblems: Boolean): String? {
    val trouble = j == null || j.delayMinutes >= 5 || j.disrupted || disruptions.isNotEmpty()
    if (onlyProblems && !trouble) return null
    val head = "Trajet « ${c.name} » (${c.from} → ${c.to})"
    val body = if (j == null) "aucun train trouvé à cette heure (supprimé ?)" else describeJourney(j)
    return "$head : $body" + (if (disruptions.isNotEmpty()) ". Perturbations : " + disruptions.take(2).joinToString(" ; ") else "") + "."
}

/** The Navitia questions both the transport tool and the trips ask: the address and key of a network, a stop by its name, the journeys. */
internal object Navitia {
    sealed interface Net {
        data class Ok(val base: String, val key: String) : Net
        data class Fail(val why: String) : Net
    }

    fun net(ctx: JarvisContainer, local: Boolean, lat: Double?, lon: Double?): Net = if (local) {
        val key = ctx.configStore.getNavitiaKey()?.takeIf { it.isNotBlank() }
        when {
            key == null -> Net.Fail("Pour les bus, trams et métros, il faut une clé navitia.io gratuite dans les réglages de Jarvis (carte Transports).")
            lat == null || lon == null -> Net.Fail("Il faut la position du téléphone pour savoir quel réseau de bus interroger.")
            else -> Net.Ok("https://api.navitia.io/v1/coverage/" + String.format(java.util.Locale.ROOT, "%.5f;%.5f", lon, lat), key)
        }
    } else {
        ctx.configStore.getSncfKey()?.takeIf { it.isNotBlank() }?.let { Net.Ok("https://api.sncf.com/v1/coverage/sncf", it) }
            ?: Net.Fail("Pour les horaires de train, il faut une clé SNCF gratuite dans les réglages de Jarvis (carte Transports).")
    }

    fun get(ctx: JarvisContainer, url: String, key: String): Pair<Int, String> {
        val auth = "Basic " + Base64.getEncoder().encodeToString("$key:".toByteArray())
        return ctx.http.newCall(Request.Builder().url(url).header("Authorization", auth).build()).execute().use { it.code to it.body?.string().orEmpty() }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** A stop area by its name (a SecurityException when the key is refused). */
    fun stopNamed(ctx: JarvisContainer, net: Net.Ok, name: String): Stop? {
        val (code, body) = get(ctx, "${net.base}/places?q=${enc(name)}&type[]=stop_area&count=5", net.key)
        if (code == 401 || code == 403) throw SecurityException()
        return if (code == 200) parseStopArea(body) else null
    }

    /** The journeys between two places ids at a time, with the disruptions' words. */
    fun journeys(ctx: JarvisContainer, net: Net.Ok, fromId: String, toId: String, at: LocalDateTime, count: Int = 3): Pair<List<Journey>, List<String>> {
        val (code, body) = get(ctx, "${net.base}/journeys?from=${enc(fromId)}&to=${enc(toId)}&datetime=${toNavitia(at)}&count=$count", net.key)
        if (code == 401 || code == 403) throw SecurityException()
        return if (code == 200) parseJourneys(body) to parseDisruptions(body) else emptyList<Journey>() to emptyList()
    }
}

internal object Commutes {
    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs(c: Context) = c.getSharedPreferences("commutes", Context.MODE_PRIVATE)
    fun all(c: Context): List<Commute> = try { prefs(c).getString("list", null)?.let { json.decodeFromString<List<Commute>>(it) } ?: emptyList() } catch (_: Exception) { emptyList() }
    fun save(c: Context, list: List<Commute>) { prefs(c).edit().putString("list", json.encodeToString(list)).apply(); program(c) }
    fun watching(c: Context) = prefs(c).getBoolean("watch", false)
    fun setWatch(c: Context, on: Boolean) { prefs(c).edit().putBoolean("watch", on).apply(); program(c) }

    fun find(list: List<Commute>, words: String): Commute? {
        val w = normalize(words)
        return list.firstOrNull { normalize(it.name) == w } ?: list.firstOrNull { w.isNotEmpty() && (w in normalize(it.name) || normalize(it.name) in w) } ?: list.singleOrNull()
    }

    /** One alarm, at the soonest look. */
    private fun program(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val now = LocalDateTime.now()
        val next = if (!watching(c)) null else all(c).mapNotNull { nextCheck(it, now) }.minOrNull()
        if (next == null) { am.cancel(intent(c)); return }
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) throw SecurityException("not allowed")
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c))
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c))
        }
    }

    fun afterBoot(c: Context) { if (watching(c)) program(c) }

    private fun intent(c: Context) = PendingIntent.getBroadcast(c, 7_998, Intent(c, CommuteReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** A trip's next train (the first leaving from its time), and the disruptions; or why not. */
    suspend fun look(ctx: JarvisContainer, cm: Commute, day: LocalDate = LocalDate.now()): Pair<Journey?, List<String>>? = withContext(Dispatchers.IO) {
        val fix = (com.jarvis.android.location.locate(ctx.appContext, 30 * 60_000L) as? com.jarvis.android.location.LocationOutcome.Found)?.fix
        val net = Navitia.net(ctx, cm.local, fix?.latitude, fix?.longitude) as? Navitia.Net.Ok ?: return@withContext null
        val from = Navitia.stopNamed(ctx, net, cm.from) ?: return@withContext null
        val to = Navitia.stopNamed(ctx, net, cm.to) ?: return@withContext null
        val at = day.atTime(LocalTime.parse(cm.time)).minusMinutes(10)
        val (js, dis) = Navitia.journeys(ctx, net, from.id, to.id, at, 3)
        js.firstOrNull { !it.departure.isBefore(at) } to dis
    }

    /** The alarm rang: the trips due now looked at, told when something is wrong; the next alarm set. */
    suspend fun ring(c: Context) {
        val ctx = (c.applicationContext as JarvisApp).container
        val now = LocalDateTime.now()
        all(c).filter { cm -> nextCheck(cm, now.minusMinutes(3))?.let { !it.isAfter(now.plusMinutes(1)) } == true }.forEach { cm ->
            val found = try { look(ctx, cm) } catch (_: Exception) { null } ?: return@forEach
            val text = commuteWords(cm, found.first, found.second, onlyProblems = true) ?: return@forEach
            com.jarvis.android.journal.Journal.alert(c, com.jarvis.android.journal.AlertKind.COMMUTE, text)
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("jarvis_commutes", tr("Trajets habituels"), NotificationManager.IMPORTANCE_HIGH))
            try {
                NotificationManagerCompat.from(c).notify(
                    ("commute" + cm.name).hashCode(),
                    NotificationCompat.Builder(c, "jarvis_commutes").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(tr("Votre trajet"))
                        .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build(),
                )
            } catch (_: SecurityException) {
            }
        }
        program(c)
    }
}

class CommuteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { Commutes.ring(context.applicationContext) } finally { pending.finish() } }
    }
}

/** "Enregistre mon trajet du boulot", "comment est mon train ce matin ?", "préviens-moi s'il y a un problème sur mon trajet". */
object MyTripsTool : Tool {
    override val name = "my_trips"
    override val description =
        "Les trajets habituels de l’utilisateur en train (SNCF) ou en transports locaux : « add » (name : « boulot », from, to, time : " +
            "l’heure du train, days : « en semaine », « lun-ven », « le week-end »…, network : train ou local) ; « list » ; « remove » " +
            "(name) ; « check » (name, vide si un seul : le prochain train de ce trajet, son retard et les perturbations, en mots) ; " +
            "« alert_on » / « alert_off » : regarder 45 minutes avant chaque trajet, ses jours, et prévenir par une notification s’il y a " +
            "un retard, une suppression ou une perturbation (avec le texte du transporteur)."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "add, list, remove, check, alert_on ou alert_off.")
        string("name", "Le nom du trajet (« boulot », « école »).")
        string("from", "Pour add : la gare ou l’arrêt de départ.")
        string("to", "Pour add : la gare ou l’arrêt d’arrivée.")
        string("time", "Pour add : l’heure du départ habituel (« 7h40 »).")
        string("days", "Pour add : les jours (« en semaine » par défaut).")
        string("network", "Pour add : train (défaut) ou local.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val c = ctx.appContext
        val list = Commutes.all(c)
        fun line(cm: Commute) = "« ${cm.name} » : ${cm.from} → ${cm.to}, ${daysWords(cm.days)} à ${cm.time.replace(':', 'h')}" + if (cm.local) " (transports locaux)" else ""
        return when (args.stringArg("action").trim().lowercase()) {
            "add" -> {
                val from = args.stringArg("from").trim()
                val to = args.stringArg("to").trim()
                val time = clockOf(args.stringArg("time")) ?: return "À quelle heure part votre train habituel ?"
                if (from.isEmpty() || to.isEmpty()) return "Dites la gare de départ et celle d’arrivée."
                val name = args.stringArg("name").trim().ifEmpty { to }
                val cm = Commute(name, from, to, parseDays(args.stringArg("days")), time, args.stringArg("network").trim().lowercase() in setOf("local", "bus", "tram", "metro", "métro"))
                Commutes.save(c, list.filterNot { normalize(it.name) == normalize(name) } + cm)
                "Trajet enregistré : ${line(cm)}." + if (!Commutes.watching(c)) " Voulez-vous que je vous prévienne des retards et perturbations 45 minutes avant ? (action alert_on)" else ""
            }
            "list" -> if (list.isEmpty()) "Aucun trajet habituel enregistré." else list.joinToString("\n", "Vos trajets habituels" + (if (Commutes.watching(c)) " (surveillés)" else "") + " :\n") { "- " + line(it) }
            "remove" -> {
                val cm = Commutes.find(list, args.stringArg("name")) ?: return "Je ne trouve pas ce trajet."
                Commutes.save(c, list - cm)
                "Trajet « ${cm.name} » supprimé."
            }
            "alert_on" -> { Commutes.setWatch(c, true); "Je regarderai vos trajets 45 minutes avant, leurs jours, et je vous préviendrai en cas de retard, suppression ou perturbation." + if (list.isEmpty()) " (Enregistrez d’abord un trajet.)" else "" }
            "alert_off" -> { Commutes.setWatch(c, false); "Je ne surveille plus vos trajets." }
            else -> {
                val cm = Commutes.find(list, args.stringArg("name")) ?: return if (list.isEmpty()) "Aucun trajet habituel enregistré : dites par exemple « enregistre mon trajet du boulot de Versailles à Paris Saint-Lazare à 7h40 »." else "Lequel ? " + list.joinToString(", ") { it.name }
                val net = Navitia.net(ctx, cm.local, 0.0, 0.0)
                if (net is Navitia.Net.Fail) return net.why
                // today's train if it has not left yet, else the next day's
                val now = LocalDateTime.now()
                val t = LocalTime.parse(cm.time)
                val day = if (now.dayOfWeek.value in cm.days && now.toLocalTime().isBefore(t.plusMinutes(15))) now.toLocalDate()
                else (1..7).map { now.toLocalDate().plusDays(it.toLong()) }.first { it.dayOfWeek.value in cm.days }
                val found = try { Commutes.look(ctx, cm, day) } catch (_: SecurityException) { return "La clé du transporteur est refusée : vérifiez-la dans les réglages (carte Transports)." } catch (_: Exception) { null }
                    ?: return "Je n’ai pas pu interroger les horaires pour « ${cm.name} »."
                (if (day != now.toLocalDate()) "Prochain : " + day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d", java.util.Locale.FRANCE)) + ". " else "") +
                    commuteWords(cm, found.first, found.second, onlyProblems = false)!!
            }
        }
    }
}
