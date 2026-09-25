package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.weather.LocationOutcome
import com.jarvis.android.weather.describeRain
import com.jarvis.android.weather.isHereRequest
import com.jarvis.android.weather.parseRainSlots
import com.jarvis.android.weather.rainForecastUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

/** "Est-ce qu'il va pleuvoir dans l'heure ?": the next two hours, in 15-minute steps. */
object RainSoonTool : Tool {
    override val name = "rain_soon"
    override val description =
        "Pluie dans les deux prochaines heures, au quart d'heure près (« est-ce qu'il va pleuvoir dans l'heure ? », « quand est-ce que la pluie s'arrête ? »). " +
            "Avec une ville, celle-ci ; sans ville ou « ici », la position du téléphone. Pour la météo générale ou les jours suivants, utilisez weather_report."
    override val parameters = objectSchema {
        string("city", "Nom de la ville ; vide ou « ici » pour la position du téléphone.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val raw = args.utilityString("city")
        val (lat, lon, label) = if (isHereRequest(raw)) {
            when (val o = com.jarvis.android.weather.locate(ctx.appContext)) {
                is LocationOutcome.Found -> Triple(o.fix.latitude, o.fix.longitude, com.jarvis.android.weather.positionLabel(o.place))
                LocationOutcome.NoPermission -> return@withContext "Je n'ai pas accès à la position : dites une ville, ou autorisez-la dans Paramètres > Position (météo)."
                else -> return@withContext "Position introuvable pour le moment : dites une ville."
            }
        } else {
            val city = normalizedUtilityQuery(raw, 200) ?: return@withContext "Indiquez une ville."
            try {
                val geo = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
                    .addQueryParameter("count", "10").addQueryParameter("language", "fr").addQueryParameter("name", city).build()
                val body = ctx.http.newCall(Request.Builder().url(geo).build()).execute().use { it.body?.string().orEmpty() }
                val place = parseWeatherPlace(body, phoneCountry(ctx)) ?: return@withContext "Aucune ville trouvée pour « $city »."
                Triple(place.latitude, place.longitude, place.label)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext "Localisation de la ville impossible pour le moment."
            }
        }
        try {
            val body = ctx.http.newCall(Request.Builder().url(rainForecastUrl(lat, lon)).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext utilityHttpError("Prévision de pluie", r.code)
                r.body?.string().orEmpty()
            }
            "À $label : " + describeRain(parseRainSlots(body), com.jarvis.android.weather.forecastNow(body)).replaceFirstChar { it.lowercase() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            "Prévision de pluie indisponible : connexion impossible ou délai dépassé."
        } catch (_: Exception) {
            "Prévision de pluie indisponible : réponse du service inexploitable."
        }
    }
}
