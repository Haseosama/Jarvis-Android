package com.jarvis.android.memory

import com.jarvis.android.core.ConversationMessage
import com.jarvis.android.core.ConversationRole
import com.jarvis.android.reminders.ReminderRecord
import com.jarvis.android.reminders.ReminderStatus
import com.jarvis.android.rest.RestChatException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val MIN_USER_TURNS_TO_SUMMARIZE = 2
internal const val MAX_TRANSCRIPT_CHARS = 6_000

/** What a finished session looked like, as plain text for the summarizer (no system lines). */
internal fun transcriptForSummary(messages: List<ConversationMessage>): String {
    val lines = messages.filter { it.role != ConversationRole.SYSTEM && it.text.isNotBlank() }.map {
        (if (it.role == ConversationRole.USER) "Utilisateur : " else "Jarvis : ") + it.text.trim().replace('\n', ' ')
    }
    val joined = lines.joinToString("\n")
    return if (joined.length <= MAX_TRANSCRIPT_CHARS) joined else joined.takeLast(MAX_TRANSCRIPT_CHARS)
}

/** True when the session was long enough to be worth remembering. */
internal fun worthSummarizing(messages: List<ConversationMessage>): Boolean =
    messages.count { it.role == ConversationRole.USER && it.text.isNotBlank() } >= MIN_USER_TURNS_TO_SUMMARIZE

internal const val SUMMARY_INSTRUCTION =
    "Résume cette conversation entre un utilisateur et son assistant vocal en une ou deux phrases courtes, en français, " +
        "à la troisième personne : ce que l’utilisateur a demandé, ce qui a été fait, et ce qui reste éventuellement à faire. " +
        "N’invente rien. N’inclus ni mot de passe, ni code, ni numéro de carte. 250 caractères maximum. Réponds uniquement par le résumé."

internal fun buildSummaryRequest(transcript: String): JsonObject {
    if (transcript.isBlank()) throw RestChatException("Conversation vide.")
    return buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { addJsonObject { put("text", SUMMARY_INSTRUCTION) } }
        }
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") { addJsonObject { put("text", transcript) } }
            }
        }
    }
}

/** Reminders still to come today, as "HH:mm texte", earliest first. */
internal fun remindersToday(records: List<ReminderRecord>, nowMs: Long, zone: ZoneId): List<String> {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val hour = DateTimeFormatter.ofPattern("HH:mm")
    return records
        .filter { it.status == ReminderStatus.SCHEDULED && it.triggerAt >= nowMs }
        .filter { Instant.ofEpochMilli(it.triggerAt).atZone(zone).toLocalDate() == today }
        .sortedBy { it.triggerAt }
        .map { Instant.ofEpochMilli(it.triggerAt).atZone(zone).format(hour) + " " + it.text.trim().take(120) }
}

internal data class BriefingInputs(
    val today: LocalDate,
    val lastBriefingDate: String,
    val lastSession: SessionEntry?,
    val reminders: List<String>,
)

/**
 * The system-prompt block that makes the assistant give a short briefing at the first session of
 * the day, or an empty string when there is nothing to say or it was already given today.
 */
internal fun buildBriefingBlock(inputs: BriefingInputs): String {
    if (inputs.lastBriefingDate == inputs.today.toString()) return ""
    if (inputs.lastSession == null && inputs.reminders.isEmpty()) return ""
    val recap = inputs.lastSession?.let { "LAST SESSION (${it.date}): ${it.summary}\n" }.orEmpty()
    val todo = if (inputs.reminders.isEmpty()) "" else "REMINDERS STILL TO COME TODAY: ${inputs.reminders.joinToString("; ")}\n"
    return "[MORNING BRIEFING]\n" +
        "This is the first session of the day. When the system asks for the briefing, give a short spoken briefing " +
        "(about twenty seconds): greet the user, then mention only the items below, in the language currently in use. " +
        "Do not invent anything and do not read out anything that is not listed.\n" + recap + todo
}

internal const val BRIEFING_TRIGGER =
    "C’est la première session d’aujourd’hui : fais maintenant le briefing du matin, en une vingtaine de secondes."
