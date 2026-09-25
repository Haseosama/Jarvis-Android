package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Aircraft flying around the phone right now, from the OpenSky Network's public API (free, no key,
 * anonymous requests are rate-limited but fine for a spoken question now and then). OpenSky only sees
 * aircraft whose ADS-B transponder reaches one of its volunteer receivers: most airliners, not all
 * light aircraft or military flights — the answer says "vus par OpenSky", not "tous les avions".
 *
 * Satellites are deliberately not here: the free "what is overhead" services (N2YO and the like) all
 * need the user to register their own API key first, the same dead end Google Home and Spotify's Web
 * API would have been.
 */

internal const val PLANES_DEFAULT_RADIUS_KM = 50
internal const val PLANES_MAX_LISTED = 8

/** One aircraft from OpenSky's `states` array (a positional array, not an object — see its REST API docs). */
internal data class PlaneState(
    val callsign: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val onGround: Boolean,
    val speedMs: Double?,
)

internal fun parseOpenSkyStates(body: String): List<PlaneState> {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Invalid OpenSky payload")
    val states = root["states"] as? JsonArray ?: return emptyList() // "states": null when the box is empty
    return states.mapNotNull { el ->
        val a = el as? JsonArray ?: return@mapNotNull null
        fun p(i: Int) = a.getOrNull(i) as? JsonPrimitive
        val lon = p(5)?.doubleOrNull ?: return@mapNotNull null
        val lat = p(6)?.doubleOrNull ?: return@mapNotNull null
        PlaneState(
            callsign = p(1)?.contentOrNull?.trim().orEmpty().ifEmpty { "sans indicatif" },
            country = p(2)?.contentOrNull?.trim().orEmpty(),
            latitude = lat,
            longitude = lon,
            altitudeM = p(7)?.doubleOrNull,
            onGround = p(8)?.booleanOrNull ?: false,
            speedMs = p(9)?.doubleOrNull,
        )
    }
}

/** lamin, lomin, lamax, lomax around a point: a degree of longitude shrinks with latitude, a degree of latitude does not. */
internal fun boundingBox(latitude: Double, longitude: Double, radiusKm: Double): DoubleArray {
    val dLat = radiusKm / 111.0
    val dLon = radiusKm / (111.0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.05))
    return doubleArrayOf(latitude - dLat, longitude - dLon, latitude + dLat, longitude + dLon)
}

/** Great-circle distance in km (haversine). */
internal fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * 6371.0 * asin(sqrt(h))
}

/** Airborne aircraft within [radiusKm] of the point, nearest first, as spoken lines. */
internal fun formatPlanes(planes: List<PlaneState>, latitude: Double, longitude: Double, radiusKm: Int, place: String): String {
    val flying = planes.filter { !it.onGround }
        .map { it to distanceKm(latitude, longitude, it.latitude, it.longitude) }
        .filter { it.second <= radiusKm }
        .sortedBy { it.second }
    if (flying.isEmpty()) return "Aucun avion en vol vu par OpenSky dans un rayon de $radiusKm km autour de $place."
    val lines = flying.take(PLANES_MAX_LISTED).map { (p, d) ->
        val alt = p.altitudeM?.let { "${(it / 100).toInt() * 100} m" } ?: "altitude inconnue"
        val speed = p.speedMs?.let { ", ${(it * 3.6).toInt()} km/h" }.orEmpty()
        val from = if (p.country.isNotEmpty()) " (${p.country})" else ""
        "- ${p.callsign}$from à ${String.format(Locale.FRANCE, "%.0f", d)} km, $alt$speed"
    }
    val more = if (flying.size > PLANES_MAX_LISTED) "\n… et ${flying.size - PLANES_MAX_LISTED} autre(s)." else ""
    return "${flying.size} avion(s) en vol vu(s) par OpenSky dans un rayon de $radiusKm km autour de $place, les plus proches d'abord :\n" +
        lines.joinToString("\n") + more
}

object PlanesOverheadTool : Tool {
    override val name = "planes_overhead"
    override val description =
        "Les avions en vol autour de la position du téléphone en ce moment (réseau OpenSky : la plupart des avions de ligne, pas forcément " +
            "tous les petits avions ni les vols militaires). Pour « quel est cet avion au-dessus de moi », « il y a des avions autour ? ». " +
            "Les satellites ne sont pas disponibles."
    override val parameters = objectSchema {
        integer("radius_km", "Rayon de recherche en km, de 5 à 200 (défaut $PLANES_DEFAULT_RADIUS_KM).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val radius = args.intArg("radius_km", PLANES_DEFAULT_RADIUS_KM).coerceIn(5, 200)
        val fix = when (val outcome = com.jarvis.android.weather.locate(ctx.appContext)) {
            is com.jarvis.android.weather.LocationOutcome.Found -> outcome
            com.jarvis.android.weather.LocationOutcome.NoPermission ->
                return "Je n'ai pas accès à la position. L'utilisateur peut l'autoriser dans Paramètres > Position (météo)."
            com.jarvis.android.weather.LocationOutcome.ServicesOff ->
                return "La localisation du téléphone est désactivée : il faut l'activer pour savoir quels avions sont autour."
            com.jarvis.android.weather.LocationOutcome.Unavailable ->
                return "Position introuvable pour le moment (souvent : l'appli n'est pas au premier plan). Réessayez avec Jarvis ouvert."
        }
        val lat = fix.fix.latitude
        val lon = fix.fix.longitude
        val box = boundingBox(lat, lon, radius.toDouble())
        return try {
            val url = "https://opensky-network.org/api/states/all".toHttpUrl().newBuilder()
                .addQueryParameter("lamin", "%.4f".format(Locale.ROOT, box[0]))
                .addQueryParameter("lomin", "%.4f".format(Locale.ROOT, box[1]))
                .addQueryParameter("lamax", "%.4f".format(Locale.ROOT, box[2]))
                .addQueryParameter("lomax", "%.4f".format(Locale.ROOT, box[3]))
                .build()
            val body = withContext(Dispatchers.IO) {
                ctx.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null to utilityHttpError("OpenSky", response.code)
                    response.body?.string().orEmpty() to null
                }
            }
            body.second?.let { return it }
            formatPlanes(parseOpenSkyStates(body.first!!), lat, lon, radius, com.jarvis.android.weather.positionLabel(fix.place))
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            "OpenSky indisponible : connexion impossible ou délai dépassé."
        } catch (_: Exception) {
            "OpenSky indisponible : réponse inexploitable."
        }
    }
}
