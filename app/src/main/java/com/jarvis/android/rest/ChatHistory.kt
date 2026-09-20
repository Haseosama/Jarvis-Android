package com.jarvis.android.rest

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** What the chat screen shows, as saved on disk. */
@Serializable
internal data class SavedMessage(val role: String, val text: String)

/** The chat as saved: the visible messages and the raw turns that give the model its context. */
@Serializable
internal data class SavedChat(val messages: List<SavedMessage>, val turns: List<JsonObject>)

/** Total size cap of the saved turns, in characters. Images never reach it: only text turns are kept (see [trimTurns]). */
internal const val MAX_SAVED_TURN_CHARS = 200_000

/**
 * Drops the oldest turns until the rest fits [maxChars], then further until the first turn is a plain user message
 * (a conversation cannot start with a model turn or a tool answer). Turns that carry binary data are never kept.
 */
internal fun trimTurns(turns: List<JsonObject>, maxChars: Int): List<JsonObject> {
    val clean = turns.filterNot { "inlineData" in it.toString() || "inline_data" in it.toString() }
    var start = 0
    var total = clean.sumOf { it.toString().length }
    while (start < clean.size && total > maxChars) {
        total -= clean[start].toString().length
        start++
    }
    while (start < clean.size && !isPlainUserTurn(clean[start])) start++
    return clean.drop(start)
}

private fun isPlainUserTurn(turn: JsonObject): Boolean = try {
    turn["role"]?.jsonPrimitive?.content == "user" && turn["parts"]?.jsonArray?.firstOrNull()?.jsonObject?.containsKey("text") == true
} catch (_: Exception) {
    false
}

/** The saved chat in a private file. Reading or writing problems are ignored: the chat then simply starts empty. */
internal class ChatHistoryStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): SavedChat? = try {
        if (file.exists()) json.decodeFromString(SavedChat.serializer(), file.readText()) else null
    } catch (_: Exception) {
        null
    }

    fun save(chat: SavedChat) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(SavedChat.serializer(), chat))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
        }
    }

    fun clear() {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }
}

internal fun emptyTurns(): List<JsonObject> = emptyList()
internal fun JsonArray.asTurns(): List<JsonObject> = map { it.jsonObject }
