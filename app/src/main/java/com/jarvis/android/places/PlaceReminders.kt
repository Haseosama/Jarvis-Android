package com.jarvis.android.places

import com.jarvis.android.offline.normalize
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/*
 * Location reminders: "rappelle-moi d'acheter du pain quand je passe près de la boulangerie", "quand j'arrive à la maison,
 * allume la lumière du salon", "quand je quitte le bureau, rappelle-moi d'appeler Paul". A reminder is a circle around a
 * place (Android's geofencing watches it, see Geofences.kt) with a text to say and, optionally, a task Jarvis carries out
 * by itself when it fires (the same way routines do). Named places ("la maison", "le bureau") are saved once, from the
 * phone's position or an address.
 *
 * All pure but the file I/O, so the matching rules are unit-tested.
 */

internal const val MAX_PLACE_REMINDERS = 90 // Android allows 100 geofences per app
internal const val MAX_PLACES = 30
internal const val DEFAULT_RADIUS_M = 150   // below ~100 m Android's geofencing gets unreliable
internal const val MIN_RADIUS_M = 100
internal const val MAX_RADIUS_M = 2_000
/** A repeating reminder does not fire again within this long (driving past the bakery twice in ten minutes). */
internal const val REPEAT_COOLDOWN_MS = 30 * 60_000L

@Serializable
internal data class SavedPlace(val name: String, val latitude: Double, val longitude: Double, val label: String = "")

internal enum class PlaceTrigger { ARRIVE, LEAVE }

@Serializable
internal data class PlaceReminder(
    val id: Int,
    val text: String,
    val placeName: String,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Int = DEFAULT_RADIUS_M,
    val trigger: PlaceTrigger = PlaceTrigger.ARRIVE,
    val task: String = "",       // something Jarvis does by itself when it fires ("allume la lumière du salon"), or empty
    val repeat: Boolean = false, // every time, or once then deleted
    val lastFiredAt: Long = 0L,
)

@Serializable
internal data class PlaceData(
    val places: List<SavedPlace> = emptyList(),
    val reminders: List<PlaceReminder> = emptyList(),
    val nextId: Int = 1,
)

/** "en partant", "quand je quitte", "en sortant" mean leaving; anything else is arriving. */
internal fun parseTrigger(text: String?): PlaceTrigger {
    val n = normalize(text.orEmpty())
    return if (listOf("part", "quitt", "sort", "leave", "exit", "depart").any { it in n }) PlaceTrigger.LEAVE else PlaceTrigger.ARRIVE
}

/** "la maison", "à la maison", "chez moi" → "maison"; a place name as it is stored and matched. */
internal fun placeKey(name: String): String {
    var n = normalize(name)
    for (p in listOf("a la ", "au ", "aux ", "a l ", "chez ", "la ", "le ", "les ", "l ", "mon ", "ma ", "mes ")) n = n.removePrefix(p)
    return when (n) {
        "moi", "nous", "la maison", "domicile", "home" -> "maison"
        "boulot", "travail", "taf" -> "bureau"
        else -> n
    }.trim()
}

/** True for "ici", "là où je suis"…: the place is the phone's current position. */
internal fun isHere(name: String): Boolean = normalize(name) in setOf("ici", "la ou je suis", "ou je suis", "ma position", "ici meme", "la")

internal fun findPlace(places: List<SavedPlace>, name: String): SavedPlace? {
    val key = placeKey(name)
    if (key.isEmpty()) return null
    return places.firstOrNull { placeKey(it.name) == key }
}

internal fun describeReminder(r: PlaceReminder): String {
    val `when` = if (r.trigger == PlaceTrigger.ARRIVE) "en arrivant" else "en partant"
    val where = r.placeName
    val task = if (r.task.isNotBlank()) " (et : ${r.task})" else ""
    val repeat = if (r.repeat) ", à chaque fois" else ", une fois"
    return "#${r.id} — $`when` $where : ${r.text}$task$repeat"
}

/** Whether [r] should fire now: a one-off always does, a repeating one not twice within the cooldown. */
internal fun shouldFire(r: PlaceReminder, now: Long): Boolean = !r.repeat || now - r.lastFiredAt >= REPEAT_COOLDOWN_MS

/** The reminders a spoken query designates for deletion: "#3" / "3" by number, otherwise those whose text or place contains it. */
internal fun matchReminders(reminders: List<PlaceReminder>, query: String): List<PlaceReminder> {
    val q = query.trim().removePrefix("#")
    q.toIntOrNull()?.let { id -> return reminders.filter { it.id == id } }
    val n = normalize(q)
    if (n.isEmpty()) return emptyList()
    return reminders.filter { normalize(it.text).contains(n) || placeKey(it.placeName) == placeKey(q) }
}

/** The places and reminders in one private file. Synchronised, not suspending: the geofence receiver uses it too. */
internal class PlaceStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): PlaceData = try {
        if (file.exists()) json.decodeFromString<PlaceData>(file.readText()) else PlaceData()
    } catch (_: Exception) {
        PlaceData()
    }

    @Synchronized
    private fun save(data: PlaceData) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(data))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {
        }
    }

    /** Saves or moves a named place. Null when there are already [MAX_PLACES] others. */
    @Synchronized
    fun savePlace(name: String, latitude: Double, longitude: Double, label: String): SavedPlace? {
        val key = placeKey(name)
        if (key.isEmpty()) return null
        val data = load()
        val others = data.places.filter { placeKey(it.name) != key }
        if (others.size >= MAX_PLACES) return null
        val place = SavedPlace(key, latitude, longitude, label.take(200))
        save(data.copy(places = others + place))
        return place
    }

    @Synchronized
    fun forgetPlace(name: String): Boolean {
        val data = load()
        val kept = data.places.filter { placeKey(it.name) != placeKey(name) }
        if (kept.size == data.places.size) return false
        save(data.copy(places = kept))
        return true
    }

    @Synchronized
    fun add(r: PlaceReminder): PlaceReminder? {
        val data = load()
        if (data.reminders.size >= MAX_PLACE_REMINDERS) return null
        val stored = r.copy(id = data.nextId, radiusM = r.radiusM.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M), text = r.text.trim().take(200), task = r.task.trim().take(300))
        save(data.copy(reminders = data.reminders + stored, nextId = data.nextId + 1))
        return stored
    }

    @Synchronized
    fun remove(ids: Collection<Int>) {
        val data = load()
        save(data.copy(reminders = data.reminders.filter { it.id !in ids }))
    }

    @Synchronized
    fun markFired(id: Int, at: Long) {
        val data = load()
        save(data.copy(reminders = data.reminders.map { if (it.id == id) it.copy(lastFiredAt = at) else it }))
    }
}
