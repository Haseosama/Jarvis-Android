package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.text.normalize
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
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.intArg
import com.jarvis.android.tool.stringArg
import com.jarvis.android.location.distanceKm
import com.jarvis.android.tool.objectSchema

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

/** "1,70", "1.7 €", "1 euro 70", "170 centimes" → euros per litre, or null when it is not a plausible price. */
internal fun parsePriceThreshold(text: String): Double? {
    val t = text.lowercase(Locale.ROOT).replace(',', '.')
    Regex("""(\d+)\s*(?:euros?|€)\s*(\d{1,3})""").find(t)?.let { m ->
        return "${m.groupValues[1]}.${m.groupValues[2].padStart(2, '0').take(3)}".toDouble().takeIf { it in 0.3..5.0 }
    }
    val v = Regex("""\d+(?:\.\d+)?""").find(t)?.value?.toDoubleOrNull() ?: return null
    return when {
        v in 0.3..5.0 -> v
        v in 30.0..500.0 -> v / 100
        else -> null
    }
}

/** What the price watch says when the cheapest station is at or under [threshold], or null. */
internal fun fuelAlertWords(ranked: List<Station>, fuel: Fuel, threshold: Double, where: String, origin: Pair<Double, Double>?): String? {
    val s = ranked.firstOrNull()?.takeIf { it.price <= threshold + 1e-9 } ?: return null
    val dist = if (origin != null && s.latitude != null && s.longitude != null)
        ", à ${String.format(Locale.FRANCE, "%.1f", distanceKm(origin.first, origin.second, s.latitude, s.longitude))} km" else ""
    val more = ranked.drop(1).count { it.price <= threshold + 1e-9 }.takeIf { it > 0 }?.let { " ($it autre${if (it > 1) "s" else ""} sous le seuil)" }.orEmpty()
    return String.format(Locale.FRANCE, "%s à %.3f €/L %s, sous votre seuil de %.3f : %s, %s %s%s%s.", fuel.label, s.price, where, threshold, s.address, s.postcode, s.city, dist, more)
}

/**
 * Whether the watch tells this find: never the same station at the same price twice in a row, and at most once a day unless
 * the price went down since the last one told.
 */
internal fun shouldTellFuel(lastKey: String?, lastDay: String?, lastPrice: Double, key: String, today: String, price: Double): Boolean =
    key != lastKey && (lastDay != today || price < lastPrice - 1e-9)

internal fun fuelAlertKey(s: Station) = "${s.address}|${s.postcode}|${String.format(Locale.ROOT, "%.3f", s.price)}"

/** The feed's filter for a city name or a postcode, or for a circle around a point. */
internal fun cityFilter(city: String): String {
    val safe = city.replace("\"", "").replace("\\", "")
    return if (safe.all { it.isDigit() } && safe.length == 5) "cp = \"$safe\"" else "search(ville, \"$safe\")"
}

internal fun circleFilter(lat: Double, lon: Double, radiusKm: Int) =
    "within_distance(geom, geom'POINT(${"%.5f".format(Locale.ROOT, lon)} ${"%.5f".format(Locale.ROOT, lat)})', ${radiusKm}km)"

internal fun fuelUrl(fuel: Fuel, filter: String) = FUEL_DATASET.toHttpUrl().newBuilder()
    .addQueryParameter("where", "$filter and ${fuel.priceField} is not null")
    .addQueryParameter("order_by", fuel.priceField)
    .addQueryParameter("limit", "100")
    .addQueryParameter("select", "adresse,ville,cp,geom,carburants_indisponibles,${fuel.priceField},${fuel.dateField}")
    .build()

object FuelPriceTool : Tool {
    override val name = "prix_carburant"
    override val description =
        "Les stations les moins chères pour un carburant, en France (données officielles en direct). carburant : gazole/diesel, SP95, SP98, E10, " +
            "E85/éthanol, GPL — tel que dit par l'utilisateur. ville : une ville ou un code postal ; vide ou « ici » pour autour de la position du téléphone. " +
            "trajet : une destination, pour les moins chères le long de la route (à moins de 3 km). Ne garde que les stations qui ont vraiment ce " +
            "carburant et un prix récent. Les stations s'affichent sur la carte à la place du visage, du vert (moins cher) au rouge. " +
            "action « alert_on » (seuil : prix en €/L, ville ou vide pour autour de la position actuelle, rayon_km) : une notification quand ce " +
            "carburant passe sous le seuil, vérifié plusieurs fois par jour ; « alert_off » pour arrêter ; « alert_status » pour dire ce qui est surveillé."
    override val parameters = objectSchema {
        string("action", "Vide pour chercher ; alert_on, alert_off ou alert_status pour l'alerte de prix.")
        string("seuil", "Pour alert_on : le prix en €/L sous lequel prévenir, tel que dit (« 1,70 », « 1 euro 75 »).")
        string("carburant", "Le carburant tel que dit : gazole, diesel, SP95, sans plomb 98, E10, E85, GPL…")
        string("ville", "Une ville ou un code postal ; vide ou « ici » pour autour de la position du téléphone.")
        integer("rayon_km", "Autour de la position : rayon en km, 1 à 30 (défaut 5).")
        string("trajet", "Une destination : les stations les moins chères à moins de 3 km de la route, de la position (ou de ville) jusqu'à elle.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        when (args.stringArg("action").trim().lowercase(Locale.ROOT)) {
            "alert_off" -> { FuelWatch.off(ctx.appContext); return@withContext "Je ne surveille plus le prix du carburant." }
            "alert_status" -> return@withContext FuelWatch.describe(ctx.appContext)
        }
        val fuel = parseFuel(args.stringArg("carburant"))
            ?: return@withContext "Carburant inconnu : dites gazole, SP95, SP98, E10, E85 ou GPL."
        if (args.stringArg("action").trim().equals("alert_on", ignoreCase = true)) return@withContext alertOn(ctx, fuel, args)
        val cityArg = args.stringArg("ville").trim()
        val now = Instant.now()
        args.stringArg("trajet").trim().takeIf { it.isNotEmpty() }?.let { return@withContext alongRoute(ctx, fuel, cityArg, it, now) }
        val here = com.jarvis.android.location.isHereRequest(cityArg)
        var origin: Pair<Double, Double>? = null
        val where: String
        val filter: String
        if (here) {
            val found = when (val outcome = com.jarvis.android.location.locate(ctx.appContext)) {
                is com.jarvis.android.location.LocationOutcome.Found -> outcome
                com.jarvis.android.location.LocationOutcome.NoPermission ->
                    return@withContext "Je n'ai pas accès à la position : dites une ville, ou autorisez la position dans Paramètres > Position (météo)."
                else -> return@withContext "Position introuvable pour le moment : dites une ville ou un code postal."
            }
            origin = found.fix.latitude to found.fix.longitude
            val radius = args.intArg("rayon_km", 5).coerceIn(1, 30)
            where = "à moins de $radius km de ${com.jarvis.android.location.positionLabel(found.place)}"
            filter = circleFilter(found.fix.latitude, found.fix.longitude, radius)
        } else {
            val city = normalizedUtilityQuery(cityArg, 80) ?: return@withContext "Indiquez une ville ou un code postal."
            where = "à $city"
            filter = cityFilter(city)
        }
        val url = fuelUrl(fuel, filter)
        try {
            val body = ctx.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext utilityHttpError("Prix des carburants", r.code)
                r.body?.string().orEmpty()
            }
            val ranked = rankStations(parseStations(body, fuel), fuel, now, if (here) null else cityArg, origin)
            showFuelMap(ctx, ranked.take(30), fuel, null, origin)
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

/** Sets the price watch: a threshold, and a place fixed now (a city, or where the phone is) so it needs no position in the background. */
private suspend fun alertOn(ctx: JarvisContainer, fuel: Fuel, args: JsonObject): String {
    val threshold = parsePriceThreshold(args.stringArg("seuil"))
        ?: return "Dites le prix sous lequel vous prévenir, par exemple « 1,70 » euro le litre."
    val cityArg = args.stringArg("ville").trim()
    val radius = args.intArg("rayon_km", 5).coerceIn(1, 30)
    val place = if (com.jarvis.android.location.isHereRequest(cityArg)) {
        val found = when (val outcome = com.jarvis.android.location.locate(ctx.appContext)) {
            is com.jarvis.android.location.LocationOutcome.Found -> outcome
            com.jarvis.android.location.LocationOutcome.NoPermission ->
                return "Je n'ai pas accès à la position : dites une ville, ou autorisez la position dans Paramètres > Position (météo)."
            else -> return "Position introuvable pour le moment : dites une ville ou un code postal."
        }
        FuelWatch.Place(null, found.fix.latitude, found.fix.longitude, radius, found.place?.trim()?.takeIf { it.isNotEmpty() }?.let { "autour de $it" } ?: "autour de votre position")
    } else {
        val city = normalizedUtilityQuery(cityArg, 80) ?: return "Indiquez une ville ou un code postal."
        FuelWatch.Place(city, null, null, radius, "à $city")
    }
    FuelWatch.on(ctx.appContext, fuel, threshold, place)
    val radiusWords = if (place.city == null) " (à moins de $radius km)" else ""
    return String.format(Locale.FRANCE, "Je vous préviendrai quand le %s passera sous %.3f €/L %s%s, en regardant plusieurs fois par jour.", fuel.label, threshold, place.label, radiusWords)
}

/** What the fuel map shows: the stations, the fuel, the route when there is one, where the user is. */
internal object FuelMap {
    @Volatile var stations: List<Station> = emptyList()
    @Volatile var fuel: Fuel = Fuel.GAZOLE
    @Volatile var line: List<Pair<Double, Double>> = emptyList()
    @Volatile var origin: Pair<Double, Double>? = null
}

private fun showFuelMap(ctx: JarvisContainer, stations: List<Station>, fuel: Fuel, line: List<Pair<Double, Double>>?, origin: Pair<Double, Double>?) {
    if (stations.none { it.latitude != null }) return
    FuelMap.stations = stations; FuelMap.fuel = fuel; FuelMap.line = line.orEmpty(); FuelMap.origin = origin
    ctx.videoPanel.show(com.jarvis.android.video.VideoPanel.Video(title = "Prix ${fuel.label}", sky = com.jarvis.android.video.SkyModes.FUEL))
}

/** A station's distance to a line of points (latitude, longitude), and how far along it (km from its start). */
internal fun alongLine(line: List<Pair<Double, Double>>, lat: Double, lon: Double): Pair<Double, Double> {
    var best = Double.MAX_VALUE
    var at = 0.0
    var run = 0.0
    line.forEachIndexed { i, p ->
        if (i > 0) run += distanceKm(line[i - 1].first, line[i - 1].second, p.first, p.second)
        val d = distanceKm(lat, lon, p.first, p.second)
        if (d < best) { best = d; at = run }
    }
    return best to at
}

/** Points of a line (latitude, longitude) every [stepKm], its ends included. */
internal fun evenPoints(line: List<Pair<Double, Double>>, stepKm: Double): List<Pair<Double, Double>> {
    if (line.isEmpty()) return emptyList()
    val out = arrayListOf(line.first())
    var run = 0.0
    for (i in 1 until line.size) {
        run += distanceKm(line[i - 1].first, line[i - 1].second, line[i].first, line[i].second)
        if (run >= stepKm) { out += line[i]; run = 0.0 }
    }
    if (out.last() != line.last()) out += line.last()
    return out
}

/** The cheapest stations along a road: asked by circles every 20 km or so along it, kept within 3 km of it. */
private suspend fun alongRoute(ctx: JarvisContainer, fuel: Fuel, fromWords: String, toWords: String, now: Instant): String {
    val to = com.jarvis.android.driving.RouteWeather.geocode(ctx, toWords) ?: return "Je ne trouve pas « $toWords »."
    val from = if (fromWords.isNotEmpty() && !com.jarvis.android.location.isHereRequest(fromWords)) com.jarvis.android.driving.RouteWeather.geocode(ctx, fromWords) ?: return "Je ne trouve pas « $fromWords »."
    else (com.jarvis.android.location.locate(ctx.appContext) as? com.jarvis.android.location.LocationOutcome.Found)?.let { Triple(it.fix.latitude, it.fix.longitude, com.jarvis.android.location.positionLabel(it.place)) }
        ?: return "Je n'ai pas votre position : dites d'où vous partez."
    val (coords, km, _) = com.jarvis.android.driving.RouteWeather.route(ctx, from.first, from.second, to.first, to.second) ?: return "Itinéraire indisponible pour le moment."
    val line = coords.map { it.second to it.first }
    // a point every 10 km (60 at most), each asked with a circle that reaches the next one and 3 km beyond the road
    val step = maxOf(10.0, km / 60)
    val samples = evenPoints(line, step)
    val radius = (step / 2 + 3).toInt() + 1
    val circles = samples.joinToString(" or ") { circleFilter(it.first, it.second, radius) }
    val url = fuelUrl(fuel, "($circles)")
    val body = withContext(Dispatchers.IO) {
        try { ctx.http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null } } catch (_: IOException) { null }
    } ?: return "Prix des carburants indisponibles pour le moment."
    val near = rankStations(parseStations(body, fuel), fuel, now, null, null).mapNotNull { s ->
        if (s.latitude == null || s.longitude == null) return@mapNotNull null
        val (off, along) = alongLine(line, s.latitude, s.longitude)
        if (off > 3.0) null else Triple(s, off, along)
    }
    if (near.isEmpty()) return "Aucune station avec du ${fuel.label} à moins de 3 km de la route ${from.third} → ${to.third}."
    showFuelMap(ctx, near.map { it.first }.take(30), fuel, line, from.first to from.second)
    return "${fuel.label} le moins cher sur la route ${from.third} → ${to.third} (${km.toInt()} km), à moins de 3 km de la route :\n" +
        near.take(4).mapIndexed { i, (s, off, along) ->
            "${i + 1}. ${String.format(Locale.FRANCE, "%.3f", s.price)} €/L — ${s.address}, ${s.postcode} ${s.city}, au km ${along.toInt()} (à ${String.format(Locale.FRANCE, "%.1f", off)} km de la route, prix mis à jour ${ageLabel(s.updated, now)})"
        }.joinToString("\n") + "\n(Source : data.economie.gouv.fr ; la carte les montre à la place du visage.)"
}
