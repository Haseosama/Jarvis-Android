package com.jarvis.android.journal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * What Jarvis notes for Sunday's summary: each alert it gave (its subject in words, and what it said) and each drive (driving mode on,
 * then off). Kept five weeks at most, on the phone only.
 */

/** An alert Jarvis gave at [at] about [kind] ("vigilance météo", "colis"…), or a drive ([kind] = [DRIVE]) from [at] to [endAt]. */
internal data class JournalEntry(val at: Long, val kind: String, val text: String = "", val endAt: Long = 0L)

/** The subjects of the alerts, as they are said in the summary. */
internal object AlertKind {
    const val VIGILANCE = "vigilance météo"
    const val RAIN = "pluie"
    const val AIR = "air et pollens"
    const val FUEL = "carburant"
    const val RECALL = "rappel de produit"
    const val OUTAGE = "coupure"
    const val COMMUTE = "trajet perturbé"
    const val PARCEL = "colis"
    const val BUDGET = "budget"
    const val QUAKE = "séisme"
    const val ENERGY = "électricité"
    const val WATCH = "surveillance"
}

internal const val DRIVE = "drive"
internal const val JOURNAL_KEEP_MS = 35L * 86_400_000L
internal const val JOURNAL_MAX = 500

/** [list] with [entry] added, without what is older than five weeks at [now], the newest [JOURNAL_MAX] kept. */
internal fun appendEntry(list: List<JournalEntry>, entry: JournalEntry, now: Long): List<JournalEntry> =
    (list + entry).filter { now - maxOf(it.at, it.endAt) < JOURNAL_KEEP_MS }.sortedBy { it.at }.takeLast(JOURNAL_MAX)

internal fun encodeJournal(list: List<JournalEntry>): String = JsonArray(list.map { e ->
    buildJsonObject {
        put("at", e.at)
        put("kind", e.kind)
        if (e.text.isNotEmpty()) put("text", e.text)
        if (e.endAt != 0L) put("end", e.endAt)
    }
}).toString()

/** What [encodeJournal] wrote; an unreadable journal or entry is left out rather than failing. */
internal fun decodeJournal(raw: String?): List<JournalEntry> = if (raw == null) emptyList() else try {
    (Json.parseToJsonElement(raw) as JsonArray).mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val at = (o["at"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        val kind = (o["kind"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        JournalEntry(at, kind, (o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (o["end"] as? JsonPrimitive)?.longOrNull ?: 0L)
    }
} catch (_: Exception) {
    emptyList()
}
