package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.transport.Stop
import com.jarvis.android.transport.describeDeparture
import com.jarvis.android.transport.describeJourney
import com.jarvis.android.transport.parseDepartures
import com.jarvis.android.transport.parseJourneys
import com.jarvis.android.transport.parseStopArea
import com.jarvis.android.transport.toNavitia
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
import java.util.Base64
import java.util.Locale
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.stringArg
import com.jarvis.android.tool.objectSchema

/** Trains (SNCF) and local transport (navitia.io): next connections and departures (see transport/Transport.kt). */
object TransportTool : Tool {
    override val name = "transport"
    override val description =
        "Transports en commun. journey (« quel est le prochain train pour Rennes ? », « un train Paris–Lyon vers 18 h » : to, from facultatif " +
            "— sans from, depuis la position du téléphone —, time HH:MM facultatif) ; departures (« les prochains départs de la gare de Brest », " +
            "« mon bus passe quand ? » : station, ou la plus proche). network 'train' (défaut, SNCF) ou 'local' (bus, tram, métro de la ville où " +
            "est le téléphone). Horaires en temps réel quand le transporteur les donne, avec les retards."
    override val parameters = objectSchema {
        string("action", "'journey' (défaut) ou 'departures'.")
        string("to", "Pour journey : gare ou ville d'arrivée.")
        string("from", "Pour journey : gare ou ville de départ (vide : la position du téléphone).")
        string("station", "Pour departures : gare ou arrêt (vide : le plus proche).")
        string("time", "Heure de départ HH:MM (vide : maintenant).")
        string("network", "'train' (défaut) ou 'local'.")
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

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val local = args.stringArg("network").trim().lowercase() in setOf("local", "bus", "tram", "metro", "métro")
        val here = suspend {
            (locate(ctx.appContext, maxAgeMs = 10 * 60_000L) as? LocationOutcome.Found)?.fix
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
            val now = LocalDateTime.now()
            val at = args.stringArg("time").trim().takeIf { it.isNotEmpty() }?.let { t ->
                Regex("^(\\d{1,2})\\s*[:hH]\\s*(\\d{2})?$").find(t)?.let { m ->
                    val clock = LocalTime.of(m.groupValues[1].toInt().coerceIn(0, 23), m.groupValues[2].ifEmpty { "0" }.toInt().coerceIn(0, 59))
                    now.toLocalDate().atTime(clock).let { if (it.isBefore(now.minusMinutes(30))) it.plusDays(1) else it }
                }
            } ?: now
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
}
