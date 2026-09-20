package com.jarvis.android.routines

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/** A task Jarvis carries out by itself at a fixed time. [days] holds ISO day numbers (1 = Monday); empty means every day. */
@Serializable
data class Routine(
    val id: Int,
    val time: String,
    val task: String,
    val days: List<Int> = emptyList(),
    val lastRunDay: String = "",
)

/** A run more than this late is skipped: doing yesterday's "good morning" task at midnight helps nobody. */
internal const val ROUTINE_GRACE_MINUTES = 180L

internal fun parseTime(text: String): LocalTime? = try {
    LocalTime.parse(text.trim().let { if (it.length == 4 && it[1] == ':') "0$it" else it })
} catch (_: Exception) {
    null
}

/** True when [routine] should run now: its day matches, its time has passed (within the grace period), and it has not run today. */
internal fun isDue(routine: Routine, now: LocalDateTime): Boolean {
    val time = parseTime(routine.time) ?: return false
    if (routine.lastRunDay == now.toLocalDate().toString()) return false
    if (routine.days.isNotEmpty() && now.dayOfWeek.value !in routine.days) return false
    val scheduled = now.toLocalDate().atTime(time)
    if (now.isBefore(scheduled)) return false
    return java.time.Duration.between(scheduled, now).toMinutes() <= ROUTINE_GRACE_MINUTES
}

internal fun dayNames(days: List<Int>): String =
    if (days.isEmpty()) "tous les jours"
    else days.sorted().joinToString(", ") { DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.FRENCH) }

/** Routines kept in a small private preferences file. */
class RoutineStore(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_routines", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Routine.serializer())

    @Synchronized
    fun list(): List<Routine> = try {
        json.decodeFromString(serializer, prefs.getString("routines", "[]").orEmpty())
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    private fun save(routines: List<Routine>) {
        prefs.edit().putString("routines", json.encodeToString(serializer, routines)).apply()
    }

    @Synchronized
    fun add(time: String, task: String, days: List<Int>): Routine {
        val parsed = requireNotNull(parseTime(time)) { "Heure invalide : utilisez HH:mm." }
        require(task.isNotBlank()) { "Indiquez la tâche à effectuer." }
        require(days.all { it in 1..7 }) { "Jours invalides : 1 (lundi) à 7 (dimanche)." }
        val all = list()
        require(all.size < MAX_ROUTINES) { "Maximum $MAX_ROUTINES routines : supprimez-en une d’abord." }
        val routine = Routine((all.maxOfOrNull { it.id } ?: 0) + 1, parsed.toString(), task.trim().take(500), days.distinct().sorted())
        save(all + routine)
        return routine
    }

    @Synchronized
    fun remove(id: Int): Boolean {
        val all = list()
        if (all.none { it.id == id }) return false
        save(all.filterNot { it.id == id })
        return true
    }

    @Synchronized
    fun markRun(id: Int, day: String) = save(list().map { if (it.id == id) it.copy(lastRunDay = day) else it })

    companion object {
        const val MAX_ROUTINES = 10
    }
}
