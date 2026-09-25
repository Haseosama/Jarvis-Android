package com.jarvis.android.habits

import com.jarvis.android.offline.normalize
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/*
 * Medications and habits: "rappelle-moi mon médicament à 8 h tous les jours", "boire de l'eau à 10 h, 14 h et 17 h".
 * At each time Jarvis says it and shows a notification with "Fait" / "Pas cette fois"; the answer (or a spoken
 * "j'ai pris mon médicament") goes into a log, so "est-ce que j'ai pris mon médicament ?" and "combien de prises
 * oubliées cette semaine ?" have a real answer. An aide-mémoire, not a medical device: it never suggests a dose.
 *
 * Everything here but the file I/O is pure, so the scheduling rules are unit-tested without Android.
 */

internal const val MAX_HABITS = 30
internal const val MAX_TIMES_PER_HABIT = 8
internal const val MAX_LOG_ENTRIES = 3_000

/** An answer given this long before a slot still counts for it ("je l'ai pris un peu en avance"). */
internal const val EARLY_MINUTES = 60L
/** A slot is still the one being answered for this long after its time; past that, "fait" starts an unscheduled entry. */
internal const val LATE_HOURS = 12L

@Serializable
internal data class Habit(
    val id: Int,
    val name: String,
    val times: List<String>,           // "08:00", sorted
    val days: List<Int> = emptyList(), // ISO day numbers, 1 = Monday; empty = every day
    val medication: Boolean = false,
    val createdAt: Long = 0L,
)

internal enum class HabitAnswer { DONE, SKIPPED }

/** One answer. [slot] is the scheduled time it answers (epoch ms), or 0 for an unscheduled "j'ai bu un verre d'eau". */
@Serializable
internal data class HabitEntry(val habitId: Int, val slot: Long, val answer: HabitAnswer, val at: Long)

@Serializable
internal data class HabitData(
    val habits: List<Habit> = emptyList(),
    val log: List<HabitEntry> = emptyList(),
    val nextId: Int = 1,
)

/** "8h", "8 h 30", "08:00", "20h", "20" to a time, or null. */
internal fun parseHabitTime(text: String): LocalTime? {
    val t = text.trim().lowercase(Locale.ROOT).replace(" ", "")
    val m = Regex("^(\\d{1,2})(?:[h:](\\d{2})?)?$").find(t) ?: return null
    val hour = m.groupValues[1].toInt()
    val minute = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
    return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
}

/** "8h, 20h" / "8h et 20h" to sorted distinct times, or null when one of them is not a time. */
internal fun parseHabitTimes(text: String): List<LocalTime>? {
    val parts = text.split(',', ';').flatMap { it.split(" et ") }.map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val times = parts.map { parseHabitTime(it) ?: return null }
    return times.distinct().sorted()
}

private val DAY_WORDS = mapOf(
    "lundi" to 1, "mardi" to 2, "mercredi" to 3, "jeudi" to 4, "vendredi" to 5, "samedi" to 6, "dimanche" to 7,
)

/** "lundi, mercredi" / "en semaine" / "le week-end" / "1,3,5" to ISO day numbers; empty for every day. */
internal fun parseHabitDays(text: String): List<Int> {
    val n = normalize(text)
    if (n.isEmpty() || "tous les jours" in n || "chaque jour" in n) return emptyList()
    if ("semaine" in n && "week" !in n) return listOf(1, 2, 3, 4, 5)
    if ("week end" in n || "weekend" in n) return listOf(6, 7)
    val words = DAY_WORDS.filterKeys { it in n }.values
    val numbers = n.split(' ').mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }
    return (words + numbers).distinct().sorted()
}

internal fun habitDaysLabel(days: List<Int>): String =
    if (days.isEmpty()) "tous les jours"
    else days.sorted().joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.FULL, Locale.FRENCH) }

internal fun formatTime(t: LocalTime): String = if (t.minute == 0) "${t.hour} h" else "${t.hour} h ${t.minute.toString().padStart(2, '0')}"

/** Every scheduled slot of [habit] from [from] (inclusive) to [to] (exclusive), in order. */
internal fun slotsBetween(habit: Habit, from: LocalDateTime, to: LocalDateTime): List<LocalDateTime> {
    val times = habit.times.mapNotNull { parseHabitTime(it) }.sorted()
    val out = mutableListOf<LocalDateTime>()
    var day = from.toLocalDate()
    while (!day.isAfter(to.toLocalDate())) {
        if (habit.days.isEmpty() || day.dayOfWeek.value in habit.days) {
            for (t in times) {
                val slot = day.atTime(t)
                if (!slot.isBefore(from) && slot.isBefore(to)) out += slot
            }
        }
        day = day.plusDays(1)
    }
    return out
}

/** The earliest slot of any habit strictly after [now] (looked for over the next eight days), with its habit. */
internal fun nextSlot(habits: List<Habit>, now: LocalDateTime): Pair<Habit, LocalDateTime>? =
    habits.flatMap { h -> slotsBetween(h, now.plusSeconds(1), now.plusDays(8)).take(1).map { h to it } }
        .minByOrNull { it.second }

private val GENERIC_MEDICATION_WORDS = setOf("medicament", "medicaments", "traitement", "cachet", "cachets", "pilule", "comprime", "comprimes")

/**
 * The habit a bare "c'est fait" answers when several exist: the one with the most recent slot still waiting for an
 * answer (the one Jarvis just reminded about, usually). Null when none is waiting.
 */
internal fun habitWaitingForAnswer(habits: List<Habit>, log: List<HabitEntry>, now: LocalDateTime, zone: ZoneId): Habit? =
    habits.mapNotNull { h -> slotToAnswer(h, log, now, zone)?.takeIf { !it.isAfter(now) }?.let { h to it } }
        .maxByOrNull { it.second }?.first

/** Which habit a spoken name means: exact (folded) name first, then one containing every word of it. */
internal fun findHabit(habits: List<Habit>, query: String): Habit? {
    val q = normalize(query).removePrefix("mon ").removePrefix("ma ").removePrefix("mes ").removePrefix("le ").removePrefix("la ").trim()
    if (q.isEmpty()) return habits.singleOrNull()
    // "mon médicament" when the medicine is saved as "Doliprane": the generic word means the only medicine there is.
    if (q in GENERIC_MEDICATION_WORDS && habits.none { normalize(it.name).contains(q) }) return habits.filter { it.medication }.singleOrNull()
    habits.firstOrNull { normalize(it.name) == q }?.let { return it }
    val words = q.split(' ').filter { it.length > 2 }
    return habits.filter { h -> val n = normalize(h.name); words.isNotEmpty() && words.all { it in n } }.minByOrNull { it.name.length }
        ?: habits.firstOrNull { normalize(it.name).contains(q) || q.contains(normalize(it.name)) }
}

/**
 * The scheduled slot a "fait" / "pas cette fois" said at [now] answers: the latest slot from [LATE_HOURS] ago to
 * [EARLY_MINUTES] ahead that has no answer yet. Null when there is none (the answer is then logged unscheduled).
 */
internal fun slotToAnswer(habit: Habit, log: List<HabitEntry>, now: LocalDateTime, zone: ZoneId): LocalDateTime? {
    val answered = log.filter { it.habitId == habit.id && it.slot != 0L }.map { it.slot }.toSet()
    return slotsBetween(habit, now.minusHours(LATE_HOURS), now.plusMinutes(EARLY_MINUTES))
        .filter { it.atZone(zone).toInstant().toEpochMilli() !in answered }
        .maxOrNull()
}

/** "Aujourd'hui, médicament : 8 h fait à 8 h 05, 20 h pas encore l'heure." */
internal fun todayStatus(habit: Habit, log: List<HabitEntry>, now: LocalDateTime, zone: ZoneId): String {
    val day = now.toLocalDate()
    val slots = slotsBetween(habit, day.atStartOfDay(), day.plusDays(1).atStartOfDay())
    val extra = log.filter { it.habitId == habit.id && it.slot == 0L && it.answer == HabitAnswer.DONE && dayOf(it.at, zone) == day }
    if (slots.isEmpty()) {
        return "Aujourd'hui, ${habit.name} : rien de prévu" + if (extra.isNotEmpty()) ", noté ${extra.size} fois." else "."
    }
    val parts = slots.map { slot ->
        val ms = slot.atZone(zone).toInstant().toEpochMilli()
        val entry = log.lastOrNull { it.habitId == habit.id && it.slot == ms }
        val t = formatTime(slot.toLocalTime())
        when {
            entry?.answer == HabitAnswer.DONE -> "$t fait à ${formatTime(timeOf(entry.at, zone))}"
            entry?.answer == HabitAnswer.SKIPPED -> "$t sauté"
            slot.isAfter(now) -> "$t pas encore l'heure"
            else -> "$t pas noté"
        }
    }
    val more = if (extra.isNotEmpty()) " (et ${extra.size} fois en dehors des heures prévues)" else ""
    return "Aujourd'hui, ${habit.name} : ${parts.joinToString(", ")}$more."
}

/** "Cette semaine, médicament : 12 prises sur 14, oubliées : mercredi 20 h, samedi 8 h." Counts only slots already past. */
internal fun adherence(habit: Habit, log: List<HabitEntry>, from: LocalDateTime, now: LocalDateTime, zone: ZoneId, label: String): String {
    val slots = slotsBetween(habit, from, now.plusSeconds(1))
    if (slots.isEmpty()) return "${label.replaceFirstChar { it.uppercase() }}, ${habit.name} : aucune heure passée à vérifier."
    val byslot = log.filter { it.habitId == habit.id && it.slot != 0L }.associateBy { it.slot }
    val done = slots.count { byslot[it.atZone(zone).toInstant().toEpochMilli()]?.answer == HabitAnswer.DONE }
    val missed = slots.filter { byslot[it.atZone(zone).toInstant().toEpochMilli()] == null }
    val word = if (habit.medication) "prises" else "fois"
    val missedText = if (missed.isEmpty()) ", aucune oubliée" else
        ", non notées : " + missed.takeLast(6).joinToString(", ") {
            it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.FRENCH) + " " + formatTime(it.toLocalTime())
        } + if (missed.size > 6) " et ${missed.size - 6} autre(s)" else ""
    return "${label.replaceFirstChar { it.uppercase() }}, ${habit.name} : $done $word sur ${slots.size}$missedText."
}

internal fun describeHabit(h: Habit): String {
    val times = h.times.mapNotNull { parseHabitTime(it) }.joinToString(", ") { formatTime(it) }
    return "${h.name}${if (h.medication) " (médicament)" else ""} : $times, ${habitDaysLabel(h.days)}"
}

private fun dayOf(ms: Long, zone: ZoneId): LocalDate = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
private fun timeOf(ms: Long, zone: ZoneId): LocalTime = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()

/** The habits and their log in one private file. Synchronised, not suspending: the alarm receiver uses it too. */
internal class HabitStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): HabitData = try {
        if (file.exists()) json.decodeFromString<HabitData>(file.readText()) else HabitData()
    } catch (_: Exception) {
        HabitData()
    }

    @Synchronized
    private fun save(data: HabitData) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(data))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {
        }
    }

    /** Adds a habit; a habit of the same name is replaced (new times), so "change mon médicament à 9 h" just works. */
    @Synchronized
    fun upsert(name: String, times: List<LocalTime>, days: List<Int>, medication: Boolean, now: Long): Habit? {
        val clean = name.trim().take(60)
        if (clean.isEmpty() || times.isEmpty() || times.size > MAX_TIMES_PER_HABIT) return null
        val data = load()
        val existing = data.habits.firstOrNull { normalize(it.name) == normalize(clean) }
        if (existing == null && data.habits.size >= MAX_HABITS) return null
        val habit = Habit(existing?.id ?: data.nextId, clean, times.map { "%02d:%02d".format(it.hour, it.minute) }, days.sorted(), medication, existing?.createdAt ?: now)
        save(data.copy(
            habits = data.habits.filter { it.id != habit.id } + habit,
            nextId = if (existing == null) data.nextId + 1 else data.nextId,
        ))
        return habit
    }

    @Synchronized
    fun remove(id: Int): Boolean {
        val data = load()
        if (data.habits.none { it.id == id }) return false
        save(data.copy(habits = data.habits.filter { it.id != id }, log = data.log.filter { it.habitId != id }))
        return true
    }

    /** Records an answer; one answer per scheduled slot (a later one replaces it). */
    @Synchronized
    fun answer(habitId: Int, slot: Long, answer: HabitAnswer, at: Long) {
        val data = load()
        if (data.habits.none { it.id == habitId }) return
        val kept = data.log.filterNot { slot != 0L && it.habitId == habitId && it.slot == slot }
        save(data.copy(log = (kept + HabitEntry(habitId, slot, answer, at)).takeLast(MAX_LOG_ENTRIES)))
    }
}
