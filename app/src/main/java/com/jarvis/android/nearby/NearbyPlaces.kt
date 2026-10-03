package com.jarvis.android.nearby

import com.jarvis.android.actions.distanceKm
import com.jarvis.android.offline.normalize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * "Autour de moi": the nearest pharmacy, bakery, cash machine, toilets or charging point, from OpenStreetMap through the
 * Overpass API (free, no key), with whether it is open now by its opening_hours (see OpeningHours.kt).
 */

/** What can be looked for: its words, its OpenStreetMap tags, how far to look first and at most (metres). */
internal enum class PlaceKind(
    val singular: String,
    val plural: String,
    val selectors: List<String>,
    val radiusM: Int,
    val maxRadiusM: Int,
    /** "ouverte", "ouvert", "ouvertes": agreeing with one of them */
    val openWord: String,
    val closedWord: String,
    /** "ouvertes", "ouverts": agreeing with several */
    val openPlural: String,
    /** "Aucune pharmacie", "Aucun distributeur de billets" */
    val none: String,
    /** "La plus proche ouverte", "Le plus proche ouvert" */
    val nearestOpen: String,
) {
    PHARMACY("Pharmacie", "Pharmacies", listOf("[\"amenity\"=\"pharmacy\"]"), 1500, 5000, "ouverte", "fermée", "ouvertes", "Aucune pharmacie", "La plus proche ouverte"),
    BAKERY("Boulangerie", "Boulangeries", listOf("[\"shop\"=\"bakery\"]"), 1000, 3000, "ouverte", "fermée", "ouvertes", "Aucune boulangerie", "La plus proche ouverte"),
    ATM(
        "Distributeur", "Distributeurs de billets", listOf("[\"amenity\"=\"atm\"]", "[\"amenity\"=\"bank\"][\"atm\"=\"yes\"]"), 1000, 3000,
        "ouvert", "fermé", "ouverts", "Aucun distributeur de billets", "Le plus proche ouvert",
    ),
    TOILETS("Toilettes", "Toilettes publiques", listOf("[\"amenity\"=\"toilets\"]"), 800, 2500, "ouvertes", "fermées", "ouvertes", "Aucunes toilettes publiques", "Les plus proches ouvertes"),
    CHARGER(
        "Borne de recharge", "Bornes de recharge", listOf("[\"amenity\"=\"charging_station\"]"), 3000, 10000,
        "ouverte", "fermée", "ouvertes", "Aucune borne de recharge", "La plus proche ouverte",
    ),
}

/** "la pharmacie", "du pain", "un DAB", "des WC", "une borne pour la voiture"… → what to look for, or null. */
internal fun parsePlaceKind(text: String): PlaceKind? {
    val n = normalize(text)
    val words = n.split(' ').toSet()
    return when {
        "pharma" in n || "medicament" in n -> PlaceKind.PHARMACY
        "boulang" in n || "pain" in words || "baguette" in n || "viennoiser" in n || "croissant" in n -> PlaceKind.BAKERY
        "recharg" in n || "borne" in n || "electrique" in n || "charging" in n || "superchargeur" in n || "irve" in words -> PlaceKind.CHARGER
        "distrib" in n || "dab" in words || "atm" in words || "billet" in n || "liquide" in n || "retirer" in n || "argent" in words || "banque" in n || "cash" in words -> PlaceKind.ATM
        "toilet" in n || "wc" in words || "sanitaire" in n || "pipi" in n || "lavabo" in n -> PlaceKind.TOILETS
        else -> null
    }
}

/** "oui", "ouvert", "maintenant", "true" → only the places open now. */
internal fun wantsOpenOnly(text: String): Boolean = normalize(text).let { it.isNotEmpty() && (it.startsWith("oui") || "ouvert" in it || "maintenant" in it || it == "true" || it == "1") }

/** Streets drawn under the places: the ones people walk and drive on, not the paths and car parks. */
private const val ROADS = "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|pedestrian)$"

/** The streets are asked for no further than this (metres), to keep the answer small. */
internal const val ROADS_MAX_M = 1200

/** The Overpass query: the places of that kind around a point (their centre for buildings), then the streets around it. */
internal fun overpassQuery(kind: PlaceKind, lat: Double, lon: Double, radiusM: Int, roadsRadiusM: Int): String {
    val around = "(around:$radiusM,${"%.6f".format(Locale.ROOT, lat)},${"%.6f".format(Locale.ROOT, lon)})"
    val places = kind.selectors.joinToString("") { "nwr$it$around;" }
    val roads = if (roadsRadiusM <= 0) "" else
        "way[\"highway\"~\"$ROADS\"](around:$roadsRadiusM,${"%.6f".format(Locale.ROOT, lat)},${"%.6f".format(Locale.ROOT, lon)});out skel geom qt;"
    return "[out:json][timeout:20];($places);out center tags;$roads"
}

internal data class Place(val id: String, val lat: Double, val lon: Double, val tags: Map<String, String>) {
    val name: String? get() = tags["name"] ?: tags["brand"] ?: tags["operator"]
    val hours: String? get() = tags["opening_hours"]
    val address: String? get() = listOfNotNull(tags["addr:housenumber"], tags["addr:street"]).joinToString(" ").takeIf { it.isNotBlank() }
}

/** The places found and the streets (each a line of latitude, longitude). */
internal data class OverpassResult(val places: List<Place>, val roads: List<List<Pair<Double, Double>>>)

internal fun parseOverpass(body: String): OverpassResult {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Invalid Overpass payload")
    val elements = root["elements"] as? JsonArray ?: return OverpassResult(emptyList(), emptyList())
    val places = ArrayList<Place>()
    val roads = ArrayList<List<Pair<Double, Double>>>()
    elements.forEach { el ->
        val o = el as? JsonObject ?: return@forEach
        fun num(obj: JsonObject?, k: String) = (obj?.get(k) as? JsonPrimitive)?.doubleOrNull
        val geometry = o["geometry"] as? JsonArray
        val tags = (o["tags"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap()
        if (tags == null && geometry != null) {
            roads += geometry.mapNotNull { p -> val po = p as? JsonObject; (num(po, "lat") ?: return@mapNotNull null) to (num(po, "lon") ?: return@mapNotNull null) }
            return@forEach
        }
        if (tags == null) return@forEach
        val centre = o["center"] as? JsonObject
        val lat = num(o, "lat") ?: num(centre, "lat") ?: return@forEach
        val lon = num(o, "lon") ?: num(centre, "lon") ?: return@forEach
        val type = (o["type"] as? JsonPrimitive)?.contentOrNull ?: "node"
        val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: 0L
        places += Place("$type/$id", lat, lon, tags)
    }
    return OverpassResult(places, roads)
}

/** A place found, how far and which way from where the user is, and whether it is open now (null: hours not known). */
internal data class NearbyHit(val place: Place, val distanceM: Double, val bearing: Double, val state: OpenState?)

/** Initial bearing from one point to another, in degrees from north. */
internal fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val f1 = Math.toRadians(lat1)
    val f2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    val y = sin(dl) * cos(f2)
    val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/**
 * The places worth naming, nearest first: not the private ones, and only one of a bank's own cash machine and the
 * bank tagged as having one when they stand together; [onlyOpen] keeps those whose hours say they are open now.
 */
internal fun rankPlaces(places: List<Place>, lat: Double, lon: Double, now: LocalDateTime, onlyOpen: Boolean): List<NearbyHit> {
    val hits = places
        .filter { it.tags["access"] !in setOf("private", "no") }
        .distinctBy { it.id }
        .map { NearbyHit(it, distanceKm(lat, lon, it.lat, it.lon) * 1000, bearingDeg(lat, lon, it.lat, it.lon), openState(it.hours, now)) }
        .sortedBy { it.distanceM }
    val kept = ArrayList<NearbyHit>()
    hits.forEach { h -> if (kept.none { distanceKm(it.place.lat, it.place.lon, h.place.lat, h.place.lon) < 0.03 && it.place.name == h.place.name }) kept += h }
    return if (onlyOpen) kept.filter { it.state?.open == true } else kept
}

/** "à 350 m", "à 1,2 km". */
internal fun distanceWords(m: Double): String =
    if (m < 995) "à ${((m / 10).roundToInt() * 10).coerceAtLeast(10)} m" else "à ${String.format(Locale.FRANCE, "%.1f", m / 1000).removeSuffix(",0")} km"

/** "ouverte, ferme à 19 h 30", "fermée, ouvre demain à 8 h", "ouvert 24 h/24", "horaires non renseignés". */
internal fun openWords(kind: PlaceKind, hit: NearbyHit, now: LocalDateTime): String {
    val s = hit.state ?: return if (hit.place.hours == null) "horaires non renseignés" else "horaires : ${hit.place.hours}"
    return when {
        s.open && s.nextChange == null -> "${kind.openWord} 24 h/24"
        s.open -> "${kind.openWord}, ferme ${whenWords(s.nextChange!!, now)}"
        s.nextChange == null -> "${kind.closedWord} toute la semaine d’après ses horaires"
        else -> "${kind.closedWord}, ouvre ${whenWords(s.nextChange, now)}"
    }
}

/** What else is worth saying about one: whether toilets are free, a charger's plugs and power. */
internal fun detailWords(kind: PlaceKind, tags: Map<String, String>): List<String> = when (kind) {
    PlaceKind.TOILETS -> listOfNotNull(
        when (tags["fee"]) { "yes" -> "payantes"; "no" -> "gratuites"; else -> null },
        "accessibles en fauteuil".takeIf { tags["wheelchair"] == "yes" },
        "table à langer".takeIf { tags["changing_table"] == "yes" },
        "réservées aux clients".takeIf { tags["access"] == "customers" },
    )
    PlaceKind.CHARGER -> {
        val plugs = listOf("type2_combo" to "CCS", "chademo" to "CHAdeMO", "type2" to "Type 2", "type2_cable" to "Type 2 câble", "typee" to "prise domestique")
            .mapNotNull { (k, w) -> tags["socket:$k"]?.let { n -> if (n == "yes" || n == "1") w else "$n × $w" } }
        val kw = tags.filterKeys { it.startsWith("socket:") && it.endsWith(":output") }.values
            .mapNotNull { Regex("""(\d+(?:[.,]\d+)?)\s*kw""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() }.maxOrNull()
        listOfNotNull(
            plugs.takeIf { it.isNotEmpty() }?.joinToString(", "),
            kw?.let { "jusqu’à ${if (it % 1.0 == 0.0) it.toInt().toString() else String.format(Locale.FRANCE, "%.1f", it)} kW" },
            tags["capacity"]?.toIntOrNull()?.let { "$it points de charge" },
            when (tags["fee"]) { "no" -> "gratuite"; else -> null },
            "réservée aux clients".takeIf { tags["access"] == "customers" },
        )
    }
    PlaceKind.ATM -> listOfNotNull("dans l’agence".takeIf { tags["amenity"] == "bank" })
    else -> emptyList()
}

internal const val NEARBY_LISTED = 4

/** The spoken answer: the nearest few with their distance, way, address and hours, then the nearest open one if it was not first. */
internal fun formatNearby(kind: PlaceKind, hits: List<NearbyHit>, where: String, radiusM: Int, now: LocalDateTime, onlyOpen: Boolean, direction: (Double) -> String): String {
    if (hits.isEmpty()) {
        val within = distanceWords(radiusM.toDouble()).removePrefix("à ")
        val base = "${kind.none}${if (onlyOpen) " ${kind.openWord} maintenant d’après les horaires connus" else ""} à moins de $within de $where dans OpenStreetMap."
        return if (kind == PlaceKind.PHARMACY) "$base Pour la pharmacie de garde, appelez le 3237 ou voyez 3237.fr." else base
    }
    val lines = hits.take(NEARBY_LISTED).mapIndexed { i, h ->
        val name = h.place.name ?: kind.singular
        val address = h.place.address?.let { ", $it" } ?: ""
        val details = detailWords(kind, h.place.tags).takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = " (", postfix = ")") ?: ""
        "${i + 1}. $name$address — ${distanceWords(h.distanceM)} ${direction(h.bearing)}$details ; ${openWords(kind, h, now)}."
    }
    val firstOpen = hits.indexOfFirst { it.state?.open == true }
    val openNote = when {
        onlyOpen -> ""
        firstOpen == 0 -> ""
        firstOpen in 1 until NEARBY_LISTED -> "\n${kind.nearestOpen} d’après les horaires : la n° ${firstOpen + 1}."
        firstOpen >= NEARBY_LISTED -> hits[firstOpen].let { "\n${kind.nearestOpen} d’après les horaires : ${it.place.name ?: kind.singular}${it.place.address?.let { a -> ", $a" } ?: ""}, ${distanceWords(it.distanceM)}." }
        else -> ""
    }
    val guard = if (kind == PlaceKind.PHARMACY && hits.none { it.state?.open == true }) "\nAucune n’est ouverte d’après ses horaires : pour la pharmacie de garde, appelez le 3237 ou voyez 3237.fr." else ""
    return "${kind.plural}${if (onlyOpen) " ${kind.openPlural} maintenant" else ""} les plus proches de $where, ${hits.size} à moins de ${distanceWords(radiusM.toDouble()).removePrefix("à ")} :\n" +
        lines.joinToString("\n") + openNote + guard +
        "\n(Source : OpenStreetMap ; distances à vol d’oiseau ; horaires saisis par les contributeurs, à confirmer. La carte les montre à la place du visage.)"
}
