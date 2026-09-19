package com.jarvis.android.timers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID

internal enum class TimerStatus {
    SCHEDULED, FIRED, CANCELLED
}

internal data class TimerRecord(
    val id: Int,
    val token: String,
    val label: String,
    val durationSeconds: Long,
    val createdAt: Long,
    val triggerAt: Long,
    val approximate: Boolean = true,
    val status: TimerStatus = TimerStatus.SCHEDULED,
)

internal data class TimerSnapshot(
    val nextId: Long = 1,
    val records: List<TimerRecord> = emptyList(),
)

internal interface TimerStorage {
    fun read(): String?
    fun write(value: String)
}

internal class TimerStore(private val storage: TimerStorage) {
    fun load(): TimerSnapshot {
        return try {
            val raw = storage.read() ?: return TimerSnapshot()
            require(raw.length <= MAX_STORAGE_CHARS)
            val root = Json.parseToJsonElement(raw) as JsonObject
            require((root["version"] as? JsonPrimitive)?.intOrNull == 1)
            val nextId = (root["nextId"] as JsonPrimitive).longOrNull ?: error("nextId")
            val records = (root["records"] as JsonArray).map { element ->
                val item = element as JsonObject
                fun string(key: String): String {
                    val value = item[key] as JsonPrimitive
                    require(value.isString)
                    return value.contentOrNull ?: error(key)
                }
                TimerRecord(
                    id = (item["id"] as JsonPrimitive).intOrNull ?: error("id"),
                    token = string("token"),
                    label = string("label"),
                    durationSeconds = (item["durationSeconds"] as JsonPrimitive).longOrNull ?: error("durationSeconds"),
                    createdAt = (item["createdAt"] as JsonPrimitive).longOrNull ?: error("createdAt"),
                    triggerAt = (item["triggerAt"] as JsonPrimitive).longOrNull ?: error("triggerAt"),
                    approximate = when ((item["approximate"] as JsonPrimitive).content) {
                        "true" -> true
                        "false" -> false
                        else -> error("approximate")
                    },
                    status = TimerStatus.valueOf(string("status")),
                )
            }
            TimerSnapshot(nextId, records).also(::validate)
        } catch (e: Exception) {
            throw IOException("Stockage des minuteurs illisible ou incompatible ; aucune donnée n’a été effacée.", e)
        }
    }

    fun save(snapshot: TimerSnapshot) {
        validate(snapshot)
        val raw = buildJsonObject {
            put("version", 1)
            put("nextId", snapshot.nextId)
            put("records", JsonArray(snapshot.records.map { record ->
                buildJsonObject {
                    put("id", record.id)
                    put("token", record.token)
                    put("label", record.label)
                    put("durationSeconds", record.durationSeconds)
                    put("createdAt", record.createdAt)
                    put("triggerAt", record.triggerAt)
                    put("approximate", record.approximate)
                    put("status", record.status.name)
                }
            }))
        }.toString()
        require(raw.length <= MAX_STORAGE_CHARS) { "Le stockage des minuteurs est plein." }
        try {
            storage.write(raw)
        } catch (e: Exception) {
            throw IOException("Impossible d’enregistrer les minuteurs ; opération non confirmée.", e)
        }
    }

    private fun validate(snapshot: TimerSnapshot) {
        require(snapshot.nextId in 1..Int.MAX_VALUE.toLong() + 1)
        require(snapshot.records.size <= MAX_RECORDS)
        require(snapshot.records.map { it.id }.toSet().size == snapshot.records.size)
        snapshot.records.forEach {
            require(it.id > 0 && it.id < snapshot.nextId)
            require(UUID.fromString(it.token).toString() == it.token)
            require(it.label.length <= MAX_LABEL_CHARS)
            require(it.durationSeconds in 1..TimerDurations.MAX_SECONDS)
            require(it.triggerAt == it.createdAt + it.durationSeconds * 1000)
        }
    }

    companion object {
        const val MAX_RECORDS = 50
        const val MAX_LABEL_CHARS = 200
        const val MAX_STORAGE_CHARS = 200_000
    }
}
