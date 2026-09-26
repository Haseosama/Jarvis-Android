package com.jarvis.android.actions

import android.content.Intent
import com.jarvis.android.JarvisContainer
import com.jarvis.android.photos.Photo
import com.jarvis.android.photos.PhotoIndexWorker
import com.jarvis.android.photos.labelMissing
import com.jarvis.android.photos.labelTerms
import com.jarvis.android.photos.matchesLabels
import com.jarvis.android.photos.photoLabelIndex
import com.jarvis.android.photos.describePhotos
import com.jarvis.android.photos.hasPhotoLocationPermission
import com.jarvis.android.photos.hasPhotoPermission
import com.jarvis.android.photos.photoPosition
import com.jarvis.android.photos.photoRange
import com.jarvis.android.photos.queryPhotos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.LocalDate
import java.time.ZoneId

/** Finds the phone's photos by date, album and place, says how many, and opens one (see photos/PhotoSearch.kt). */
object PhotoSearchTool : Tool {
    private const val PLACE_SCAN_LIMIT = 400
    private const val PLACE_RADIUS_KM = 25.0

    override val name = "photos"
    override val description =
        "Chercher dans les photos du téléphone : « mes photos d'août », « les photos de la semaine dernière », « mes photos à Brest », " +
            "« les photos WhatsApp d'hier », « mes photos de chien », « les photos de plage de cet été ». Donnez les jours en dates (from/to, AAAA-MM-JJ) calculées depuis la date du jour ; un lieu seulement " +
            "si l'utilisateur en nomme un. Répond combien il y en a et sur quels jours, et ouvre la plus récente (ou la plus ancienne) dans la galerie. " +
            "Pour ce qu'il y a SUR les photos, donnez subject (les mots de l'utilisateur) et labels (les étiquettes anglaises du modèle d'images " +
            "du téléphone, ex. 'dog' pour chien, 'beach' pour plage, 'food' pour repas, 'cat', 'flower', 'car', 'sky', 'snow', 'cake', 'baby') ; sans dates, " +
            "la recherche par sujet couvre l'année écoulée. Aucune photo n'est envoyée : l'analyse se fait sur le téléphone."
    override val parameters = objectSchema {
        string("from", "Premier jour, AAAA-MM-JJ (seul : ce jour-là ; sans from ni to : les 7 derniers jours).")
        string("to", "Dernier jour inclus, AAAA-MM-JJ.")
        string("place", "Ville ou lieu où les photos ont été prises (facultatif, à 25 km près).")
        string("album", "Nom d'album contenu, ex. 'WhatsApp', 'Screenshots', 'Camera' (facultatif).")
        string("subject", "Ce qu'il y a sur les photos, avec les mots de l'utilisateur, ex. 'chien' (facultatif).")
        string("labels", "Le même sujet en étiquettes anglaises séparées par des virgules, ex. 'dog' ou 'beach, sea' (avec subject).")
        string("open", "'latest' (défaut), 'first' ou 'none'.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val context = ctx.appContext
        if (!hasPhotoPermission(context)) {
            return@withContext "Je n'ai pas accès aux photos : autorisez-le dans les réglages de Jarvis (carte Photos)."
        }
        val zone = ZoneId.systemDefault()
        val subject = args.stringArg("subject").trim()
        val terms = labelTerms(args.stringArg("labels").ifBlank { subject })
        val today = LocalDate.now(zone)
        val noDates = args.stringArg("from").isBlank() && args.stringArg("to").isBlank()
        // Looking for what is on the photos with no dates: the whole past year (a start alone would mean that one day).
        val yearBack = terms.isNotEmpty() && noDates
        val (start, end) = photoRange(
            if (yearBack) today.minusDays(364).toString() else args.stringArg("from"),
            if (yearBack) today.toString() else args.stringArg("to"),
            today, zone,
        ) ?: return@withContext "Dates illisibles : donnez-les sous la forme AAAA-MM-JJ."
        var photos = try {
            queryPhotos(context, start, end, args.stringArg("album"))
        } catch (_: SecurityException) {
            return@withContext "Android a refusé l'accès aux photos."
        }
        var where = ""
        var note = ""
        val placeName = args.stringArg("place").trim()
        if (placeName.isNotEmpty() && photos.isNotEmpty()) {
            if (!hasPhotoLocationPermission(context)) {
                return@withContext "Pour trier par lieu, Jarvis doit pouvoir lire la position des photos : autorisez-le dans les réglages de Jarvis (carte Photos)."
            }
            val place = try {
                val geo = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
                    .addQueryParameter("count", "10").addQueryParameter("language", "fr").addQueryParameter("name", placeName.take(200)).build()
                val body = ctx.http.newCall(Request.Builder().url(geo).build()).execute().use { it.body?.string().orEmpty() }
                parseWeatherPlace(body, phoneCountry(ctx))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext "Impossible de situer « $placeName » pour le moment (connexion ?)."
            } ?: return@withContext "Je ne trouve pas le lieu « $placeName »."
            val total = photos.size
            val scanned = photos.take(PLACE_SCAN_LIMIT)
            var noGps = 0
            photos = scanned.filter { p ->
                val pos = photoPosition(context, p)
                if (pos == null) noGps++
                pos != null && distanceKm(pos.first, pos.second, place.latitude, place.longitude) <= PLACE_RADIUS_KM
            }
            where = place.label
            if (noGps > 0) note += " ${if (noGps == 1) "1 photo de la période n'a" else "$noGps photos de la période n'ont"} pas de position enregistrée."
            if (total > scanned.size) note += " Seules les $PLACE_SCAN_LIMIT plus récentes de la période ont été vérifiées : précisez les dates pour aller plus loin."
        }
        if (terms.isNotEmpty() && photos.isNotEmpty()) {
            val index = photoLabelIndex(context)
            // Up to 20 seconds of looking at photos not seen yet, newest first; the rest is done while the phone charges.
            val left = labelMissing(context, index, photos, System.currentTimeMillis() + 20_000L)
            photos = photos.filter { p -> index.get(p.id)?.let { matchesLabels(it, terms) } == true }
            if (left > 0) {
                PhotoIndexWorker.schedule(context)
                note += " ${left} photo${if (left > 1) "s" else ""} de la période n'${if (left > 1) "ont" else "a"} pas encore été analysée${if (left > 1) "s" else ""} : " +
                    "Jarvis s'en occupe pendant la prochaine charge, redemandez ensuite."
            }
        }
        val summary = describePhotos(photos, zone, where, subject.ifEmpty { terms.joinToString(", ") }) + note
        val open = args.stringArg("open").trim().lowercase()
        val target: Photo? = when (open) {
            "none", "non" -> null
            "first", "oldest" -> photos.lastOrNull()
            else -> photos.firstOrNull()
        }
        if (target == null) return@withContext summary
        val opened = try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(target.uri, "image/*")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            true
        } catch (_: Exception) {
            false
        }
        val day = whenLabel(target.takenAt, LocalDate.now(zone), zone)
        val which = if (open == "first" || open == "oldest") "la plus ancienne" else "la plus récente"
        summary + if (opened) " J'ouvre $which, prise $day." else " Aucune galerie ne peut l'ouvrir."
    }
}
