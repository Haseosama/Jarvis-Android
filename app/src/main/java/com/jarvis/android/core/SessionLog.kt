package com.jarvis.android.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What started a voice session. */
enum class SessionTrigger(private val labelFr: String) {
    WAKE_WORD("mot d’activation"),
    APP_BUTTON("bouton de l’appli"),
    UNKNOWN("origine inconnue");

    /** The label in the current interface language. */
    val label: String get() = com.jarvis.android.i18n.tr(labelFr)

    companion object {
        fun fromName(name: String?): SessionTrigger = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

internal data class SessionRecord(val startedAt: Long, val trigger: SessionTrigger, val durationMs: Long? = null)

internal const val MAX_SESSION_RECORDS = 30

/** [list] with [record] added, keeping only the newest [max]. */
internal fun withStarted(list: List<SessionRecord>, record: SessionRecord, max: Int = MAX_SESSION_RECORDS): List<SessionRecord> =
    (list + record).takeLast(max)

/** [list] with the duration of the latest unfinished session filled in. */
internal fun withEnded(list: List<SessionRecord>, endedAt: Long): List<SessionRecord> {
    val index = list.indexOfLast { it.durationMs == null }
    if (index < 0) return list
    return list.toMutableList().also { it[index] = it[index].copy(durationMs = (endedAt - it[index].startedAt).coerceAtLeast(0)) }
}

internal fun encodeSessions(list: List<SessionRecord>): String = JsonArray(
    list.map {
        buildJsonObject {
            put("at", it.startedAt)
            put("trigger", it.trigger.name)
            it.durationMs?.let { d -> put("ms", d) }
        }
    }
).toString()

internal fun decodeSessions(text: String?): List<SessionRecord> = try {
    Json.parseToJsonElement(text.orEmpty()).jsonArray.mapNotNull { element ->
        val o: JsonObject = element.jsonObject
        val at = o["at"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        SessionRecord(at, SessionTrigger.fromName(o["trigger"]?.jsonPrimitive?.contentOrNull), o["ms"]?.jsonPrimitive?.longOrNull)
    }.takeLast(MAX_SESSION_RECORDS)
} catch (_: Exception) {
    emptyList()
}

internal fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"
}

/** "20/09 10:49 · mot d’activation · 38 s"; a session with no recorded end says so. */
internal fun formatSessionRecord(record: SessionRecord, zone: ZoneId): String {
    val time = Instant.ofEpochMilli(record.startedAt).atZone(zone).format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
    val length = record.durationMs?.let { formatDuration(it) } ?: "en cours ou interrompue"
    return "$time · ${record.trigger.label} · $length"
}

/** The last sessions and what started them, kept in a small file that is never backed up. */
internal class SessionLog(private val file: File) {
    @Synchronized
    fun entries(): List<SessionRecord> = decodeSessions(try { file.readText() } catch (_: Exception) { null })

    @Synchronized
    fun started(trigger: SessionTrigger, now: Long = System.currentTimeMillis()) {
        write(withStarted(entries(), SessionRecord(now, trigger)))
    }

    @Synchronized
    fun ended(now: Long = System.currentTimeMillis()) {
        write(withEnded(entries(), now))
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun write(list: List<SessionRecord>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(encodeSessions(list))
        } catch (_: Exception) {
            // The history is a convenience: failing to write it must never disturb a session.
        }
    }
}
