package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.offline.normalize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

/*
 * Cheapest fuel in France, from the government's live feed (data.economie.gouv.fr, no key). Replaces the JSON plugin of the
 * same name, which went wrong in ways a URL template cannot fix: "Lyon" also matched Chazelles-sur-Lyon and "Paris" matched
 * Cormeilles-en-Parisis; the model had to spell the field exactly ("gazole_prix", and "gazole" was an error); a station out of
 * that fuel could come first; and there was no "près de moi". Named like the plugin so an installed copy is shadowed by it.
 */

private const val FUEL_DATASET =
    "https://data.economie.gouv.fr/api/explore/v2.1/catalog/datasets/prix-des-carburants-en-france-flux-instantane-v2/records"

/** A price older than this is left out: a station that stopped reporting is not "the cheapest". */
internal const val FUEL_MAX_AGE_DAYS = 8L
internal const val FUEL_LISTED = 3

/** A fuel as said to its dataset field and its name in the "carburants_indisponibles" list. */
internal enum class Fuel(val field: String, val label: String) {
    GAZOLE("gazole", "Gazole"), SP95("sp95", "SP95"), SP98("sp98", "SP98"), E10("e10", "E10"), E85("e85", "E85"), GPLC("gplc", "GPLc");

    val priceField get() = "${field}_prix"
    val dateField get() = "${field}_maj"
}

/** "diesel", "gazole", "sans plomb 95", "super", "SP95-E10", "éthanol", "GPL"… → a fuel, or null. */
internal fun parseFuel(text: String): Fuel? {
    val n = normalize(text).replace(" ", "").removeSuffix("prix")
    return when {
        n.isEmpty() -> null
        "gazol" in n || "diesel" in n || n == "go" -> Fuel.GAZOLE
        "e85" in n || "ethanol" in n || "superethanol" in n || "flex" in n -> Fuel.E85
        "gpl" in n -> Fuel.GPLC
        "e10" in n -> Fuel.E10
        "98" in n -> Fuel.SP98
        "95" in n || "sanspomb" in n || "sansplomb" in n || n == "essence" || n == "super" || n == "sp" -> Fuel.SP95
        else -> null
    }
}

internal data class Station(
    val address: String,
    val city: String,
    val postcode: String,
    val price: Double,
    val updated: Instant?,
    val latitude: Double?,
    val longitude: Double?,
    val unavailable: Set<String>,
)

internal fun parseStations(body: String, fuel: Fuel): List<Station> {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Invalid fuel payload")
    root["error_code"]?.let { error("Fuel service error") }
    val results = root["results"] as? JsonArray ?: return emptyList()
    return results.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val price = (o[fuel.priceField] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val geom = o["geom"] as? JsonObject
        Station(
            address = s("adresse"), city = s("ville"), postcode = s("cp"), price = price,
            updated = s(fuel.dateField).takeIf { it.isNotEmpty() }?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() },
            latitude = (geom?.get("lat") as? JsonPrimitive)?.doubleOrNull,
            longitude = (geom?.get("lon") as? JsonPrimitive)?.doubleOrNull,
            unavailable = (o["carburants_indisponibles"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet(),
        )
    }
}

/**
 * The stations worth naming: that fuel actually available, a price no older than [FUEL_MAX_AGE_DAYS], in [city] itself when
 * one was asked for (not a town that merely contains the name), cheapest first (then nearest, then freshest).
 */
internal fun rankStations(stations: List<Station>, fuel: Fuel, now: Instant, city: String?, origin: Pair<Double, Double>?): List<Station> {
    val wantedCity = city?.let { normalize(it) }?.takeIf { it.isNotEmpty() }
    return stations
        .filter { fuel.label !in it.unavailable }
        .filter { it.updated == null || Duration.between(it.updated, now).toDays() < FUEL_MAX_AGE_DAYS }
        .filter { wantedCity == null || wantedCity.all { c -> c.isDigit() } || normalize(it.city) == wantedCity || normalize(it.city).startsWith("$wantedCity ") }
        .sortedWith(compareBy<Station> { it.price }.thenBy { s -> distanceOrMax(s, origin) }.thenByDescending { it.updated })
}

private fun distanceOrMax(s: Station, origin: Pair<Double, Double>?): Double =
    if (origin == null || s.latitude == null || s.longitude == null) Double.MAX_VALUE
    else distanceKm(origin.first, origin.second, s.latitude, s.longitude)

/** "il y a 3 h", "il y a 2 jours" */
internal fun ageLabel(updated: Instant?, now: Instant): String {
    if (updated == null) return "date inconnue"
    val minutes = Duration.between(updated, now).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 60 -> "il y a ${minutes} min"
        minutes < 48 * 60 -> "il y a ${minutes / 60} h"
        else -> "il y a ${minutes / (24 * 60)} jours"
    }
}

internal fun formatStations(ranked: List<Station>, fuel: Fuel, where: String, now: Instant, origin: Pair<Double, Double>?): String {
    if (ranked.isEmpty()) return "Aucune station avec du ${fuel.label} disponible et un prix récent $where."
    val lines = ranked.take(FUEL_LISTED).mapIndexed { i, s ->
        val price = String.format(Locale.FRANCE, "%.3f", s.price)
        val dist = if (origin != null && s.latitude != null && s.longitude != null)
            ", à ${String.format(Locale.FRANCE, "%.1f", distanceKm(origin.first, origin.second, s.latitude, s.longitude))} km" else ""
        "${i + 1}. $price €/L — ${s.address}, ${s.postcode} ${s.city}$dist (prix mis à jour ${ageLabel(s.updated, now)})"
    }
    return "${fuel.label} le moins cher $where :\n" + lines.joinToString("\n") +
        "\n(Source : data.economie.gouv.fr ; le nom de l'enseigne n'est pas fourni, seulement l'adresse.)"
}

object FuelPriceTool : Tool {
    override val name = "prix_carburant"
    override val description =
        "Les stations les moins chères pour un carburant, en France (données officielles en direct). carburant : gazole/diesel, SP95, SP98, E10, " +
            "E85/éthanol, GPL — tel que dit par l'utilisateur. ville : une ville ou un code postal ; vide ou « ici » pour autour de la position du téléphone. " +
            "Ne garde que les stations qui ont vraiment ce carburant et un prix récent."
    override val parameters = objectSchema(required = listOf("carburant")) {
        string("carburant", "Le carburant tel que dit : gazole, diesel, SP95, sans plomb 98, E10, E85, GPL…")
        string("ville", "Une ville ou un code postal ; vide ou « ici » pour autour de la position du téléphone.")
        integer("rayon_km", "Autour de la position : rayon en km, 1 à 30 (défaut 5).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val fuel = parseFuel(args.stringArg("carburant"))
            ?: return@withContext "Carburant inconnu : dites gazole, SP95, SP98, E10, E85 ou GPL."
        val cityArg = args.stringArg("ville").trim()
        val now = Instant.now()
        val here = com.jarvis.android.weather.isHereRequest(cityArg)
        var origin: Pair<Double, Double>? = null
        val where: String
        val filter: String
        if (here) {
            val found = when (val outcome = com.jarvis.android.weather.locate(ctx.appContext)) {
                is com.jarvis.android.weather.LocationOutcome.Found -> outcome
                com.jarvis.android.weather.LocationOutcome.NoPermission ->
                    return@withContext "Je n'ai pas accès à la position : dites une ville, ou autorisez la position dans Paramètres > Position (météo)."
                else -> return@withContext "Position introuvable pour le moment : dites une ville ou un code postal."
            }
            origin = found.fix.latitude to found.fix.longitude
            val radius = args.intArg("rayon_km", 5).coerceIn(1, 30)
            where = "à moins de $radius km de ${com.jarvis.android.weather.positionLabel(found.place)}"
            filter = "within_distance(geom, geom'POINT(${"%.5f".format(Locale.ROOT, found.fix.longitude)} ${"%.5f".format(Locale.ROOT, found.fix.latitude)})', ${radius}km)"
        } else {
            val city = normalizedUtilityQuery(cityArg, 80) ?: return@withContext "Indiquez une ville ou un code postal."
            val safe = city.replace("\"", "").replace("\\", "")
            where = "à $city"
            filter = if (safe.all { it.isDigit() } && safe.length == 5) "cp = \"$safe\"" else "search(ville, \"$safe\")"
        }
        val url = FUEL_DATASET.toHttpUrl().newBuilder()
            .addQueryParameter("where", "$filter and ${fuel.priceField} is not null")
            .addQueryParameter("order_by", fuel.priceField)
            .addQueryParameter("limit", "100")
            .addQueryParameter("select", "adresse,ville,cp,geom,carburants_indisponibles,${fuel.priceField},${fuel.dateField}")
            .build()
        try {
            val body = ctx.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext utilityHttpError("Prix des carburants", r.code)
                r.body?.string().orEmpty()
            }
            val ranked = rankStations(parseStations(body, fuel), fuel, now, if (here) null else cityArg, origin)
            formatStations(ranked, fuel, where, now, origin)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            "Prix des carburants indisponibles : connexion impossible ou délai dépassé."
        } catch (_: Exception) {
            "Prix des carburants indisponibles : réponse du service inexploitable."
        }
    }
}
