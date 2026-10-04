package com.jarvis.android.energy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import com.jarvis.android.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.FormBody
import okhttp3.Request
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/*
 * Electricity in France: the Tempo day's colour (blue, white, red: the price from 6 a.m. to 10 p.m. with EDF's Tempo option) for today and
 * tomorrow, from api-couleur-tempo.fr (free, no key, fed by RTE's publication), and the days of each colour left in the season; and
 * EcoWatt, RTE's signal of how tight the grid is (green, orange, red, hour by hour, for four days), with the user's own free RTE key
 * (RTE's data portal gives it; entered in the settings). Advice on what to put off, a warning the evening before, a line in the briefing.
 */

/** A Tempo day: its date and colour (1 blue, 2 white, 3 red; 0 not known yet). */
internal data class TempoDay(val date: String, val code: Int)

internal fun parseTempoDay(json: String): TempoDay? = try {
    val o = Json.parseToJsonElement(json) as JsonObject
    TempoDay((o["dateJour"] as JsonPrimitive).content, (o["codeJour"] as? JsonPrimitive)?.intOrNull ?: 0)
} catch (_: Exception) {
    null
}

/** The days of each colour left in the season. */
internal data class TempoLeft(val blue: Int, val white: Int, val red: Int)

internal fun parseTempoLeft(json: String): TempoLeft? = try {
    val o = Json.parseToJsonElement(json) as JsonObject
    fun n(k: String) = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
    TempoLeft(n("joursBleusRestants"), n("joursBlancsRestants"), n("joursRougesRestants"))
} catch (_: Exception) {
    null
}

internal fun tempoColour(code: Int) = when (code) { 1 -> "bleu"; 2 -> "blanc"; 3 -> "rouge"; else -> "pas encore connu" }

/** What a colour means, and what to put off. */
internal fun tempoAdvice(code: Int): String = when (code) {
    3 -> "électricité très chère de 6 h à 22 h : lancez lave-linge, lave-vaisselle et recharges après 22 h, baissez un peu le chauffage"
    2 -> "électricité plus chère de 6 h à 22 h : décalez ce qui peut attendre aux heures creuses (22 h – 6 h)"
    1 -> "tarif le plus bas"
    else -> "la couleur est publiée vers 11 h la veille"
}

/** An EcoWatt day: its date, its signal (1 green, 2 orange, 3 red), RTE's message, and the hours at orange or red. */
internal data class EcoWattDay(val date: LocalDate, val level: Int, val message: String, val tenseHours: List<Int>)

/** RTE's EcoWatt v5 answer: {"signals":[{"jour":…,"dvalue":…,"message":…,"values":[{"pas":h,"hvalue":v}]}]}. */
internal fun parseEcoWatt(json: String): List<EcoWattDay> = try {
    ((Json.parseToJsonElement(json) as JsonObject)["signals"] as JsonArray).mapNotNull { s ->
        val o = s as? JsonObject ?: return@mapNotNull null
        val day = OffsetDateTime.parse((o["jour"] as JsonPrimitive).content).toLocalDate()
        val hours = (o["values"] as? JsonArray).orEmpty().mapNotNull { v ->
            val vo = v as? JsonObject ?: return@mapNotNull null
            val h = (vo["pas"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val level = (vo["hvalue"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            if (level >= 2) h else null
        }
        EcoWattDay(day, (o["dvalue"] as? JsonPrimitive)?.intOrNull ?: 1, (o["message"] as? JsonPrimitive)?.contentOrNull.orEmpty(), hours)
    }.sortedBy { it.date }
} catch (_: Exception) {
    emptyList()
}

/** "de 8 h à 13 h et de 18 h à 20 h" from the hours listed. */
internal fun hoursWords(hours: List<Int>): String {
    if (hours.isEmpty()) return ""
    val spans = ArrayList<Pair<Int, Int>>()
    hours.sorted().forEach { h -> if (spans.isNotEmpty() && spans.last().second == h - 1) spans[spans.lastIndex] = spans.last().first to h else spans += h to h }
    return spans.joinToString(" et ") { (a, b) -> "de $a h à ${b + 1} h" }
}

internal fun ecoWattWords(d: EcoWattDay, today: LocalDate): String {
    val day = when (d.date) { today -> "aujourd’hui"; today.plusDays(1) -> "demain"; else -> "le " + d.date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d", java.util.Locale.FRANCE)) }
    return when (d.level) {
        3 -> "EcoWatt rouge $day : coupures possibles si la consommation ne baisse pas" + (if (d.tenseHours.isNotEmpty()) " (${hoursWords(d.tenseHours)})" else "") + " ; réduisez au maximum"
        2 -> "EcoWatt orange $day : réseau tendu" + (if (d.tenseHours.isNotEmpty()) " ${hoursWords(d.tenseHours)}" else "") + " ; évitez les gros appareils à ces heures"
        else -> "EcoWatt vert $day : pas de tension sur le réseau"
    }
}

internal object Energy {
    private const val TEMPO = "https://www.api-couleur-tempo.fr/api"
    @Volatile private var ecoCache: Pair<Long, List<EcoWattDay>>? = null

    private fun get(ctx: JarvisContainer, url: String): String? = try {
        ctx.http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-Android").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Exception) {
        null
    }

    suspend fun tempo(ctx: JarvisContainer): Pair<TempoDay?, TempoDay?> = withContext(Dispatchers.IO) {
        get(ctx, "$TEMPO/jourTempo/today")?.let { parseTempoDay(it) } to get(ctx, "$TEMPO/jourTempo/tomorrow")?.let { parseTempoDay(it) }
    }

    suspend fun tempoLeft(ctx: JarvisContainer): TempoLeft? = withContext(Dispatchers.IO) { get(ctx, "$TEMPO/stats")?.let { parseTempoLeft(it) } }

    /**
     * EcoWatt through RTE's API with the user's key (the "ID client en base 64" RTE's portal gives): a token, then the signals. RTE allows
     * one question every 15 minutes: kept an hour.
     */
    suspend fun ecoWatt(ctx: JarvisContainer): List<EcoWattDay>? = withContext(Dispatchers.IO) {
        val key = ctx.configStore.getRteKey()?.takeIf { it.isNotBlank() } ?: return@withContext null
        ecoCache?.takeIf { System.currentTimeMillis() - it.first < 3_600_000L }?.let { return@withContext it.second }
        try {
            val token = ctx.http.newCall(
                Request.Builder().url("https://digital.iservices.rte-france.com/token/oauth/").header("Authorization", "Basic $key").post(FormBody.Builder().build()).build(),
            ).execute().use { r -> if (r.isSuccessful) ((Json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject)?.get("access_token") as? JsonPrimitive)?.contentOrNull else null }
                ?: return@withContext emptyList()
            val days = ctx.http.newCall(Request.Builder().url("https://digital.iservices.rte-france.com/open_api/ecowatt/v5/signals").header("Authorization", "Bearer $token").build())
                .execute().use { r -> if (r.isSuccessful) parseEcoWatt(r.body?.string().orEmpty()) else emptyList() }
            if (days.isNotEmpty()) ecoCache = System.currentTimeMillis() to days
            days
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Today and tomorrow in words. */
    suspend fun words(ctx: JarvisContainer): String {
        val (today, tomorrow) = tempo(ctx)
        val left = tempoLeft(ctx)
        val parts = ArrayList<String>()
        if (today == null && tomorrow == null) parts += "couleur Tempo indisponible"
        today?.let { parts += "jour Tempo ${tempoColour(it.code)} aujourd’hui" + if (it.code >= 2) " (${tempoAdvice(it.code)})" else "" }
        tomorrow?.let { parts += "demain : ${tempoColour(it.code)}" + if (it.code >= 2) " (${tempoAdvice(it.code)})" else if (it.code == 0) " (${tempoAdvice(0)})" else "" }
        left?.let { parts += "il reste ${it.red} jours rouges et ${it.white} jours blancs dans la saison" }
        val today0 = LocalDate.now()
        when (val eco = ecoWatt(ctx)) {
            null -> parts += "EcoWatt : ajoutez votre clé RTE dans les réglages (Électricité) pour le signal du réseau"
            else -> if (eco.isEmpty()) parts += "EcoWatt indisponible (clé RTE refusée ou service occupé)" else eco.filter { !it.date.isBefore(today0) }.take(2).forEach { parts += ecoWattWords(it, today0) }
        }
        return parts.joinToString(". ") { it.replaceFirstChar { c -> c.uppercase() } } + "."
    }

    /** One sentence for the morning briefing when today is white or red, or the grid is tight. */
    suspend fun briefingLine(ctx: JarvisContainer): String? {
        val today = tempo(ctx).first
        val parts = ArrayList<String>()
        if (today != null && today.code >= 2) parts += "jour Tempo ${tempoColour(today.code)} : ${tempoAdvice(today.code)}"
        ecoWatt(ctx)?.firstOrNull { it.date == LocalDate.now() && it.level >= 2 }?.let { parts += ecoWattWords(it, LocalDate.now()) }
        return if (parts.isEmpty()) null else parts.joinToString(" ; ")
    }

    private fun prefs(c: Context) = c.getSharedPreferences("energy_watch", Context.MODE_PRIVATE)
    fun setWatch(c: Context, on: Boolean, white: Boolean) {
        prefs(c).edit().putBoolean("on", on).putBoolean("white", white).apply()
        val wm = WorkManager.getInstance(c)
        if (on) wm.enqueueUniquePeriodicWork(
            "energy_watch", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<EnergyWorker>(3, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        ) else wm.cancelUniqueWork("energy_watch")
    }

    /** Tomorrow red (or white, if asked) or EcoWatt orange or red to come: told once for that day. */
    suspend fun check(c: Context) {
        val p = prefs(c)
        if (!p.getBoolean("on", false)) return
        val ctx = (c.applicationContext as JarvisApp).container
        val parts = ArrayList<Pair<String, String>>()
        tempo(ctx).second?.takeIf { it.code == 3 || (it.code == 2 && p.getBoolean("white", false)) }?.let { parts += "tempo${it.date}" to "Demain, jour Tempo ${tempoColour(it.code)} : ${tempoAdvice(it.code)}" }
        ecoWatt(ctx)?.filter { it.level >= 2 && !it.date.isBefore(LocalDate.now()) }?.forEach { parts += "eco${it.date}${it.level}" to ecoWattWords(it, LocalDate.now()).replaceFirstChar { c2 -> c2.uppercase() } }
        val told = p.getStringSet("told", emptySet()).orEmpty()
        val fresh = parts.filter { it.first !in told }
        if (fresh.isEmpty()) return
        p.edit().putStringSet("told", (told + fresh.map { it.first }).toList().takeLast(60).toSet()).apply()
        val text = fresh.joinToString(". ") { it.second } + "."
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("jarvis_energy", tr("Électricité : Tempo et EcoWatt"), NotificationManager.IMPORTANCE_DEFAULT))
        try {
            NotificationManagerCompat.from(c).notify(
                7_997,
                NotificationCompat.Builder(c, "jarvis_energy").setSmallIcon(android.R.drawable.ic_lock_idle_charging).setContentTitle(tr("Électricité : Tempo et EcoWatt"))
                    .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build(),
            )
        } catch (_: SecurityException) {
        }
    }
}

class EnergyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { try { Energy.check(applicationContext) } catch (_: Exception) {}; return Result.success() }
}

/** "Demain c'est un jour rouge ?", "est-ce que je peux lancer la machine ?", "préviens-moi des jours rouges". */
object EnergyTool : Tool {
    override val name = "electricity"
    override val description =
        "Électricité en France : la couleur Tempo (bleu, blanc, rouge : le prix de 6 h à 22 h avec l’option Tempo d’EDF) aujourd’hui et " +
            "demain, les jours rouges et blancs qui restent dans la saison, et EcoWatt (le signal de RTE sur la tension du réseau, heure par " +
            "heure) si l’utilisateur a mis sa clé RTE dans les réglages ; avec ce qu’il vaut mieux décaler (machine, lave-vaisselle, " +
            "recharge, chauffage). action « now » (défaut) ; « alert_on » (white : oui pour être prévenu aussi des jours blancs) / " +
            "« alert_off » : une notification la veille d’un jour rouge et quand EcoWatt passe à l’orange ou au rouge."
    override val parameters = objectSchema {
        string("action", "now, alert_on ou alert_off.")
        string("white", "Pour alert_on : « oui » pour les jours blancs aussi.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val c = ctx.appContext
        return when (args.stringArg("action").trim().lowercase()) {
            "alert_on" -> {
                val white = args.stringArg("white").trim().lowercase() in setOf("oui", "yes", "true", "1")
                Energy.setWatch(c, true, white)
                "Je vous préviendrai la veille des jours rouges" + (if (white) " et blancs" else "") + ", et quand EcoWatt passe à l’orange ou au rouge" +
                    (if (ctx.configStore.getRteKey().isNullOrBlank()) " (EcoWatt demande votre clé RTE dans les réglages)" else "") + "."
            }
            "alert_off" -> { Energy.setWatch(c, false, false); "Je ne surveille plus Tempo et EcoWatt." }
            else -> Energy.words(ctx) + " Dites-le simplement."
        }
    }
}
