package com.jarvis.android.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * The phone side of the PC assistant's remote control: Jarvis 2.0's `electron/remoteServer.cjs`, which speaks the protocol of
 * Mark-LIV's remote dashboard (`dashboard/server.py`), so either PC assistant works. The protocol:
 *  - the PC shows a one-time 6-character key (valid 10 minutes) and a QR code of `https://<ip>:8000/auto-login?key=<key>`;
 *    that page hands back an auth token, the key, and a device token that gets a fresh auth token later
 *    (POST /api/device-login) without a new key (Jarvis 2.0 keeps it across restarts, Mark-LIV forgets it when it restarts);
 *  - a command is POST /api/command {"enc": base64(IV ‖ AES-256-CBC(text))}, the AES key being SHA-256(key ‖ "JARVIS-DASHBOARD-v1");
 *  - its answers come back on the /ws?token= WebSocket as {"type":"log","speaker":"jarvis","text":…};
 *  - Jarvis 2.0 (2.0.34+) makes pictures with Fooocus, ComfyUI or Forge on the PC: POST /api/image {"enc": …}, the request as JSON,
 *    the answer {"ok", "text", "png": base64};
 *  - Jarvis 2.0 (2.0.33+) also drives a browser with Playwright: POST /api/browser {"enc": …} with the action as JSON, the answer
 *    {"ok", "text", "jpegBase64"?} coming straight back.
 * The PC serves HTTPS with a certificate it made itself, so the phone pins that certificate's SHA-256 at pairing time and
 * then trusts nothing else. No Android imports here, so it can be unit-tested on the JVM.
 */

const val PC_DEFAULT_PORT = 8000
private const val AES_SALT = "JARVIS-DASHBOARD-v1"
private val KEY = Regex("^[A-Z0-9]{6}$")

/** A paired PC: where it is, the certificate it must present, and what Jarvis PC gave the phone at pairing. */
data class PcPairing(
    val baseUrl: String,
    val certSha256: String,
    val sessionKey: String,
    val token: String,
    val deviceToken: String,
) {
    fun toJson(): String = buildJsonObject {
        put("base", baseUrl); put("cert", certSha256); put("key", sessionKey); put("token", token); put("device", deviceToken)
    }.toString()

    companion object {
        fun fromJson(text: String?): PcPairing? = try {
            val o = Json.parseToJsonElement(text ?: return null).jsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            PcPairing(s("base"), s("cert"), s("key"), s("token"), s("device")).takeIf { it.baseUrl.isNotEmpty() && it.deviceToken.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}

/** Where to ask for pairing: the PC's base URL and the one-time key. */
data class PairingTarget(val baseUrl: String, val key: String) {
    val autoLoginUrl get() = "$baseUrl/auto-login?key=$key"
}

/** "ab 12 cd" → "AB12CD"; null unless it is a 6-letter/digit key. */
fun normalizeKey(raw: String): String? = raw.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }.takeIf { KEY.matches(it) }

/**
 * The pairing target from what the user gave: the QR code's whole link (the key inside it), or an address
 * ("192.168.1.20", "192.168.1.20:8001", "https://pc.local:8000") plus the key shown on the PC. HTTPS and port 8000 unless said.
 */
fun pairingTarget(address: String, code: String = ""): PairingTarget? {
    var text = address.trim()
    if (text.isEmpty()) return null
    if (!text.contains("://")) text = "https://$text"
    val scheme = text.substringBefore("://").lowercase(Locale.ROOT)
    if (scheme != "https" && scheme != "http") return null
    val rest = text.substringAfter("://")
    val authority = rest.substringBefore('/').substringBefore('?')
    if (authority.isEmpty() || authority.any { it.isWhitespace() }) return null
    val host = authority.substringBeforeLast(':').takeIf { authority.contains(':') && !authority.endsWith("]") } ?: authority
    val port = if (host == authority) PC_DEFAULT_PORT else authority.substringAfterLast(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    if (host.isEmpty()) return null
    val queryKey = Regex("[?&]key=([^&#]+)").find(rest)?.groupValues?.get(1).orEmpty()
    val key = normalizeKey(code.ifBlank { queryKey }) ?: return null
    return PairingTarget("$scheme://$host:$port", key)
}

/** What /auto-login answered: the auth token, the session key, the device token; null for the expired-link page. */
data class AutoLogin(val token: String, val sessionKey: String, val deviceToken: String)

fun parseAutoLogin(html: String): AutoLogin? {
    fun item(name: String) = Regex("""setItem\(\s*'$name'\s*,\s*'([^']+)'\s*\)""").find(html)?.groupValues?.get(1)
    return AutoLogin(item("jarvis_token") ?: return null, item("jarvis_key") ?: return null, item("jarvis_device_token") ?: return null)
}

fun aesKey(sessionKey: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest((sessionKey + AES_SALT).toByteArray(Charsets.UTF_8))

/** The `enc` field Jarvis PC decrypts: base64(IV ‖ AES-256-CBC/PKCS7(text)). */
fun encryptCommand(sessionKey: String, text: String, iv: ByteArray): String {
    require(iv.size == 16)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey(sessionKey), "AES"), IvParameterSpec(iv))
    return Base64.getEncoder().encodeToString(iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
}

fun commandBody(sessionKey: String, text: String, iv: ByteArray): String =
    buildJsonObject { put("enc", encryptCommand(sessionKey, text, iv)) }.toString()

/** An action for the PC's Playwright browser, encrypted like a command. */
fun browserBody(sessionKey: String, action: JsonObject, iv: ByteArray): String = commandBody(sessionKey, action.toString(), iv)

/** What the PC's browser answered: a text for the model (the page read, or why it failed), and the JPEG for a `look`. */
class PcBrowserReply(val ok: Boolean, val text: String, val jpeg: ByteArray? = null)

fun parseBrowserReply(text: String): PcBrowserReply? = try {
    val o = Json.parseToJsonElement(text).jsonObject
    val said = (o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val jpeg = (o["jpegBase64"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    PcBrowserReply((o["ok"] as? JsonPrimitive)?.contentOrNull == "true", said, jpeg?.takeIf { it.isNotEmpty() })
        .takeIf { said.isNotEmpty() || it.jpeg != null }
} catch (_: Exception) {
    null
}

/** What the PC's image generator answered (Jarvis 2.0, electron/imageGen.cjs): a sentence, and the PNG when one was made. */
class PcImageReply(val ok: Boolean, val text: String, val png: ByteArray? = null)

fun parseImageReply(text: String): PcImageReply? = try {
    val o = Json.parseToJsonElement(text).jsonObject
    val said = (o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val png = (o["png"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    PcImageReply((o["ok"] as? JsonPrimitive)?.contentOrNull == "true", said, png?.takeIf { it.isNotEmpty() })
        .takeIf { said.isNotEmpty() || it.png != null }
} catch (_: Exception) {
    null
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** One message of the dashboard WebSocket that matters here. */
sealed interface PcEvent {
    data class Said(val text: String) : PcEvent
    data class State(val state: String) : PcEvent
}

fun parsePcEvent(text: String): PcEvent? = try {
    val o: JsonObject = Json.parseToJsonElement(text).jsonObject
    fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    when (s("type")) {
        "log" -> if (s("speaker") == "jarvis" && s("text").isNotBlank()) PcEvent.Said(s("text").trim()) else null
        "status" -> s("state").takeIf { it.isNotEmpty() }?.let { PcEvent.State(it) }
        else -> null
    }
} catch (_: Exception) {
    null
}

/** Jarvis PC's answer to one command, from what it said after the command was sent (a short "je m'en occupe" then the result). */
fun joinAnswer(parts: List<String>): String = parts.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" ")

/** /api/device-login's answer: the new auth token and the session key it goes with (null when absent). */
fun parseDeviceLogin(body: String): Pair<String, String?>? = try {
    val o = Json.parseToJsonElement(body).jsonObject
    val token = (o["token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    token?.let { it to (o["key"] as? JsonPrimitive)?.contentOrNull?.takeIf { k -> k.isNotEmpty() } }
} catch (_: Exception) {
    null
}
