package com.jarvis.android.voices

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * More voices than Gemini's 30, as in Hermes Agent: online, Microsoft Edge's read-aloud voices (free, no key, the same service as the
 * edge-tts package Hermes uses) and ElevenLabs (the user's key); offline, any voice of the speech engines installed on the phone
 * (Google's, or Piper voices through an engine such as SherpaTTS). Gemini Live still runs the conversation: with another online
 * voice, its spoken words are taken from the transcript it sends with the audio, sentence by sentence, and read by that voice.
 * This file holds what needs no Android, so it is unit-tested on the JVM.
 */

/** An online voice other than Gemini's: [id] is what the settings store ("edge:fr-FR-DeniseNeural", "eleven:<voice id>"). */
internal data class OnlineVoice(val id: String, val name: String, val female: Boolean, val accentFr: String, val accentEn: String) {
    fun label(english: Boolean): String =
        "$name · " + (if (english) (if (female) "female" else "male") + ", $accentEn" else (if (female) "féminine" else "masculine") + ", $accentFr")
}

internal const val EDGE_PREFIX = "edge:"
internal const val ELEVEN_PREFIX = "eleven:"
internal const val PHONE_PREFIX = "phone:"

/** The French voices of Microsoft Edge, then its multilingual ones (they speak French too, with their own accent). */
internal val EDGE_VOICES: List<OnlineVoice> = listOf(
    edge("fr-FR-DeniseNeural", "Denise", true, "France", "France"),
    edge("fr-FR-HenriNeural", "Henri", false, "France", "France"),
    edge("fr-FR-EloiseNeural", "Eloise", true, "France, enfant", "France, child"),
    edge("fr-FR-VivienneMultilingualNeural", "Vivienne", true, "France, multilingue", "France, multilingual"),
    edge("fr-FR-RemyMultilingualNeural", "Rémy", false, "France, multilingue", "France, multilingual"),
    edge("fr-BE-CharlineNeural", "Charline", true, "Belgique", "Belgium"),
    edge("fr-BE-GerardNeural", "Gérard", false, "Belgique", "Belgium"),
    edge("fr-CH-ArianeNeural", "Ariane", true, "Suisse", "Switzerland"),
    edge("fr-CH-FabriceNeural", "Fabrice", false, "Suisse", "Switzerland"),
    edge("fr-CA-SylvieNeural", "Sylvie", true, "Québec", "Quebec"),
    edge("fr-CA-JeanNeural", "Jean", false, "Québec", "Quebec"),
    edge("fr-CA-AntoineNeural", "Antoine", false, "Québec", "Quebec"),
    edge("fr-CA-ThierryNeural", "Thierry", false, "Québec", "Quebec"),
    edge("en-US-AvaMultilingualNeural", "Ava", true, "américaine, multilingue", "American, multilingual"),
    edge("en-US-EmmaMultilingualNeural", "Emma", true, "américaine, multilingue", "American, multilingual"),
    edge("en-US-AndrewMultilingualNeural", "Andrew", false, "américain, multilingue", "American, multilingual"),
    edge("en-US-BrianMultilingualNeural", "Brian", false, "américain, multilingue", "American, multilingual"),
    edge("de-DE-SeraphinaMultilingualNeural", "Seraphina", true, "allemande, multilingue", "German, multilingual"),
    edge("de-DE-FlorianMultilingualNeural", "Florian", false, "allemand, multilingue", "German, multilingual"),
)

private fun edge(short: String, name: String, female: Boolean, fr: String, en: String) = OnlineVoice(EDGE_PREFIX + short, name, female, fr, en)

/** Which kind of voice a stored choice is. Blank: Gemini's own. */
internal enum class VoiceKind { GEMINI, EDGE, ELEVEN }

internal fun voiceKind(choice: String): VoiceKind = when {
    choice.startsWith(EDGE_PREFIX) -> VoiceKind.EDGE
    choice.startsWith(ELEVEN_PREFIX) && choice.length > ELEVEN_PREFIX.length -> VoiceKind.ELEVEN
    else -> VoiceKind.GEMINI
}

/** "eleven:abc|Rachel" → the ElevenLabs voice id "abc"; "edge:fr-FR-DeniseNeural" → "fr-FR-DeniseNeural". */
internal fun voiceIdOf(choice: String): String = choice.substringAfter(':').substringBefore('|')

/** The name shown for a stored choice ("Denise", "Rachel"); blank for Gemini's. */
internal fun voiceNameOf(choice: String): String = when (voiceKind(choice)) {
    VoiceKind.EDGE -> EDGE_VOICES.firstOrNull { it.id == choice }?.name ?: voiceIdOf(choice).substringAfter('-').substringAfter('-').removeSuffix("Neural")
    VoiceKind.ELEVEN -> choice.substringAfter('|', "").ifBlank { voiceIdOf(choice) }
    VoiceKind.GEMINI -> ""
}

internal fun elevenChoice(id: String, name: String): String = ELEVEN_PREFIX + id + "|" + name.replace("|", " ")

/**
 * Cuts Gemini's streamed transcript into sentences to read as they come, so the voice starts before the answer is over. A sentence
 * ends at . ! ? … or a line break followed by a space or the end; a very long run is cut at a comma or space after 220 characters.
 */
internal class SentenceBuffer(private val maxChars: Int = 220) {
    private val text = StringBuilder()

    /** Adds a piece of transcript; returns the sentences it completed. */
    fun add(piece: String): List<String> {
        text.append(piece)
        val out = mutableListOf<String>()
        while (true) {
            val end = sentenceEnd(text) ?: break
            out += text.substring(0, end).trim()
            text.delete(0, end)
        }
        while (text.length > maxChars) {
            val cut = text.lastIndexOf(", ", maxChars).takeIf { it > maxChars / 2 }?.plus(1)
                ?: text.lastIndexOf(" ", maxChars).takeIf { it > 0 } ?: maxChars
            out += text.substring(0, cut).trim()
            text.delete(0, cut)
        }
        return out.filter { it.any(Char::isLetterOrDigit) }
    }

    /** The rest, at the end of the turn. */
    fun flush(): String? = text.toString().trim().also { text.clear() }.takeIf { it.any(Char::isLetterOrDigit) }

    fun clear() = text.clear()

    private fun sentenceEnd(s: CharSequence): Int? {
        for (i in s.indices) {
            val c = s[i]
            val ends = c == '.' || c == '!' || c == '?' || c == '…' || c == '\n'
            // the end mark must be followed by a space: "3.5" or "M.Dupont" go on (a mark at the very end may still continue)
            if (ends && i + 1 < s.length && s[i + 1].isWhitespace()) {
                // "M. Dupont", "etc. ": a lone capital or a short abbreviation is not the end of a sentence
                val word = s.substring(0, i).takeLastWhile { it.isLetter() }
                if (c == '.' && (word.length == 1 || word.lowercase() in ABBREVIATIONS)) continue
                return i + 1
            }
        }
        return null
    }

    private companion object {
        val ABBREVIATIONS = setOf("m", "mme", "mlle", "dr", "st", "ste", "etc", "env", "cf", "ex", "av", "bd", "mr", "mrs", "ms", "vs", "no")
    }
}

/** The output rate of every voice here: 16-bit mono PCM at 24 kHz, as Gemini's own audio, so it plays through the same player. */
internal const val VOICE_SAMPLE_RATE = 24_000

/** Downmixes 16-bit PCM to mono and resamples it to 24 kHz (linear: enough for speech). */
internal fun toMono24k(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
    val frames = pcm.size / (2 * channels.coerceAtLeast(1))
    val mono = ShortArray(frames) { f ->
        var sum = 0
        for (c in 0 until channels) {
            val i = (f * channels + c) * 2
            sum += ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
        }
        (sum / channels.coerceAtLeast(1)).toShort()
    }
    val resampled = if (rate == VOICE_SAMPLE_RATE || rate <= 0 || mono.isEmpty()) mono else {
        val count = (mono.size.toLong() * VOICE_SAMPLE_RATE / rate).toInt()
        ShortArray(count) { j ->
            val pos = j.toDouble() * rate / VOICE_SAMPLE_RATE
            val a = pos.toInt().coerceAtMost(mono.size - 1)
            val b = (a + 1).coerceAtMost(mono.size - 1)
            val t = pos - a
            (mono[a] * (1 - t) + mono[b] * t).toInt().toShort()
        }
    }
    val out = ByteArray(resampled.size * 2)
    resampled.forEachIndexed { i, s ->
        out[2 * i] = (s.toInt() and 0xFF).toByte()
        out[2 * i + 1] = (s.toInt() shr 8).toByte()
    }
    return out
}

/* ---------- Microsoft Edge read-aloud (the protocol of the edge-tts package) ---------- */

internal const val EDGE_TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
internal const val EDGE_CHROMIUM_VERSION = "143.0.3650.75"
internal const val EDGE_WSS = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

/** Seconds between 1601-01-01 (Windows file time) and 1970-01-01. */
private const val WIN_EPOCH = 11_644_473_600L

/** The Sec-MS-GEC token: SHA-256 of the Windows file time rounded down to 5 minutes, then the trusted client token, in capitals. */
internal fun edgeSecMsGec(unixSeconds: Long): String {
    var seconds = unixSeconds + WIN_EPOCH
    seconds -= seconds % 300
    val ticks = seconds * 10_000_000L
    val digest = MessageDigest.getInstance("SHA-256").digest("$ticks$EDGE_TRUSTED_TOKEN".toByteArray(Charsets.US_ASCII))
    return digest.joinToString("") { "%02X".format(it) }
}

internal fun edgeUrl(unixSeconds: Long, connectionId: String): String =
    "$EDGE_WSS?TrustedClientToken=$EDGE_TRUSTED_TOKEN&ConnectionId=$connectionId" +
        "&Sec-MS-GEC=${edgeSecMsGec(unixSeconds)}&Sec-MS-GEC-Version=1-$EDGE_CHROMIUM_VERSION"

internal fun edgeHeaders(muid: String): Map<String, String> {
    val major = EDGE_CHROMIUM_VERSION.substringBefore('.')
    return linkedMapOf(
        "Pragma" to "no-cache",
        "Cache-Control" to "no-cache",
        "Origin" to "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36 Edg/$major.0.0.0",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cookie" to "muid=$muid;",
    )
}

private val EDGE_DATE = DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US).withZone(ZoneOffset.UTC)

internal fun edgeDate(unixSeconds: Long): String = EDGE_DATE.format(Instant.ofEpochSecond(unixSeconds))

/** The first message: MP3 at 24 kHz (the only formats this service gives), no word timings. */
internal fun edgeConfigMessage(unixSeconds: Long): String =
    "X-Timestamp:${edgeDate(unixSeconds)}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
        "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"

/** The text to read, as SSML in [voice] (a short name such as fr-FR-DeniseNeural). */
internal fun edgeSsmlMessage(requestId: String, unixSeconds: Long, voice: String, text: String, rate: String = "+0%", pitch: String = "+0Hz"): String {
    val clean = text.map { c -> if (c.code in 0..8 || c.code in 11..12 || c.code in 14..31) ' ' else c }.joinToString("")
    val escaped = clean.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return "X-RequestId:$requestId\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${edgeDate(unixSeconds)}Z\r\nPath:ssml\r\n\r\n" +
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'><voice name='$voice'>" +
        "<prosody pitch='$pitch' rate='$rate' volume='+0%'>$escaped</prosody></voice></speak>"
}

/** The audio of a binary message (2 bytes of header length, the header, then the MP3), or null when it carries none. */
internal fun edgeAudio(message: ByteArray): ByteArray? {
    if (message.size < 2) return null
    val headerLength = ((message[0].toInt() and 0xFF) shl 8) or (message[1].toInt() and 0xFF)
    if (headerLength + 2 > message.size) return null
    val header = String(message, 2, headerLength, Charsets.UTF_8)
    if (!header.contains("Path:audio")) return null
    return message.copyOfRange(headerLength + 2, message.size).takeIf { it.isNotEmpty() }
}

/** The Path of a text message ("turn.start", "audio.metadata", "turn.end"…). */
internal fun edgePath(message: String): String? =
    message.substringBefore("\r\n\r\n").split("\r\n").firstOrNull { it.startsWith("Path:") }?.removePrefix("Path:")?.trim()

/* ---------- ElevenLabs (the user's key) ---------- */

internal const val ELEVEN_API = "https://api.elevenlabs.io/v1"
internal const val ELEVEN_MODEL = "eleven_flash_v2_5"

/** The body of POST /v1/text-to-speech/{voice_id}/stream?output_format=pcm_24000. */
internal fun elevenRequest(text: String, languageCode: String? = "fr"): JsonObject = buildJsonObject {
    put("text", text)
    put("model_id", ELEVEN_MODEL)
    if (!languageCode.isNullOrBlank()) put("language_code", languageCode)
}

/** The voices of GET /v1/voices: id and name, with "female"/"male" from their labels when given. */
internal fun parseElevenVoices(root: JsonObject): List<OnlineVoice> =
    (root["voices"] as? JsonArray).orEmpty().mapNotNull { v ->
        val o = v as? JsonObject ?: return@mapNotNull null
        val id = o.str("voice_id") ?: return@mapNotNull null
        val name = o.str("name") ?: id
        val labels = o["labels"] as? JsonObject
        val gender = labels?.str("gender").orEmpty().lowercase()
        val accent = labels?.str("accent").orEmpty()
        OnlineVoice(elevenChoice(id, name), name, gender == "female", accent.ifBlank { "ElevenLabs" }, accent.ifBlank { "ElevenLabs" })
    }

/** A short French message for a failed ElevenLabs call. */
internal fun elevenError(code: Int): String = when (code) {
    401 -> "ElevenLabs refuse la clé (HTTP 401) : vérifiez-la dans Paramètres > Voix."
    402, 403 -> "ElevenLabs refuse (HTTP $code) : crédit épuisé ou voix réservée à un autre abonnement."
    404 -> "Cette voix ElevenLabs n’existe plus : choisissez-en une autre."
    429 -> "ElevenLabs est saturé ou la limite est atteinte (HTTP 429)."
    else -> "ElevenLabs indisponible (HTTP $code)."
}

/* ---------- the phone's own voices (offline) ---------- */

/** A voice of a speech engine installed on the phone: stored as "phone:<engine package>|<voice name>". */
internal data class PhoneVoice(val engine: String, val engineLabel: String, val name: String, val locale: String, val offline: Boolean, val quality: Int) {
    val id: String get() = "$PHONE_PREFIX$engine|$name"
}

internal fun phoneChoice(choice: String): Pair<String, String>? {
    if (!choice.startsWith(PHONE_PREFIX)) return null
    val rest = choice.removePrefix(PHONE_PREFIX)
    val engine = rest.substringBefore('|')
    val name = rest.substringAfter('|', "")
    return if (engine.isBlank() || name.isBlank()) null else engine to name
}

/** A readable name for an Android voice: "fr-fr-x-frd-local" → "FRD (fr-FR)", other engines keep their own name. */
internal fun phoneVoiceLabel(v: PhoneVoice): String {
    val local = Regex("^[a-z]{2,3}-[a-z]{2}-x-([a-z0-9]+)-(local|network)$").find(v.name.lowercase())
    val base = local?.groupValues?.get(1)?.uppercase() ?: v.name
    return "$base (${v.locale}) · ${v.engineLabel}"
}

/** The voices to offer: those that work without the network in the user's language first, best quality first. */
internal fun sortPhoneVoices(voices: List<PhoneVoice>, language: String): List<PhoneVoice> =
    voices.filter { it.offline }
        .sortedWith(compareByDescending<PhoneVoice> { it.locale.lowercase().startsWith(language.lowercase()) }.thenByDescending { it.quality }.thenBy { it.name })

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
