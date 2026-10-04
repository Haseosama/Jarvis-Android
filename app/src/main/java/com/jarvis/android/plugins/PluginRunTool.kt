package com.jarvis.android.plugins

import com.jarvis.android.JarvisContainer
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

import kotlinx.serialization.json.JsonPrimitive as Primitive


/** The arguments of a plugin as the model writes them, a JSON object of plain values; null when it is not one. Empty text means none. */
internal fun parsePluginArguments(text: String): JsonObject? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return JsonObject(emptyMap())
    val element = try { Json.parseToJsonElement(trimmed) } catch (_: Exception) { return null }
    val obj = element as? JsonObject ?: return null
    return JsonObject(obj.filterValues { it is Primitive })
}

/**
 * Runs a plugin that has no tool of its own. Every installed plugin is usable, but only the first [MAX_DECLARED_PLUGINS]
 * are declared to the model one by one (a very long list of tool declarations weighs on every session, and one the service refuses would
 * break the whole session); the others are listed in this tool's description, and the assistant calls them through it.
 */
internal object PluginRunTool : Tool {
    override val name = "plugin_run"
    override val description: String
        get() = "Lancer un plugin de l’utilisateur qui n’a pas d’outil à lui. Donner son nom et ses paramètres en JSON, par exemple {\"ville\": \"Paris\"}. " +
            "Plugins disponibles, sous la forme nom(paramètres, * = obligatoire) : description — " +
            InstalledPlugins.extra().joinToString(" | ") { (it as? PluginTool)?.signature() ?: it.name }
    override val parameters = objectSchema(required = listOf("plugin")) {
        string("plugin", "Nom exact du plugin.")
        string("arguments", "Ses paramètres, en JSON : {\"nom\": \"valeur\"}. Vide si le plugin n’en a pas.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val wanted = args.stringArg("plugin").trim()
        val target = InstalledPlugins.all().firstOrNull { it.name == wanted }
            ?: return "Plugin inconnu : « $wanted ». Plugins : " + InstalledPlugins.all().joinToString { it.name }
        val parsed = parsePluginArguments(args.stringArg("arguments"))
            ?: return "Les arguments doivent être un objet JSON de valeurs simples, par exemple {\"ville\": \"Paris\"}."
        return target.run(parsed, ctx)
    }
}
