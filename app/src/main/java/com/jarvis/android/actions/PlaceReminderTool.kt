package com.jarvis.android.actions

import android.location.Geocoder
import android.os.Build
import com.jarvis.android.JarvisContainer
import com.jarvis.android.places.DEFAULT_RADIUS_M
import com.jarvis.android.places.Geofences
import com.jarvis.android.places.PlaceReminder
import com.jarvis.android.places.PlaceTrigger
import com.jarvis.android.places.describeReminder
import com.jarvis.android.places.findPlace
import com.jarvis.android.places.isHere
import com.jarvis.android.places.matchReminders
import com.jarvis.android.places.parseTrigger
import com.jarvis.android.places.placeKey
import com.jarvis.android.places.placePermissionProblem
import com.jarvis.android.weather.LocationOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.coroutines.resume

/** Reminders that fire on arriving at or leaving a place, and the named places they refer to. */
object PlaceReminderTool : Tool {
    override val name = "place_reminder"
    override val description =
        "Rappels selon le lieu. create : « rappelle-moi d'acheter du pain quand je passe près de la boulangerie », « quand j'arrive à la maison, " +
            "allume la lumière du salon » (text = ce qu'il faut rappeler, place = le lieu, trigger = arrive ou leave, task = une action que " +
            "Jarvis fera tout seul à ce moment, facultatif, repeat = 'true' pour chaque fois). place peut être un lieu enregistré (« maison », " +
            "« bureau »), « ici », ou une adresse ou un commerce (cherché autour de la position actuelle). save_place : « retiens que la maison c'est ici » " +
            "(name, place = ici ou une adresse). Aussi : list, delete (query = numéro ou mots du rappel), places, forget_place."
    override val parameters = objectSchema {
        string("action", "'create' (défaut), 'save_place', 'list', 'delete', 'places' ou 'forget_place'.")
        string("text", "Pour create : ce qu'il faut rappeler.")
        string("place", "Pour create/save_place : lieu enregistré, « ici », ou adresse / nom de commerce.")
        string("name", "Pour save_place/forget_place : le nom du lieu (« maison », « bureau »).")
        string("trigger", "Pour create : 'arrive' (défaut) ou 'leave'.")
        string("task", "Pour create : action que Jarvis fera tout seul au déclenchement (« allume la lumière du salon »), facultatif.")
        string("repeat", "Pour create : 'true' pour rappeler à chaque passage, sinon une seule fois.")
        integer("radius_m", "Pour create : rayon en mètres, 100 à 2000 (défaut $DEFAULT_RADIUS_M).")
        string("query", "Pour delete : numéro du rappel ou mots de son texte.")
    }

    private data class Resolved(val latitude: Double, val longitude: Double, val label: String)

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val store = ctx.placeStore
        when (args.stringArg("action").trim().lowercase().ifEmpty { "create" }) {
            "save_place" -> {
                val name = args.stringArg("name").trim().ifEmpty { return@withContext "Indiquez le nom du lieu (« maison », « bureau »…)." }
                val where = args.stringArg("place").trim().ifEmpty { "ici" }
                val resolved = resolve(ctx, where, near = null).getOrElse { return@withContext it.message ?: "Lieu introuvable." }
                val place = store.savePlace(name, resolved.latitude, resolved.longitude, resolved.label)
                    ?: return@withContext "Impossible d'enregistrer ce lieu (30 au plus)."
                "Lieu « ${place.name} » enregistré : ${resolved.label}."
            }
            "create" -> {
                placePermissionProblem(ctx.appContext)?.let { return@withContext it }
                val text = args.stringArg("text").trim().ifEmpty { return@withContext "Indiquez ce qu'il faut rappeler." }
                val where = args.stringArg("place").trim().ifEmpty { return@withContext "Indiquez le lieu." }
                val saved = findPlace(store.load().places, where)
                val resolved = if (saved != null) Resolved(saved.latitude, saved.longitude, saved.label.ifEmpty { saved.name })
                else resolve(ctx, where, near = true).getOrElse { return@withContext it.message ?: "Lieu introuvable." }
                val trigger = parseTrigger(args.stringArg("trigger"))
                val reminder = store.add(PlaceReminder(
                    id = 0, text = text, placeName = saved?.name ?: where,
                    latitude = resolved.latitude, longitude = resolved.longitude,
                    radiusM = args.intArg("radius_m", DEFAULT_RADIUS_M), trigger = trigger,
                    task = args.stringArg("task"), repeat = args.stringArg("repeat").trim().lowercase() in setOf("true", "oui", "1", "yes"),
                )) ?: return@withContext "Trop de rappels de lieu (90 au plus) : supprimez-en d'abord."
                Geofences.registerAll(ctx.appContext)
                val `when` = if (trigger == PlaceTrigger.ARRIVE) "en arrivant" else "en partant"
                "Rappel ${reminder.id} créé $`when` ${reminder.placeName} (${resolved.label}, rayon ${reminder.radiusM} m) : « ${reminder.text} »" +
                    (if (reminder.task.isNotBlank()) ", et je ferai : « ${reminder.task} »" else "") +
                    (if (reminder.repeat) ", à chaque passage." else ", une seule fois.")
            }
            "list" -> store.load().reminders.let { if (it.isEmpty()) "Aucun rappel de lieu." else it.joinToString("\n") { r -> describeReminder(r) } }
            "delete" -> {
                val matches = matchReminders(store.load().reminders, args.stringArg("query"))
                when {
                    matches.isEmpty() -> "Aucun rappel de lieu ne correspond."
                    matches.size > 1 && args.stringArg("query").trim().toIntOrNull() == null ->
                        "Plusieurs rappels correspondent : " + matches.joinToString(" ; ") { describeReminder(it) } + ". Précisez le numéro."
                    else -> {
                        store.remove(matches.map { it.id })
                        Geofences.registerAll(ctx.appContext)
                        "Supprimé : ${describeReminder(matches.first())}."
                    }
                }
            }
            "places" -> store.load().places.let { p -> if (p.isEmpty()) "Aucun lieu enregistré." else p.joinToString("\n") { "${it.name} : ${it.label}" } }
            "forget_place" -> if (store.forgetPlace(args.stringArg("name"))) "Lieu oublié." else "Aucun lieu de ce nom."
            else -> "Action inconnue : create, save_place, list, delete, places ou forget_place."
        }
    }

    /**
     * "ici" → the phone's position; anything else → Android's geocoder, looked for within ~20 km of the phone first
     * when [near] (so "la boulangerie" means one nearby, not the first in France).
     */
    private suspend fun resolve(ctx: JarvisContainer, where: String, near: Boolean?): Result<Resolved> {
        val here = com.jarvis.android.weather.locate(ctx.appContext)
        if (isHere(where) || placeKey(where).isEmpty()) {
            return when (here) {
                is LocationOutcome.Found -> Result.success(Resolved(here.fix.latitude, here.fix.longitude, here.place ?: "votre position actuelle"))
                LocationOutcome.NoPermission -> Result.failure(Exception("Je n'ai pas accès à la position. L'utilisateur peut l'autoriser dans Réglages de Jarvis > Rappels selon le lieu."))
                LocationOutcome.ServicesOff -> Result.failure(Exception("La localisation du téléphone est désactivée."))
                LocationOutcome.Unavailable -> Result.failure(Exception("Position introuvable pour le moment. Réessayez avec Jarvis ouvert."))
            }
        }
        if (!Geocoder.isPresent()) return Result.failure(Exception("Recherche d'adresse indisponible sur ce téléphone : enregistrez plutôt le lieu en y étant (« retiens que … c'est ici »)."))
        val geocoder = Geocoder(ctx.appContext, Locale.FRANCE)
        val box = (here as? LocationOutcome.Found)?.takeIf { near == true }?.fix
        val address = geocode(geocoder, where, box?.let { doubleArrayOf(it.latitude - 0.18, it.longitude - 0.25, it.latitude + 0.18, it.longitude + 0.25) })
            ?: geocode(geocoder, where, null)
            ?: return Result.failure(Exception("Je ne trouve pas « $where ». Donnez une adresse plus précise, ou enregistrez le lieu en y étant."))
        val label = (0..address.maxAddressLineIndex).mapNotNull { address.getAddressLine(it) }.joinToString(", ").ifEmpty { where }
        return Result.success(Resolved(address.latitude, address.longitude, label))
    }

    @Suppress("DEPRECATION")
    private suspend fun geocode(geocoder: Geocoder, query: String, box: DoubleArray?): android.location.Address? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<android.location.Address>) { cont.resume(addresses.firstOrNull()) }
                        override fun onError(errorMessage: String?) { cont.resume(null) }
                    }
                    if (box != null) geocoder.getFromLocationName(query, 1, box[0], box[1], box[2], box[3], listener)
                    else geocoder.getFromLocationName(query, 1, listener)
                }
            }
        } else {
            (if (box != null) geocoder.getFromLocationName(query, 1, box[0], box[1], box[2], box[3]) else geocoder.getFromLocationName(query, 1))?.firstOrNull()
        }
    } catch (_: Exception) {
        null
    }
}
