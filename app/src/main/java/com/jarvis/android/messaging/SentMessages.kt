package com.jarvis.android.messaging

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class SentMessage(val time: Long, val to: String, val via: String, val text: String)

/** What Jarvis sent in the user's name, newest last, kept on the phone (not in backups). The settings show the latest ones. */
internal class SentMessages(private val file: File, private val max: Int = 50) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun all(): List<SentMessage> = try {
        json.decodeFromString<List<SentMessage>>(file.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun add(message: SentMessage) {
        val kept = (all() + message.copy(text = message.text.take(500))).takeLast(max)
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(kept), Charsets.UTF_8)
        } catch (_: java.io.IOException) {
            // the message was sent; only its record is lost
        }
    }

    /** The [count] latest, newest first. */
    fun recent(count: Int): List<SentMessage> = all().takeLast(count).reversed()

    @Synchronized
    fun clear() {
        file.delete()
    }
}
