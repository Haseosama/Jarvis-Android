package com.jarvis.android.space

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.JarvisContainer
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import com.jarvis.android.i18n.tr
import com.jarvis.android.video.VideoPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.jarvis.android.video.SkyModes

/*
 * Rocket launches: the next ones in the world from The Space Devs' Launch Library 2 (free, 15 questions an hour without a key, so the
 * list is kept half an hour), with their countdown, and an alert before the one asked for with its webcast.
 */

/** A launch: what flies, on what, from where, when (to the second, or only the hour, the day, the month), and where to watch it. */
internal data class Launch(
    val id: String, val name: String, val mission: String, val rocket: String, val provider: String, val pad: String, val place: String,
    val netMs: Long, val precision: String, val status: String, val description: String, val orbit: String, val videos: List<String>,
    val image: String, val live: Boolean, val probability: Int?, val padLat: Double?, val padLon: Double?,
) {
    /** The first YouTube webcast, for the player. */
    val youtube: String? get() = videos.firstNotNullOfOrNull { VideoPanel.youtubeId(it) }
    val done: Boolean get() = status in setOf("Success", "Failure", "Partial Failure")
}

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun parseLaunch(o: JsonObject): Launch? {
    val id = o["id"].str().ifBlank { return null }
    val net = try { Instant.parse(o["net"].str()).toEpochMilli() } catch (_: Exception) { return null }
    val pad = o["pad"].obj()
    val mission = o["mission"].obj()
    val videos = (o["vid_urls"] as? JsonArray).orEmpty().mapNotNull { v -> v.obj()?.let { it["priority"].str().toIntOrNull() to it["url"].str() } }
        .filter { it.second.startsWith("https://") }.sortedBy { it.first ?: 99 }.map { it.second }.distinct()
    return Launch(
        id = id,
        name = o["name"].str(),
        mission = mission?.get("name").str(),
        rocket = o["rocket"].obj()?.get("configuration").obj()?.get("full_name").str(),
        provider = o["launch_service_provider"].obj()?.get("name").str(),
        pad = pad?.get("name").str(),
        place = pad?.get("location").obj()?.get("name").str(),
        netMs = net,
        precision = o["net_precision"].obj()?.get("abbrev").str().ifBlank { "SEC" },
        status = o["status"].obj()?.get("abbrev").str(),
        description = mission?.get("description").str(),
        orbit = mission?.get("orbit").obj()?.get("name").str(),
        videos = videos,
        image = o["image"].obj()?.let { it["thumbnail_url"].str().ifBlank { it["image_url"].str() } }.orEmpty(),
        live = (o["webcast_live"] as? JsonPrimitive)?.booleanOrNull == true,
        probability = (o["probability"] as? JsonPrimitive)?.intOrNull,
        padLat = (pad?.get("latitude") as? JsonPrimitive)?.doubleOrNull ?: pad?.get("latitude").str().toDoubleOrNull(),
        padLon = (pad?.get("longitude") as? JsonPrimitive)?.doubleOrNull ?: pad?.get("longitude").str().toDoubleOrNull(),
    )
}

/** The launches of an answer ({"results": […]}, or one launch alone). */
internal fun parseLaunches(json: String): List<Launch> = try {
    val o = Json.parseToJsonElement(json).jsonObject
    val results = o["results"] as? JsonArray
    if (results != null) results.mapNotNull { it.obj()?.let(::parseLaunch) } else listOfNotNull(parseLaunch(o))
} catch (_: Exception) {
    emptyList()
}

/** The status in French. */
internal fun launchStatusWords(status: String): String = when (status) {
    "Go" -> "confirmé"
    "TBC" -> "à confirmer"
    "TBD" -> "date à déterminer"
    "Hold" -> "compte à rebours suspendu"
    "In Flight" -> "en vol"
    "Success" -> "réussi"
    "Failure" -> "échec"
    "Partial Failure" -> "échec partiel"
    else -> status.ifBlank { "inconnu" }
}

/** "dans 2 j 3 h", "dans 3 h 20 min", "dans 12 min", "dans 40 s", "il y a 5 min". */
internal fun countdownWords(ms: Long): String {
    val past = ms < 0
    val s = kotlin.math.abs(ms) / 1000
    val d = s / 86_400
    val h = s % 86_400 / 3600
    val m = s % 3600 / 60
    val words = when {
        d > 0 -> "$d j" + if (h > 0) " $h h" else ""
        h > 0 -> "$h h" + if (m > 0) " $m min" else ""
        m > 0 -> "$m min"
        else -> "${s} s"
    }
    return if (past) "il y a $words" else "dans $words"
}

/** "T-02:13:45" (or "T+…" after), for the countdown on screen. */
internal fun tMinus(ms: Long): String {
    val s = kotlin.math.abs(ms) / 1000
    val d = s / 86_400
    val body = "%02d:%02d:%02d".format(s % 86_400 / 3600, s % 3600 / 60, s % 60)
    return (if (ms >= 0) "T-" else "T+") + (if (d > 0) "${d} j " else "") + body
}

/** When it goes, in words, as precise as it is known: "mercredi 1 octobre à 17h10", "vers le 3 octobre", "en octobre". */
internal fun launchWhen(l: Launch, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(l.netMs).atZone(zone)
    val fr = Locale.FRANCE
    return when (l.precision) {
        "SEC", "MIN" -> t.format(DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH'h'mm", fr))
        "HR" -> t.format(DateTimeFormatter.ofPattern("EEEE d MMMM 'vers' HH'h'", fr))
        "DAY", "AM", "PM" -> t.format(DateTimeFormatter.ofPattern("'le' EEEE d MMMM", fr)) + " (heure pas encore fixée)"
        "W" -> t.format(DateTimeFormatter.ofPattern("'la semaine du' d MMMM", fr))
        "M" -> t.format(DateTimeFormatter.ofPattern("'en' MMMM", fr))
        else -> t.format(DateTimeFormatter.ofPattern("'vers le' d MMMM", fr)) + " (date approximative)"
    }
}

internal object Launches {
    private const val BASE = "https://ll.thespacedevs.com/2.3.0/launches"
    private const val FRESH_MS = 30 * 60_000L

    private fun get(ctx: JarvisContainer, url: String): String? = try {
        ctx.http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-Android").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Exception) {
        null
    }

    /** The next launches (those done within the day are left out), kept half an hour. */
    suspend fun upcoming(ctx: JarvisContainer): List<Launch> = withContext(Dispatchers.IO) {
        val file = File(File(ctx.appContext.cacheDir, "space").apply { mkdirs() }, "launches.json")
        val text = if (file.isFile && System.currentTimeMillis() - file.lastModified() < FRESH_MS) file.readText()
        else get(ctx, "$BASE/upcoming/?limit=15&mode=detailed")?.also { if (parseLaunches(it).isNotEmpty()) file.writeText(it) } ?: file.takeIf { it.isFile }?.readText()
        parseLaunches(text.orEmpty()).filter { !it.done }
    }

    /** One launch as it is now (for an alert: its time may have moved). */
    suspend fun byId(ctx: JarvisContainer, id: String): Launch? = withContext(Dispatchers.IO) { get(ctx, "$BASE/$id/?mode=detailed")?.let { parseLaunches(it).firstOrNull() } }

    /** The launch asked for by words ("Crew-13", "Ariane", "Starship", "le prochain"), among [list]. */
    fun find(list: List<Launch>, words: String): Launch? {
        val w = com.jarvis.android.text.normalize(words)
        if (w.isEmpty() || w in setOf("next", "prochain", "le prochain", "suivant")) return list.firstOrNull()
        val parts = w.split(' ').filter { it.length > 1 }
        return list.firstOrNull { l -> val t = com.jarvis.android.text.normalize("${l.name} ${l.provider} ${l.place}"); parts.all { it in t } }
            ?: list.firstOrNull { l -> com.jarvis.android.text.normalize("${l.name} ${l.provider}").let { t -> parts.any { it.length > 3 && it in t } } }
    }
}

/** A launch to be told of [leadMin] minutes before. */
@Serializable
internal data class LaunchAlert(val id: String, val name: String, val netMs: Long, val leadMin: Int = 30)

internal object LaunchAlerts {
    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs(c: Context) = c.getSharedPreferences("launch_alerts", Context.MODE_PRIVATE)
    fun all(c: Context): List<LaunchAlert> = try { prefs(c).getString("a", null)?.let { json.decodeFromString<List<LaunchAlert>>(it) } ?: emptyList() } catch (_: Exception) { emptyList() }
    private fun put(c: Context, list: List<LaunchAlert>) { prefs(c).edit().putString("a", json.encodeToString(list)).apply(); program(c) }

    fun add(c: Context, a: LaunchAlert): Boolean { put(c, all(c).filter { it.id != a.id } + a); return approximate }
    fun remove(c: Context, id: String?) = put(c, if (id == null) emptyList() else all(c).filter { it.id != id })

    @Volatile private var approximate = false

    /** One alarm, at the soonest alert. */
    private fun program(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val next = all(c).minOfOrNull { it.netMs - it.leadMin * 60_000L }
        if (next == null) { am.cancel(intent(c)); return }
        val at = maxOf(next, System.currentTimeMillis() + 5_000)
        approximate = try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) throw SecurityException("not allowed")
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c))
            false
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c))
            true
        }
    }

    fun afterBoot(c: Context) { if (all(c).isNotEmpty()) program(c) }

    private fun intent(c: Context) = PendingIntent.getBroadcast(c, 7_970, Intent(c, LaunchAlertReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** The alarm rang: each alert due is checked against the launch as it is now (moved: set again; held or done: said so). */
    suspend fun ring(c: Context) {
        val ctx = (c.applicationContext as JarvisApp).container
        val now = System.currentTimeMillis()
        val keep = ArrayList<LaunchAlert>()
        for (a in all(c)) {
            if (a.netMs - a.leadMin * 60_000L > now + 60_000) { keep += a; continue }
            val l = Launches.byId(ctx, a.id)
            when {
                l == null -> notify(c, a.id, "Lancement : ${a.name}", "Décollage prévu ${countdownWords(a.netMs - now)} (je n’ai pas pu vérifier l’heure).", null)
                l.done -> {}
                l.netMs - now > (a.leadMin + 5) * 60_000L -> {
                    // moved later: told once, then the alert follows the new time
                    notify(c, a.id, "Lancement reporté : ${l.name}", "Nouvelle heure : ${launchWhen(l)} (${launchStatusWords(l.status)}).", null)
                    keep += a.copy(netMs = l.netMs)
                }
                l.status == "Hold" || l.status == "TBD" -> notify(c, a.id, "Lancement en attente : ${l.name}", "Statut : ${launchStatusWords(l.status)}.", l.videos.firstOrNull())
                else -> notify(
                    c, a.id, "Décollage ${countdownWords(l.netMs - now)} : ${l.name}",
                    "${l.rocket} depuis ${l.place}" + (l.videos.firstOrNull()?.let { " · touchez pour voir le direct" } ?: ""), l.videos.firstOrNull(),
                )
            }
        }
        put(c, keep)
    }

    private fun notify(c: Context, id: String, title: String, text: String, video: String?) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("jarvis_launches", tr("Lancements de fusées"), NotificationManager.IMPORTANCE_HIGH))
        val open = video?.let {
            PendingIntent.getActivity(c, id.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        }
        try {
            NotificationManagerCompat.from(c).notify(
                id.hashCode(),
                NotificationCompat.Builder(c, "jarvis_launches").setSmallIcon(android.R.drawable.ic_menu_upload).setContentTitle(title).setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).apply { if (open != null) setContentIntent(open) }.build(),
            )
        } catch (_: SecurityException) {
        }
    }
}

class LaunchAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { LaunchAlerts.ring(context.applicationContext) } finally { pending.finish() } }
    }
}

/** "Quand est le prochain lancement ?", "préviens-moi pour Crew-13", "mets le direct du lancement". */
object LaunchTool : Tool {
    override val name = "rocket_launches"
    override val description =
        "Les lancements de fusées dans le monde (SpaceX, Ariane, NASA, Chine, Rocket Lab…), données The Space Devs : action « list » : " +
            "les prochains, affichés à la place du visage avec leur compte à rebours ; « detail » : un lancement (name : « Crew-13 », " +
            "« Ariane », « Starship », vide = le prochain) ; « watch » : son direct (YouTube) dans le lecteur ; « alert » : une notification " +
            "minutes (30 par défaut) avant le décollage, avec le lien du direct, suivie si l’heure change ; « alert_off », « alert_show »."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "list, detail, watch, alert, alert_off ou alert_show.")
        string("name", "Le lancement : nom de la mission, de la fusée ou de l’entreprise ; vide pour le prochain.")
        string("minutes", "Pour alert : combien de minutes avant (30 par défaut).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val action = args.stringArg("action").trim().lowercase()
        val c = ctx.appContext
        if (action == "alert_show") {
            val list = LaunchAlerts.all(c)
            return if (list.isEmpty()) "Aucune alerte de lancement." else list.joinToString("\n", "Alertes de lancement :\n") { "- ${it.name} : ${it.leadMin} min avant (décollage ${countdownWords(it.netMs - System.currentTimeMillis())})" }
        }
        val list = Launches.upcoming(ctx)
        if (list.isEmpty()) return "Je n’arrive pas à obtenir la liste des lancements (service indisponible ou trop sollicité : réessayez dans quelques minutes)."
        val now = System.currentTimeMillis()
        if (action == "list") {
            ctx.videoPanel.show(VideoPanel.Video(title = "Prochains lancements", sky = SkyModes.LAUNCHES))
            return list.take(6).joinToString("\n", "Prochains lancements (données The Space Devs), affichés à la place du visage :\n") {
                "- ${it.name} (${it.provider}), depuis ${it.place} : ${launchWhen(it)}, ${countdownWords(it.netMs - now)}, ${launchStatusWords(it.status)}"
            } + "\nDites les deux ou trois premiers en une phrase chacun."
        }
        val wanted = args.stringArg("name")
        val l = Launches.find(list, wanted) ?: return "Je ne trouve pas « $wanted » parmi les ${list.size} prochains lancements."
        return when (action) {
            "watch" -> {
                val id = l.youtube ?: return "Pas encore de direct YouTube annoncé pour ${l.name}" + (l.videos.firstOrNull()?.let { " (un direct ailleurs : $it)" } ?: "") + "."
                ctx.videoPanel.show(VideoPanel.Video(youtubeId = id, title = l.name, sound = true))
                "Le direct de ${l.name} s’affiche à la place du visage (décollage ${countdownWords(l.netMs - now)})."
            }
            "alert" -> {
                val lead = args.stringArg("minutes").trim().toIntOrNull()?.coerceIn(1, 24 * 60) ?: 30
                val approx = LaunchAlerts.add(c, LaunchAlert(l.id, l.name, l.netMs, lead))
                "Je préviendrai $lead minutes avant le décollage de ${l.name} (${launchWhen(l)}), avec le lien du direct ; si l’heure change, " +
                    "l’alerte suit." + if (approx) " (Les alarmes exactes ne sont pas autorisées : la notification peut avoir quelques minutes de retard.)" else ""
            }
            "alert_off" -> { LaunchAlerts.remove(c, if (wanted.isBlank()) null else l.id); if (wanted.isBlank()) "Toutes les alertes de lancement sont supprimées." else "Plus d’alerte pour ${l.name}." }
            else -> {
                ctx.videoPanel.show(VideoPanel.Video(title = "Prochains lancements", sky = SkyModes.LAUNCHES))
                "${l.name} : ${l.rocket} de ${l.provider}, depuis ${l.pad} (${l.place}). Décollage ${launchWhen(l)}, ${countdownWords(l.netMs - now)} " +
                    "(${launchStatusWords(l.status)}${l.probability?.let { ", météo favorable à $it %" } ?: ""}). Orbite : ${l.orbit.ifBlank { "non précisée" }}. " +
                    l.description.take(500) + (if (l.videos.isNotEmpty()) " Un direct est annoncé." else "") + " Résumez en deux phrases."
            }
        }
    }
}
