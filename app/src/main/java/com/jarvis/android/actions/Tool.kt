package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One callable skill. Mirrors the shape of Mark-LIII's `TOOL` dict (one per
 * file under `actions/`): a name + description + JSON-schema parameters the
 * model reads to decide when to call it, plus the handler that actually runs.
 *
 * Python can auto-discover a skill by dropping a `.py` file into `actions/` at
 * runtime; Android can't safely load and execute arbitrary code dropped onto
 * the device at runtime (that's arbitrary code execution, not a feature), so
 * the equivalent here is compile-time but still "one file, no core edits":
 * add a new object implementing [Tool] and register it once in
 * [ToolRegistry.ALL].
 */
interface Tool {
    val name: String
    val description: String

    /**
     * Gemini function-declaration JSON schema. Always carries an (possibly
     * empty) "properties" object — Mark-LIII's `_DEFAULT_PARAMS` does the same
     * (`{"type": "OBJECT", "properties": {}}`); omitting it is a malformed
     * declaration the Live API rejects at setup for the *whole session*, not
     * just this one tool.
     */
    val parameters: JsonObject
        get() = buildJsonObject {
            put("type", "OBJECT")
            putJsonObject("properties") {}
        }

    suspend fun run(args: JsonObject, ctx: JarvisContainer): String
}

fun JsonObject.stringArg(key: String, default: String = ""): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: default

fun JsonObject.intArg(key: String, default: Int = 0): Int =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: default
