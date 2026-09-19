package com.jarvis.android.reminders

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
import java.time.ZoneId
import java.util.UUID

internal enum class ReminderStatus {
    PREPARING, SCHEDULED, DELIVERING, DELIVERED, BLOCKED, FAILED
}

internal data class ReminderRecord(
    val id: Int,
    val token: String,
    val text: String,
    val whenIso: String,
    val zoneId: String,
    val triggerAt: Long,
    val approximate: Boolean = true,
    val status: ReminderStatus = ReminderStatus.PREPARING,
)

internal data class ReminderSnapshot(
    val nextId: Long = 1,
    val records: List<ReminderRecord> = emptyList(),
)

internal interface ReminderStorage {
    fun read(): String?
    fun write(value: String)
}

internal class ReminderStore(private val storage: ReminderStorage) {
    fun load(): ReminderSnapshot {
        return try {
            val raw = storage.read() ?: return ReminderSnapshot()
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
                ReminderRecord(
                    id = (item["id"] as JsonPrimitive).intOrNull ?: error("id"),
                    token = string("token"),
                    text = string("text"),
                    whenIso = string("whenIso"),
                    zoneId = string("zoneId"),
                    triggerAt = (item["triggerAt"] as JsonPrimitive).longOrNull ?: error("triggerAt"),
                    approximate = when ((item["approximate"] as JsonPrimitive).content) {
                        "true" -> true
                        "false" -> false
                        else -> error("approximate")
                    },
                    status = ReminderStatus.valueOf(string("status")),
                )
            }
            ReminderSnapshot(nextId, records).also(::validate)
        } catch (e: Exception) {
            throw IOException("Stockage des rappels illisible ou incompatible ; aucune donnée n’a été effacée.", e)
        }
    }

    fun save(snapshot: ReminderSnapshot) {
        validate(snapshot)
        val raw = buildJsonObject {
            put("version", 1)
            put("nextId", snapshot.nextId)
            put("records", JsonArray(snapshot.records.map { record ->
                buildJsonObject {
                    put("id", record.id)
                    put("token", record.token)
                    put("text", record.text)
                    put("whenIso", record.whenIso)
                    put("zoneId", record.zoneId)
                    put("triggerAt", record.triggerAt)
                    put("approximate", record.approximate)
                    put("status", record.status.name)
                }
            }))
        }.toString()
        require(raw.length <= MAX_STORAGE_CHARS) { "Le stockage des rappels est plein." }
        try {
            storage.write(raw)
        } catch (e: Exception) {
            throw IOException("Impossible d’enregistrer les rappels ; opération non confirmée.", e)
        }
    }

    private fun validate(snapshot: ReminderSnapshot) {
        require(snapshot.nextId in 1..Int.MAX_VALUE.toLong() + 1)
        require(snapshot.records.size <= MAX_RECORDS)
        require(snapshot.records.map { it.id }.toSet().size == snapshot.records.size)
        snapshot.records.forEach {
            require(it.id > 0 && it.id < snapshot.nextId)
            require(UUID.fromString(it.token).toString() == it.token)
            require(it.text.isNotBlank() && it.text.length <= MAX_TEXT_CHARS)
            val expected = ReminderDates.parse(it.whenIso, ZoneId.of(it.zoneId), java.time.Instant.MIN)
            require(expected == it.triggerAt)
        }
    }

    companion object {
        const val MAX_RECORDS = 200
        const val MAX_TEXT_CHARS = 1000
        const val MAX_STORAGE_CHARS = 2_000_000
    }
}
