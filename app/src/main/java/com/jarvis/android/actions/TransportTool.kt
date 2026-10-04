package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.transport.Stop
import com.jarvis.android.transport.TRANSITOUS_BASE
import com.jarvis.android.transport.TransitKind
import com.jarvis.android.transport.describeBoard
import com.jarvis.android.transport.describeDeparture
import com.jarvis.android.transport.describeJourney
import com.jarvis.android.transport.parseDepartures
import com.jarvis.android.transport.parseJourneys
import com.jarvis.android.transport.parseStopArea
import com.jarvis.android.transport.parseStopTimes
import com.jarvis.android.transport.parseTransitStops
import com.jarvis.android.transport.stopsToTry
import com.jarvis.android.transport.toNavitia
import com.jarvis.android.location.Fix
import com.jarvis.android.location.LocationOutcome
import com.jarvis.android.location.locate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.Instant
import java.util.Base64
import java.util.Locale
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.stringArg
import com.jarvis.android.tool.objectSchema

/**
 * Trains (SNCF) and local transport (navitia.io): next connections and departures (see transport/Transport.kt). Without the
 * service's key, the departures come from Transitous instead, which needs none (see transport/Transitous.kt).
 */
object TransportTool : Tool {
    override val name = "transport"
    override val description =
        "Transports en commun. journey (« quel est le prochain train pour Rennes ? », « un train Paris–Lyon vers 18 h » : to, from facultatif " +
            "— sans from, depuis la position du téléphone —, time HH:MM facultatif) ; departures (« les prochains départs de la gare de Brest », " +
            "« mon bus passe quand ? », « le prochain tram » : station, ou l'arrêt le plus proche du téléphone). network 'train' (SNCF), 'local' " +
            "(bus, tram, métro) ou vide. Les départs marchent partout en France même sans clé ; les trajets (journey) demandent la clé SNCF. " +
            "Horaires en temps réel quand le transporteur les donne, avec les retards."
    override val parameters = objectSchema {
        string("action", "'journey' (défaut) ou 'departures'.")
        string("to", "Pour journey : gare ou ville d'arrivée.")
        string("from", "Pour journey : gare ou ville de départ (vide : la position du téléphone).")
        string("station", "Pour departures : gare ou arrêt (vide : le plus proche).")
        string("time", "Heure de départ HH:MM (vide : maintenant).")
        string("network", "'train', 'local' (bus, tram, métro) ou vide (tout ; avec une clé SNCF, les trains).")
    }

    private sealed interface Net {
        data class Ok(val base: String, val key: String) : Net
        data class Fail(val why: String) : Net
    }

    private fun get(ctx: JarvisContainer, url: String, key: String): Pair<Int, String> {
        val auth = "Basic " + Base64.getEncoder().encodeToString("$key:".toByteArray())
        return ctx.http.newCall(Request.Builder().url(url).header("Authorization", auth).build()).execute().use { it.code to it.body?.string().orEmpty() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** "18:30", "7h", "7 h 05" → that time today, or tomorrow when it is more than half an hour ago; [now] otherwise. */
    private fun timeArg(args: JsonObject, now: LocalDateTime): LocalDateTime =
        args.stringArg("time").trim().takeIf { it.isNotEmpty() }?.let { t ->
            Regex("^(\\d{1,2})\\s*[:hH]\\s*(\\d{2})?$").find(t)?.let { m ->
                val clock = LocalTime.of(m.groupValues[1].toInt().coerceIn(0, 23), m.groupValues[2].ifEmpty { "0" }.toInt().coerceIn(0, 59))
                now.toLocalDate().atTime(clock).let { if (it.isBefore(now.minusMinutes(30))) it.plusDays(1) else it }
            }
        } ?: now

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val network = args.stringArg("network").trim().lowercase()
        val local = network in setOf("local", "bus", "tram", "metro", "métro")
        val here = suspend {
            (locate(ctx.appContext, maxAgeMs = 10 * 60_000L) as? LocationOutcome.Found)?.fix
        }
        if (args.stringArg("action").trim().lowercase() == "departures") {
            val key = if (local) ctx.configStore.getNavitiaKey() else ctx.configStore.getSncfKey()
            if (key.isNullOrBlank()) {
                val kind = when {
                    local -> TransitKind.LOCAL
                    network in setOf("train", "trains", "sncf", "ter", "tgv", "rer") -> TransitKind.TRAIN
                    else -> TransitKind.ANY
                }
                return@withContext keylessDepartures(ctx, args.stringArg("station").trim(), kind, timeArg(args, LocalDateTime.now()), here)
            }
        }
        val net: Net = if (local) {
            val key = ctx.configStore.getNavitiaKey()?.takeIf { it.isNotBlank() }
            val fix = here()
            when {
                key == null -> Net.Fail("Pour les bus, trams et métros, il faut une clé navitia.io gratuite dans les réglages de Jarvis (carte Transports).")
                fix == null -> Net.Fail("Il faut la position du téléphone pour savoir quel réseau de bus interroger (Paramètres > Position).")
                else -> Net.Ok("https://api.navitia.io/v1/coverage/" + String.format(Locale.ROOT, "%.5f;%.5f", fix.longitude, fix.latitude), key)
            }
        } else {
            ctx.configStore.getSncfKey()?.takeIf { it.isNotBlank() }?.let { Net.Ok("https://api.sncf.com/v1/coverage/sncf", it) }
                ?: Net.Fail("Pour les horaires de train, il faut une clé SNCF gratuite dans les réglages de Jarvis (carte Transports).")
        }
        if (net is Net.Fail) return@withContext net.why
        net as Net.Ok
        try {
            fun stopNamed(name: String): Stop? {
                val (code, body) = get(ctx, "${net.base}/places?q=${enc(name)}&type[]=stop_area&count=5", net.key)
                if (code == 401 || code == 403) throw SecurityException()
                return if (code == 200) parseStopArea(body) else null
            }
            suspend fun nearestStop(): Stop? {
                val fix = here() ?: return null
                val (code, body) = get(ctx, "${net.base}/coord/" + String.format(Locale.ROOT, "%.5f;%.5f", fix.longitude, fix.latitude) +
                    "/places_nearby?type[]=stop_area&distance=5000&count=1", net.key)
                if (code == 401 || code == 403) throw SecurityException()
                return if (code == 200) parseStopArea(body) else null
            }
            val at = timeArg(args, LocalDateTime.now())
            if (args.stringArg("action").trim().lowercase() == "departures") {
                val name = args.stringArg("station").trim()
                val stop = (if (name.isNotEmpty()) stopNamed(name) else nearestStop())
                    ?: return@withContext if (name.isNotEmpty()) "Je ne trouve pas la gare ou l'arrêt « $name »." else "Aucune gare ou arrêt trouvé près du téléphone."
                val (code, body) = get(ctx, "${net.base}/stop_areas/${enc(stop.id)}/departures?count=6&from_datetime=${toNavitia(at)}", net.key)
                if (code == 401 || code == 403) throw SecurityException()
                val list = if (code == 200) parseDepartures(body) else emptyList()
                return@withContext if (list.isEmpty()) "Aucun départ trouvé depuis ${stop.name} pour le moment."
                else "Prochains départs de ${stop.name} :\n" + list.joinToString("\n") { describeDeparture(it) }
            }
            val toName = args.stringArg("to").trim()
            if (toName.isEmpty()) return@withContext "Vers quelle gare ou quelle ville ?"
            val to = stopNamed(toName) ?: return@withContext "Je ne trouve pas « $toName »."
            val fromName = args.stringArg("from").trim()
            val (fromId, fromLabel) = if (fromName.isNotEmpty() && fromName.lowercase() !in setOf("ici", "ma position")) {
                val s = stopNamed(fromName) ?: return@withContext "Je ne trouve pas « $fromName »."
                s.id to s.name
            } else {
                val fix = here() ?: return@withContext "Je n'ai pas la position du téléphone : dites d'où vous partez."
                String.format(Locale.ROOT, "%.5f;%.5f", fix.longitude, fix.latitude) to "votre position"
            }
            val (code, body) = get(ctx, "${net.base}/journeys?from=${enc(fromId)}&to=${enc(to.id)}&datetime=${toNavitia(at)}&count=3", net.key)
            if (code == 401 || code == 403) throw SecurityException()
            val journeys = if (code == 200) parseJourneys(body) else emptyList()
            val disruptions = if (code == 200) com.jarvis.android.transport.parseDisruptions(body) else emptyList()
            if (journeys.isEmpty()) "Aucun trajet trouvé de $fromLabel à ${to.name} à cette heure-là." + (if (disruptions.isNotEmpty()) " Perturbations : " + disruptions.take(2).joinToString(" ; ") else "")
            else "De $fromLabel à ${to.name} :\n" + journeys.take(3).mapIndexed { i, j -> "${i + 1}) ${describeJourney(j)}" }.joinToString("\n") +
                (if (disruptions.isNotEmpty()) "\nPerturbations : " + disruptions.take(3).joinToString(" ; ") else "")
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            "La clé ${if (local) "navitia.io" else "SNCF"} est refusée : vérifiez-la dans les réglages de Jarvis (carte Transports)."
        } catch (_: Exception) {
            "Horaires indisponibles pour le moment (connexion ?)."
        }
    }

    /**
     * The next departures through Transitous, which needs no key: the stop named, or the nearest ones to the phone (a few are
     * tried, the nearest first, until one has departures of the [kind] asked).
     */
    private suspend fun keylessDepartures(
        ctx: JarvisContainer,
        name: String,
        kind: TransitKind,
        at: LocalDateTime,
        here: suspend () -> Fix?,
    ): String {
        val version = runCatching { ctx.appContext.packageManager.getPackageInfo(ctx.appContext.packageName, 0).versionName }.getOrNull() ?: "?"
        // Transitous asks for the app's name, its version and a way of contact in every request.
        val agent = "Jarvis-Android/$version (+https://github.com/Haseosama/Jarvis-Android)"
        fun fetch(path: String): Pair<Int, String> =
            ctx.http.newCall(Request.Builder().url("$TRANSITOUS_BASE$path").header("User-Agent", agent).build()).execute()
                .use { it.code to it.body?.string().orEmpty() }
        val what = when (kind) {
            TransitKind.TRAIN -> "de train"
            TransitKind.LOCAL -> "de bus, tram ou métro"
            TransitKind.ANY -> ""
        }
        return try {
            val fix = here()
            val stops = if (name.isNotEmpty()) {
                val bias = fix?.let { "&place=" + String.format(Locale.ROOT, "%.5f,%.5f", it.latitude, it.longitude) }.orEmpty()
                val (code, body) = fetch("/v1/geocode?text=${enc(name)}&type=STOP&language=fr&numResults=5$bias")
                if (code == 429) return "Le service des horaires est saturé, réessayez dans un moment."
                (if (code == 200) parseTransitStops(body) else emptyList()).take(2)
                    .ifEmpty { return "Je ne trouve pas la gare ou l'arrêt « $name »." }
            } else {
                fix ?: return "Il faut la position du téléphone pour trouver l'arrêt le plus proche (Paramètres > Position), ou dites lequel."
                val (code, body) = fetch("/v1/reverse-geocode?place=" + String.format(Locale.ROOT, "%.5f,%.5f", fix.latitude, fix.longitude) +
                    "&type=STOP&numResults=10")
                if (code == 429) return "Le service des horaires est saturé, réessayez dans un moment."
                stopsToTry(if (code == 200) parseTransitStops(body) else emptyList(), fix.latitude, fix.longitude, kind,
                    maxKm = if (kind == TransitKind.TRAIN) 3.0 else 1.5).take(4)
                    .ifEmpty {
                        return if (kind == TransitKind.TRAIN) "Pas de gare tout près du téléphone : dites laquelle (« les départs de la gare de Brest »)."
                        else "Aucun arrêt trouvé près du téléphone."
                    }
            }
            val zone = ZoneId.systemDefault()
            val time = at.atZone(zone).toOffsetDateTime().toString()
            val now = Instant.now()
            for (stop in stops) {
                val (code, body) = fetch("/v5/stoptimes?stopId=${enc(stop.id)}&n=30&time=${enc(time)}")
                if (code == 429) return "Le service des horaires est saturé, réessayez dans un moment."
                val board = (if (code == 200) parseStopTimes(body) else null) ?: continue
                describeBoard(board.copy(stop = board.stop.ifBlank { stop.name }), kind, now, zone)?.let { return it }
            }
            "Aucun départ${if (what.isNotEmpty()) " $what" else ""} trouvé depuis ${stops.first().name} pour le moment."
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "Horaires indisponibles pour le moment (connexion ?)."
        }
    }
}
