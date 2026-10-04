package com.jarvis.android.nearby

import com.jarvis.android.JarvisContainer
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.intArg
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException
import java.time.LocalDateTime

/** Overpass servers, the main one first; the second is asked when the first is busy or down. */
private val OVERPASS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")

/** What the "around me" map shows: what was looked for, the places (numbered as said), the streets, where the user is. */
internal object NearbyMap {
    @Volatile var kind: PlaceKind = PlaceKind.PHARMACY
    @Volatile var hits: List<NearbyHit> = emptyList()
    @Volatile var roads: List<List<Pair<Double, Double>>> = emptyList()
    @Volatile var origin: Pair<Double, Double>? = null
}

object NearbyTool : Tool {
    override val name = "autour_de_moi"
    override val description =
        "Le plus proche autour de l'utilisateur, d'après OpenStreetMap : pharmacie, boulangerie, distributeur de billets, toilettes publiques " +
            "ou borne de recharge pour voiture électrique, avec la distance, la direction, l'adresse, les horaires et s'il est ouvert maintenant. " +
            "Les lieux s'affichent sur la carte à la place du visage (vert : ouvert, rouge : fermé, gris : horaires inconnus)."
    override val parameters = objectSchema(required = listOf("quoi")) {
        string("quoi", "Ce qui est cherché, tel que dit : pharmacie, boulangerie, distributeur / DAB, toilettes, borne de recharge.")
        string("ville", "Un lieu (ville, adresse, quartier) ; vide ou « ici » pour autour de la position du téléphone.")
        integer("rayon_m", "Rayon de recherche en mètres, 200 à 10000 (par défaut selon ce qui est cherché, élargi une fois si rien n'est trouvé).")
        string("ouvert", "« oui » pour ne garder que ce qui est ouvert maintenant d'après les horaires (« une pharmacie ouverte »).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val kind = parsePlaceKind(args.stringArg("quoi"))
            ?: return@withContext "Je sais chercher une pharmacie, une boulangerie, un distributeur de billets, des toilettes publiques ou une borne de recharge."
        val placeArg = args.stringArg("ville").trim()
        val (lat, lon, where) = if (com.jarvis.android.location.isHereRequest(placeArg)) {
            when (val outcome = com.jarvis.android.location.locate(ctx.appContext)) {
                is com.jarvis.android.location.LocationOutcome.Found ->
                    Triple(outcome.fix.latitude, outcome.fix.longitude, com.jarvis.android.location.positionLabel(outcome.place))
                com.jarvis.android.location.LocationOutcome.NoPermission ->
                    return@withContext "Je n'ai pas accès à la position : dites un lieu, ou autorisez la position dans Paramètres > Position (météo)."
                else -> return@withContext "Position introuvable pour le moment : dites un lieu."
            }
        } else {
            com.jarvis.android.driving.RouteWeather.geocode(ctx, placeArg) ?: return@withContext "Je ne trouve pas « $placeArg »."
        }
        val onlyOpen = wantsOpenOnly(args.stringArg("ouvert"))
        val asked = args.intArg("rayon_m", 0).takeIf { it > 0 }?.coerceIn(200, 10_000)
        val now = LocalDateTime.now()
        try {
            var radius = asked ?: kind.radiusM
            var found = search(ctx, kind, lat, lon, radius) ?: return@withContext "OpenStreetMap ne répond pas pour le moment (serveur Overpass saturé ou injoignable) : réessayez dans un instant."
            var hits = rankPlaces(found.places, lat, lon, now, onlyOpen)
            // nothing near: look once as far as is still worth it
            if (hits.isEmpty() && asked == null) {
                radius = kind.maxRadiusM
                search(ctx, kind, lat, lon, radius)?.let { found = it; hits = rankPlaces(it.places, lat, lon, now, onlyOpen) }
            }
            if (hits.isNotEmpty()) {
                NearbyMap.kind = kind; NearbyMap.hits = hits.take(20); NearbyMap.roads = found.roads; NearbyMap.origin = lat to lon
                ctx.videoPanel.show(com.jarvis.android.video.VideoPanel.Video(title = kind.plural, sky = com.jarvis.android.video.SkyModes.NEARBY))
            }
            formatNearby(kind, hits, where, radius, now, onlyOpen) { com.jarvis.android.space.towardDirection(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "OpenStreetMap indisponible : réponse du service inexploitable."
        }
    }

    /** The places and streets around a point, from the first Overpass server that answers; null when none does. */
    private fun search(ctx: JarvisContainer, kind: PlaceKind, lat: Double, lon: Double, radiusM: Int): OverpassResult? {
        val query = overpassQuery(kind, lat, lon, radiusM, minOf(radiusM, ROADS_MAX_M))
        for (server in OVERPASS) {
            val body = try {
                val request = Request.Builder().url(server).header("User-Agent", "Jarvis-Android").post(FormBody.Builder().add("data", query).build()).build()
                ctx.http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
            } catch (_: IOException) {
                null
            } ?: continue
            return parseOverpass(body)
        }
        return null
    }
}
