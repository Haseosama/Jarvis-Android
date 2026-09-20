package com.jarvis.android.watch

import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale

internal const val KIND_CRYPTO = "crypto"
internal const val KIND_SITE = "site"
internal const val KIND_TEMPERATURE = "temperature"
internal const val KIND_MEMORY = "memory"
internal val WATCH_KINDS = listOf(KIND_CRYPTO, KIND_SITE, KIND_TEMPERATURE, KIND_MEMORY)
internal const val MAX_WATCHES = 20

/**
 * One thing kept an eye on in the background. crypto: the price in euros of coin [target] (a CoinGecko id such as "bitcoin"),
 * [above] the [threshold] or below it. site: the address [target], alerted when it goes down and when it is back. temperature: the
 * battery in °C. memory: the free memory in MB (alert below [threshold]).
 * [triggered] remembers that the alert has been sent, so it is not repeated until the condition has cleared.
 */
@Serializable
internal data class Watch(
    val id: Int,
    val kind: String,
    val target: String = "",
    val threshold: Double = 0.0,
    val above: Boolean = true,
    val label: String = "",
    val triggered: Boolean = false,
    val lastValue: Double? = null,
    val lastCheck: Long = 0L,
)

internal fun Watch.title(): String = label.ifBlank {
    when (kind) {
        KIND_CRYPTO -> target
        KIND_SITE -> target
        KIND_TEMPERATURE -> "Température de la batterie"
        else -> "Mémoire libre"
    }
}

/** What a check found: an alert to show (or none) and the watch as it should be kept. */
internal data class WatchResult(val alert: String?, val watch: Watch)

private fun number(v: Double): String =
    if (v >= 100 || v == Math.floor(v)) String.format(Locale.FRANCE, "%,.0f", v) else String.format(Locale.FRANCE, "%.2f", v)

/**
 * Compares a fresh [value] with the watch and says whether to alert. Prices, temperatures and memory alert once when the condition
 * starts and again only after it has cleared (by a small margin, so a value hovering at the threshold does not ring every check).
 * For a site, [value] is 1.0 when it answers and 0.0 when it does not: an alert when it goes down and another when it is back.
 * A null value (the check itself failed) changes nothing.
 */
internal fun evaluateWatch(watch: Watch, value: Double?, now: Long): WatchResult {
    if (value == null) return WatchResult(null, watch.copy(lastCheck = now))
    val checked = watch.copy(lastValue = value, lastCheck = now)
    if (watch.kind == KIND_SITE) {
        val down = value < 0.5
        return when {
            down && !watch.triggered -> WatchResult(trf("{0} ne répond plus.", watch.title()), checked.copy(triggered = true))
            !down && watch.triggered -> WatchResult(trf("{0} répond de nouveau.", watch.title()), checked.copy(triggered = false))
            else -> WatchResult(null, checked)
        }
    }
    val margin = Math.abs(watch.threshold) * 0.01
    val met = if (watch.above) value >= watch.threshold else value <= watch.threshold
    val clear = if (watch.above) value < watch.threshold - margin else value > watch.threshold + margin
    return when {
        met && !watch.triggered -> WatchResult(alertText(watch, value), checked.copy(triggered = true))
        clear && watch.triggered -> WatchResult(null, checked.copy(triggered = false))
        else -> WatchResult(null, checked)
    }
}

private fun alertText(watch: Watch, value: Double): String {
    val direction = if (watch.above) tr("au-dessus de") else tr("en dessous de")
    return when (watch.kind) {
        KIND_CRYPTO -> trf("{0} : {1} € ({2} {3} €).", watch.title(), number(value), direction, number(watch.threshold))
        KIND_TEMPERATURE -> trf("La batterie est à {0} °C ({1} {2} °C).", number(value), direction, number(watch.threshold))
        else -> trf("Il ne reste que {0} Mo de mémoire libre (seuil : {1} Mo).", number(value), number(watch.threshold))
    }
}

/** The price in euros of [coin] in a CoinGecko `simple/price` answer, or null. */
internal fun parseCoinPrice(json: String, coin: String): Double? = try {
    Json.parseToJsonElement(json).jsonObject[coin]?.jsonObject?.get("eur")?.jsonPrimitive?.doubleOrNull
} catch (_: Exception) {
    null
}

/** Whether an HTTP status means the site is up (a 401 or 403 still answers). */
internal fun statusMeansUp(code: Int): Boolean = code in 200..399 || code == 401 || code == 403

private val COIN_ID = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
internal fun validCoinId(id: String) = COIN_ID.matches(id)

/** A site address the watch can call: http(s) only. Returns the normalised address or null. */
internal fun normaliseSite(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty() || text.length > 300 || text.any { it.isWhitespace() }) return null
    val withScheme = if (text.startsWith("http://", true) || text.startsWith("https://", true)) text else "https://$text"
    val host = withScheme.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
    return if (host.contains('.') && host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) withScheme else null
}

/** The watches, kept in a small JSON file. */
internal class WatchStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun load(): List<Watch> = synchronized(lock) {
        try {
            if (file.isFile) json.decodeFromString<List<Watch>>(file.readText()) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(list: List<Watch>) = synchronized(lock) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Watch.serializer()), list))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    /** Adds [watch] (its id is set here). Fails when there are already [MAX_WATCHES]. */
    fun add(watch: Watch): Watch = synchronized(lock) {
        val list = load()
        require(list.size < MAX_WATCHES) { "Il y a déjà $MAX_WATCHES surveillances : supprimez-en une avant d’en ajouter." }
        val created = watch.copy(id = (list.maxOfOrNull { it.id } ?: 0) + 1)
        save(list + created)
        created
    }

    fun remove(id: Int): Boolean = synchronized(lock) {
        val list = load()
        val kept = list.filter { it.id != id }
        if (kept.size != list.size) save(kept)
        kept.size != list.size
    }

    fun replace(updated: List<Watch>) = synchronized(lock) {
        val byId = updated.associateBy { it.id }
        // Keep watches added or removed while a check was running.
        save(load().map { byId[it.id] ?: it })
    }
}

@Suppress("unused")
private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
