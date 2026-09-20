package com.jarvis.android.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Locale

/** What is kept of a finished conversation: a one-line summary and the lasting facts about the user, by category. */
internal data class Extraction(val summary: String, val facts: Map<String, Map<String, String>>)

internal const val MAX_EXTRACTED_FACTS = 8

internal const val EXTRACT_INSTRUCTION =
    "Tu lis une conversation entre un utilisateur et son assistant vocal. Réponds uniquement par un objet JSON de la forme " +
        "{\"summary\": \"…\", \"facts\": {\"identity\": {}, \"preferences\": {}, \"projects\": {}, \"relationships\": {}, \"wishes\": {}, \"notes\": {}}}. " +
        "summary : une ou deux phrases courtes, en français, à la troisième personne : ce que l’utilisateur a demandé, ce qui a été fait, ce qui reste à faire ; 250 caractères au plus. " +
        "facts : seulement des informations durables sur l’utilisateur que celui-ci a données lui-même (prénom, âge, ville, métier, goûts, habitudes, projets, proches, souhaits) ; " +
        "clé courte en snake_case sans accents (par exemple sister_name), valeur d’une courte phrase. N’invente rien, ne répète pas ce qui est déjà connu " +
        "(liste ci-dessous) sauf si cela a changé, au plus $MAX_EXTRACTED_FACTS faits. N’inclus jamais de mot de passe, de code, de numéro de carte ou de compte, ni de donnée de santé. " +
        "Sans information durable, laisse les objets vides."

internal fun buildExtractionRequest(transcript: String, known: String): JsonObject {
    if (transcript.isBlank()) throw IllegalArgumentException("Conversation vide.")
    return buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { addJsonObject { put("text", EXTRACT_INSTRUCTION + "\nDéjà connu :\n" + known.ifBlank { "(rien)" }) } }
        }
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") { addJsonObject { put("text", transcript) } }
            }
        }
        putJsonObject("generationConfig") { put("responseMimeType", "application/json") }
    }
}

private val SENSITIVE = Regex("""\d[\d \-.]{10,}\d|mot de passe|password|iban""", RegexOption.IGNORE_CASE)

/** A key the memory can hold: lower case, letters, digits and underscores, 40 characters at most. */
internal fun cleanFactKey(raw: String): String =
    java.text.Normalizer.normalize(raw.trim().lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40)

/**
 * Reads the model's answer. Accepts a JSON object, possibly inside a code fence; a plain sentence counts as the summary alone.
 * Facts with an unknown category are dropped (the memory would file them under notes), sensitive-looking values are refused,
 * and no more than [MAX_EXTRACTED_FACTS] are kept.
 */
internal fun parseExtraction(text: String): Extraction? {
    val body = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    if (body.isEmpty()) return null
    val root = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }
        ?: return Extraction(body.take(280), emptyMap())
    val summary = (root["summary"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim().take(280)
    val valid = setOf("identity", "preferences", "projects", "relationships", "wishes", "notes")
    val facts = linkedMapOf<String, Map<String, String>>()
    var count = 0
    for ((category, group) in (root["facts"] as? JsonObject).orEmpty()) {
        if (category !in valid) continue
        val kept = linkedMapOf<String, String>()
        for ((rawKey, rawValue) in (group as? JsonObject).orEmpty()) {
            if (count >= MAX_EXTRACTED_FACTS) break
            val key = cleanFactKey(rawKey)
            val value = (rawValue as? JsonPrimitive)?.contentOrNull.orEmpty().trim().take(300)
            if (key.isEmpty() || value.isEmpty() || SENSITIVE.containsMatchIn(value)) continue
            kept[key] = value
            count++
        }
        if (kept.isNotEmpty()) facts[category] = kept
    }
    if (summary.isEmpty() && facts.isEmpty()) return null
    return Extraction(summary, facts)
}

/**
 * Conversations whose summary could not be written yet (no network, app killed, no key): each is kept as a file until it has been
 * processed, so that stopping a session never loses what was said. The newest few are kept, for a week at most.
 */
internal class PendingTranscripts(private val dir: File, private val maxFiles: Int = 5, private val maxAgeMs: Long = 7L * 24 * 3600 * 1000) {
    fun add(transcript: String, now: Long = System.currentTimeMillis()): File {
        dir.mkdirs()
        return File(dir, "t_$now.txt").also { it.writeText(transcript, Charsets.UTF_8) }
    }

    /** The waiting conversations, oldest first; expired or surplus ones are removed. */
    fun list(now: Long = System.currentTimeMillis()): List<File> {
        val all = dir.listFiles { f -> f.extension == "txt" }?.sortedBy { it.name } ?: return emptyList()
        val fresh = all.filter { now - it.lastModified() <= maxAgeMs }
        (all - fresh.toSet()).forEach { it.delete() }
        val kept = fresh.takeLast(maxFiles)
        (fresh - kept.toSet()).forEach { it.delete() }
        return kept
    }
}
