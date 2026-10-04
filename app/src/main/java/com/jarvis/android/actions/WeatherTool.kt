package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.location.isHereRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.weather.formatCurrentWeather
import com.jarvis.android.weather.weatherAt

object WeatherTool : Tool {
    override val name = "weather_report"
    override val description =
        "Obtenir les conditions météo actuelles. Avec une ville, celle-ci ; SANS ville (ou « ici », « chez moi »), la météo à la position du téléphone."
    override val parameters = objectSchema {
        string("city", "Nom de la ville ; vide ou « ici » pour utiliser la position de l’utilisateur.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val rawCity = args.utilityString("city")
        if (isHereRequest(rawCity)) return@withContext weatherHere(ctx)
        val city = normalizedUtilityQuery(rawCity, 200)
            ?: return@withContext "Indiquez une ville de 200 caractères maximum, ou demandez la météo ici."
        try {
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("count", "10").addQueryParameter("language", "fr")
                .addQueryParameter("name", city).build()
            val geoBody = ctx.http.newCall(Request.Builder().url(geoUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext utilityHttpError("Localisation", response.code)
                response.body?.string().orEmpty()
            }
            val place = parseWeatherPlace(geoBody, phoneCountry(ctx))
                ?: return@withContext "Aucune ville trouvée pour « $city ». Précisez son nom."
            val wxUrl = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
                .addQueryParameter("latitude", place.latitude.toString())
                .addQueryParameter("longitude", place.longitude.toString())
                .addQueryParameter("current", "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m")
                .addQueryParameter("temperature_unit", "celsius")
                .addQueryParameter("wind_speed_unit", "kmh").build()
            val wxBody = ctx.http.newCall(Request.Builder().url(wxUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext utilityHttpError("Météo", response.code)
                response.body?.string().orEmpty()
            }
            formatCurrentWeather(wxBody, place.label)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            "Météo indisponible : connexion impossible ou délai dépassé. Réessayez plus tard."
        } catch (_: Exception) {
            "Météo indisponible : données du service incomplètes ou invalides."
        }
    }
}

internal data class WeatherPlace(val latitude: Double, val longitude: Double, val label: String)

/**
 * The place a name means. The geocoder ranks by population, so "Brest" is Brest in Belarus; among several results the
 * first one in the first of [preferCountries] that has one is taken, and the first overall only when none is.
 */
internal fun parseWeatherPlace(body: String, preferCountry: String?): WeatherPlace? = parseWeatherPlace(body, listOfNotNull(preferCountry))

internal fun parseWeatherPlace(body: String, preferCountries: List<String> = emptyList()): WeatherPlace? {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Invalid weather data")
    require(root["error"]?.toString() != "true")
    val results = root["results"] ?: return null
    require(results is JsonArray)
    if (results.isEmpty()) return null
    val preferred = preferCountries.firstNotNullOfOrNull { code ->
        results.firstOrNull { ((it as? JsonObject)?.get("country_code") as? JsonPrimitive)?.contentOrNull.equals(code, ignoreCase = true) }
    }
    val place = (preferred ?: results.first()) as? JsonObject ?: error("Invalid place")
    val lat = (place["latitude"] as? JsonPrimitive)?.doubleOrNull
    val lon = (place["longitude"] as? JsonPrimitive)?.doubleOrNull
    require(lat != null && lat.isFinite() && lat in -90.0..90.0)
    require(lon != null && lon.isFinite() && lon in -180.0..180.0)
    val name = (place["name"] as? JsonPrimitive)?.contentOrNull?.trim()
    require(!name.isNullOrBlank())
    val label = listOfNotNull(name, (place["admin1"] as? JsonPrimitive)?.contentOrNull,
        (place["country"] as? JsonPrimitive)?.contentOrNull)
        .filter { it.isNotBlank() }.distinct().joinToString(", ").take(300)
    return WeatherPlace(lat, lon, label)
}

/** The countries to prefer for a place name, in order: the SIM's, the phone settings', then France (Jarvis speaks French). */
internal fun phoneCountry(ctx: JarvisContainer): List<String> =
    listOfNotNull(
        ctx.appContext.getSystemService(android.telephony.TelephonyManager::class.java)?.simCountryIso,
        Locale.getDefault().country,
        "FR",
    ).filter { it.isNotBlank() }.map { it.uppercase(Locale.ROOT) }.distinct()

/** Weather at the phone's position, when the user has allowed location. */
private suspend fun weatherHere(ctx: JarvisContainer): String {
    val fix = when (val outcome = com.jarvis.android.location.locate(ctx.appContext)) {
        is com.jarvis.android.location.LocationOutcome.Found -> outcome
        com.jarvis.android.location.LocationOutcome.NoPermission ->
            return "Je n’ai pas accès à la position. L’utilisateur peut l’autoriser dans Paramètres > Position (météo), ou dire le nom d’une ville."
        com.jarvis.android.location.LocationOutcome.ServicesOff ->
            return "La localisation du téléphone est désactivée. L’utilisateur doit l’activer, ou dire le nom d’une ville."
        com.jarvis.android.location.LocationOutcome.Unavailable ->
            return "Position introuvable pour le moment (souvent : l’appli n’est pas au premier plan). Demandez le nom d’une ville, ou réessayez avec Jarvis ouvert."
    }
    return try {
        weatherAt(ctx, fix.fix.latitude, fix.fix.longitude, com.jarvis.android.location.positionLabel(fix.place)) ?: "Météo indisponible pour le moment."
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        "Météo indisponible : connexion impossible ou délai dépassé."
    } catch (_: Exception) {
        "Météo indisponible : données du service incomplètes ou invalides."
    }
}
