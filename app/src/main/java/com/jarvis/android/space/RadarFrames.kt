package com.jarvis.android.space

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One picture of the rain radar: when, and where its tiles are. */
internal data class RadarFrame(val time: Long, val path: String, val forecast: Boolean)

/** RainViewer's list of radar pictures: the host, then the past ones and the few to come. */
internal fun parseRadarFrames(json: String): Pair<String, List<RadarFrame>>? = try {
    val o = Json.parseToJsonElement(json).jsonObject
    val host = o["host"]?.jsonPrimitive?.contentOrNull ?: return null
    val radar = o["radar"]?.jsonObject ?: return null
    fun list(k: String, forecast: Boolean) = (radar[k] as? JsonArray).orEmpty().mapNotNull { f ->
        val fo = f as? JsonObject ?: return@mapNotNull null
        val t = fo["time"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val p = fo["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        RadarFrame(t * 1000, p, forecast)
    }
    host to (list("past", false) + list("nowcast", true))
} catch (_: Exception) {
    null
}
