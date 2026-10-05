package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.location.LocationOutcome
import com.jarvis.android.location.distanceKm
import com.jarvis.android.location.isHereRequest
import com.jarvis.android.marine.formatMarineWeather
import com.jarvis.android.marine.formatTides
import com.jarvis.android.marine.marineWeatherUrl
import com.jarvis.android.marine.nearestPort
import com.jarvis.android.marine.modelCell
import com.jarvis.android.marine.seaWindUrl
import com.jarvis.android.marine.tidesUrl
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.time.ZoneId

/*
 * Tides and marine weather — not a Mark-LIII port. A place is geocoded the way weather_report does it, no place
 * (or "ici") is the phone's position; then the closest French port within 50 km is taken, or the point itself
 * elsewhere. The figures and their limits are in marine/Tides.kt.
 */
object TidesTool : Tool {
    override val name = "marees"
    override val description =
        "Marées et météo marine. action « tides » (défaut) : heures de pleine et basse mer aujourd'hui et demain au port le plus " +
            "proche, marnage et coefficients de marée. action « sea » : météo marine (vent en nœuds et force Beaufort, rafales, état " +
            "de la mer, vagues, température de l'eau, et le pire des 12 prochaines heures). Avec un lieu (ville ou port), celui-ci ; " +
            "SANS lieu (ou « ici »), la position du téléphone."
    override val parameters = objectSchema {
        string("place", "Ville ou port ; vide ou « ici » pour la position de l'utilisateur.")
        string("action", "tides ou sea.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val sea = args.stringArg("action").trim().lowercase() in setOf("sea", "meteo", "météo", "weather", "meteo_marine")
        val unavailable = if (sea) "Météo marine indisponible" else "Marées indisponibles"
        val raw = args.utilityString("place")
        val (lat, lon, asked) = if (isHereRequest(raw)) {
            when (val o = com.jarvis.android.location.locate(ctx.appContext)) {
                is LocationOutcome.Found -> Triple(o.fix.latitude, o.fix.longitude, com.jarvis.android.location.positionLabel(o.place))
                LocationOutcome.NoPermission -> return@withContext "Je n'ai pas accès à la position. L'utilisateur peut l'autoriser dans Paramètres > Position (météo), ou dire le nom d'un port."
                LocationOutcome.ServicesOff -> return@withContext "La localisation du téléphone est désactivée. L'utilisateur doit l'activer, ou dire le nom d'un port."
                LocationOutcome.Unavailable -> return@withContext "Position introuvable pour le moment. Demandez le nom d'un port, ou réessayez avec Jarvis ouvert."
            }
        } else {
            val query = normalizedUtilityQuery(raw, 200) ?: return@withContext "Indiquez une ville ou un port de 200 caractères maximum."
            try {
                val geoUrl = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
                    .addQueryParameter("count", "10").addQueryParameter("language", "fr").addQueryParameter("name", query).build()
                val body = get(ctx, geoUrl.toString()) ?: return@withContext "$unavailable : la recherche du lieu a échoué."
                val place = parseWeatherPlace(body, phoneCountry(ctx)) ?: return@withContext "Aucun lieu trouvé pour « $query ». Précisez son nom."
                Triple(place.latitude, place.longitude, place.label)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext "$unavailable : la recherche du lieu a échoué."
            }
        }
        val port = nearestPort(lat, lon)
        val (qLat, qLon) = port?.let { it.first.lat to it.first.lon } ?: (lat to lon)
        val label = port?.let { (p, km) -> if (km < 3) p.name else "${p.name} (à ${km.toInt()} km de $asked)" } ?: asked
        try {
            val body = get(ctx, if (sea) marineWeatherUrl(qLat, qLon) else tidesUrl(qLat, qLon)) ?: return@withContext "$unavailable pour le moment."
            // away from the listed ports, the model's nearest sea cell must still be close: inland, there is no sea to speak of
            if (port == null) {
                val (cellLat, cellLon) = modelCell(body)
                val km = distanceKm(lat, lon, cellLat, cellLon)
                if (km > 30) return@withContext "Pas de mer à moins de ${km.toInt()} km de $asked : dites le nom d'un port."
            }
            val now = System.currentTimeMillis() / 1000
            if (sea) {
                val wind = get(ctx, seaWindUrl(qLat, qLon)) ?: return@withContext "$unavailable pour le moment."
                formatMarineWeather(body, wind, label, now)
            } else {
                formatTides(body, label, now, ZoneId.systemDefault())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            "$unavailable : connexion impossible ou délai dépassé. Réessayez plus tard."
        } catch (_: Exception) {
            "$unavailable : données du service incomplètes ou invalides."
        }
    }

    private fun get(ctx: JarvisContainer, url: String): String? =
        ctx.http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
}
