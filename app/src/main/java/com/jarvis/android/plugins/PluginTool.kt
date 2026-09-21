package com.jarvis.android.plugins

import android.content.Intent
import android.net.Uri
import com.jarvis.android.JarvisContainer
import com.jarvis.android.actions.Tool
import com.jarvis.android.actions.ToolRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A plugin, presented to the assistant like any built-in tool. */
internal class PluginTool(private val spec: PluginSpec) : Tool {
    override val name = spec.name
    override val description = spec.description
    override val parameters: JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            spec.params.forEach { p ->
                putJsonObject(p.name) {
                    put("type", "STRING")
                    put("description", p.description.ifBlank { p.name })
                }
            }
        }
        val required = spec.params.filter { it.required }.map { it.name }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }

    /** One line for the list handed to the assistant: `name(param*, other) : what it does`, a `*` marking a required parameter. */
    fun signature(): String =
        "$name(" + spec.params.joinToString(", ") { it.name + if (it.required) "*" else "" } + ") : " + spec.description.replace('\n', ' ').take(110)

    val summary: String
        get() = when (val a = spec.action) {
            is PluginAction.Http -> "appel web ${a.method} vers " + (Regex("^https://([^/?#]+)").find(a.url)?.groupValues?.get(1) ?: "?")
            is PluginAction.Open -> "ouvre un lien"
            is PluginAction.Routine -> "routine de ${a.steps.size} étape(s) : " + a.steps.joinToString(" → ") { it.tool }
        }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val values = spec.params.associate { it.name to (args[it.name]?.jsonPrimitive?.contentOrNull.orEmpty()) }
        val runner = PluginRunner(
            http = ctx.http,
            openLink = { url ->
                try {
                    ctx.appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (_: Exception) {
                    false
                }
            },
            // Routines call built-in tools only, so a plugin can never start another plugin.
            runTool = { tool, toolArgs -> ToolRegistry.runBuiltIn(tool, toolArgs, ctx) },
        )
        return runner.run(spec, values)
    }
}
