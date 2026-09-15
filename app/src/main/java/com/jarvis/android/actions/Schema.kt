package com.jarvis.android.actions

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Small builder for Gemini function-declaration parameter schemas. */
fun objectSchema(required: List<String> = emptyList(), build: PropsBuilder.() -> Unit): JsonObject =
    buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("OBJECT"))
        putJsonObject("properties") {
            val pb = PropsBuilder(this)
            pb.build()
        }
        if (required.isNotEmpty()) {
            putJsonArray("required") { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }
    }

class PropsBuilder(private val obj: kotlinx.serialization.json.JsonObjectBuilder) {
    fun string(name: String, description: String) {
        obj.putJsonObject(name) {
            put("type", kotlinx.serialization.json.JsonPrimitive("STRING"))
            put("description", kotlinx.serialization.json.JsonPrimitive(description))
        }
    }
    fun integer(name: String, description: String) {
        obj.putJsonObject(name) {
            put("type", kotlinx.serialization.json.JsonPrimitive("INTEGER"))
            put("description", kotlinx.serialization.json.JsonPrimitive(description))
        }
    }
}
