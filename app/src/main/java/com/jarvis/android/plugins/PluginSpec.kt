package com.jarvis.android.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/** How many plugins can be installed (a sanity limit: the files are tiny). */
internal const val MAX_PLUGINS = 100
internal const val MAX_PLUGIN_BYTES = 20_000
internal const val MAX_PARAMS = 5
internal const val MAX_STEPS = 10
internal const val MAX_RESULT_CHARS = 1_500

private val NAME_PATTERN = Regex("^[a-z][a-z0-9_]{1,40}$")
private val PARAM_PATTERN = Regex("^[a-z][a-z0-9_]{0,30}$")
private val PLACEHOLDER = Regex("\\{([a-z][a-z0-9_]*)\\}")

/** A plugin may not start these: itself-like (agent), ending the session, or another plugin. */
internal val ROUTINE_FORBIDDEN_TOOLS = setOf("agent_task", "end_session")

internal data class PluginParam(val name: String, val description: String, val required: Boolean)
internal data class RoutineStep(val tool: String, val args: Map<String, String>)

internal sealed interface PluginAction {
    /** Calls a web address (HTTPS only) and returns the answer, or one value of it when [resultPath] is set. */
    data class Http(val method: String, val url: String, val body: String?, val resultPath: String?) : PluginAction

    /** Opens a web address or app link in the browser or the app that handles it. */
    data class Open(val url: String) : PluginAction

    /** Runs built-in tools one after the other. */
    data class Routine(val steps: List<RoutineStep>) : PluginAction
}

internal data class PluginSpec(
    val name: String,
    val description: String,
    val params: List<PluginParam>,
    val action: PluginAction,
)

internal sealed interface PluginParse {
    data class Ok(val spec: PluginSpec) : PluginParse
    data class Error(val message: String) : PluginParse
}

private fun fail(message: String) = PluginParse.Error(message)

/** Addresses a plugin must not reach: this phone, the local network, link-local and internal names. */
internal fun isForbiddenHost(host: String): Boolean {
    val h = host.lowercase()
    if (h == "localhost" || h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".localhost")) return true
    if (h.startsWith("[") || h.contains(':')) return true
    val parts = h.split('.')
    if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
        val a = parts[0].toInt()
        val b = parts[1].toInt()
        return a == 10 || a == 127 || a == 0 || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }
    return false
}

private fun placeholders(text: String): Set<String> = PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toSet()

private fun httpsHost(url: String): String? {
    // The address up to the first placeholder is what decides the host, so a placeholder in the host is refused.
    val match = Regex("^https://([^/?#{}]+)").find(url) ?: return null
    return match.groupValues[1].substringAfter('@').substringBefore(':').ifEmpty { null }
}

/**
 * Reads and checks a plugin file. [builtInTools] are the names of the tools a routine may call and
 * that a plugin may not take the name of.
 */
internal fun parsePlugin(text: String, builtInTools: Set<String>): PluginParse {
    if (text.length > MAX_PLUGIN_BYTES) return fail("Fichier trop gros ($MAX_PLUGIN_BYTES caractères maximum).")
    val root = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        return fail("Ce fichier n’est pas du JSON valide.")
    }
    fun str(key: String) = root[key]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    val name = str("name")
    if (!NAME_PATTERN.matches(name)) return fail("Nom invalide : minuscules, chiffres et _ (2 à 41 caractères, commence par une lettre).")
    if (name in builtInTools) return fail("Le nom « $name » est déjà pris par un outil intégré.")
    val description = str("description")
    if (description.length !in 10..500) return fail("La description doit faire de 10 à 500 caractères : c’est elle qui dit à l’assistant quand utiliser le plugin.")

    val paramsJson = try {
        root["parameters"]?.jsonArray ?: JsonArray(emptyList())
    } catch (_: Exception) {
        return fail("« parameters » doit être une liste.")
    }
    if (paramsJson.size > MAX_PARAMS) return fail("$MAX_PARAMS paramètres au maximum.")
    val params = paramsJson.map {
        val o = try { it.jsonObject } catch (_: Exception) { return fail("Chaque paramètre est un objet {name, description, required}.") }
        val pn = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!PARAM_PATTERN.matches(pn)) return fail("Nom de paramètre invalide : « $pn ».")
        PluginParam(pn, o["description"]?.jsonPrimitive?.contentOrNull.orEmpty().take(200), o["required"]?.jsonPrimitive?.booleanOrNull == true)
    }
    if (params.map { it.name }.toSet().size != params.size) return fail("Deux paramètres portent le même nom.")
    val declared = params.map { it.name }.toSet()

    fun checkPlaceholders(vararg texts: String?): String? {
        val unknown = texts.filterNotNull().flatMap { placeholders(it) }.filter { it !in declared }.toSet()
        return if (unknown.isEmpty()) null else "Paramètre(s) non déclaré(s) utilisé(s) : ${unknown.joinToString()}."
    }

    val action: PluginAction = when (val type = str("type").lowercase()) {
        "http" -> {
            val url = str("url")
            val host = httpsHost(url) ?: return fail("L’adresse doit commencer par https:// et l’hôte ne peut pas contenir de paramètre.")
            if (isForbiddenHost(host)) return fail("Adresse interdite : « $host » (réseau local ou interne).")
            val method = str("method").ifEmpty { "GET" }.uppercase()
            if (method != "GET" && method != "POST") return fail("Méthode GET ou POST seulement.")
            val body = root["body"]?.jsonPrimitive?.contentOrNull
            if (method == "GET" && body != null) return fail("Un GET n’a pas de corps.")
            checkPlaceholders(url, body)?.let { return fail(it) }
            PluginAction.Http(method, url, body, root["result_path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() })
        }
        "open" -> {
            val url = str("url")
            if (!(url.startsWith("https://") || url.startsWith("geo:") || url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("sms:"))) {
                return fail("Adresse à ouvrir : https://, geo:, tel:, mailto: ou sms: seulement.")
            }
            if (url.startsWith("https://")) {
                val host = httpsHost(url) ?: return fail("L’hôte ne peut pas contenir de paramètre.")
                if (isForbiddenHost(host)) return fail("Adresse interdite : « $host ».")
            }
            checkPlaceholders(url)?.let { return fail(it) }
            PluginAction.Open(url)
        }
        "routine" -> {
            val stepsJson = try { root["steps"]?.jsonArray } catch (_: Exception) { null }
                ?: return fail("Une routine a une liste « steps ».")
            if (stepsJson.isEmpty() || stepsJson.size > MAX_STEPS) return fail("Une routine a de 1 à $MAX_STEPS étapes.")
            val steps = stepsJson.map { el ->
                val o = try { el.jsonObject } catch (_: Exception) { return fail("Chaque étape est un objet {tool, args}.") }
                val tool = o["tool"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (tool !in builtInTools) return fail("Étape inconnue : l’outil « $tool » n’existe pas (seuls les outils intégrés sont permis).")
                if (tool in ROUTINE_FORBIDDEN_TOOLS) return fail("L’outil « $tool » ne peut pas être appelé par une routine.")
                val args = try {
                    (o["args"]?.jsonObject ?: JsonObject(emptyMap())).mapValues { (_, v) -> v.jsonPrimitive.content }
                } catch (_: Exception) { return fail("Les arguments d’une étape sont des textes ou des nombres.") }
                checkPlaceholders(*args.values.toTypedArray())?.let { return fail(it) }
                RoutineStep(tool, args)
            }
            PluginAction.Routine(steps)
        }
        else -> return fail("Type inconnu « $type » : http, open ou routine.")
    }
    return PluginParse.Ok(PluginSpec(name, description, params, action))
}

/** Fills {placeholders}; with [urlEncode] the values are percent-encoded, with [jsonEscape] they are safe inside a JSON string. */
internal fun render(template: String, args: Map<String, String>, urlEncode: Boolean = false, jsonEscape: Boolean = false): String =
    PLACEHOLDER.replace(template) { m ->
        val value = args[m.groupValues[1]].orEmpty()
        when {
            urlEncode -> URLEncoder.encode(value, "UTF-8").replace("+", "%20")
            jsonEscape -> JsonPrimitive(value).toString().removeSurrounding("\"")
            else -> value
        }
    }

/** The value at a dotted path such as "current.temp" or "items.0.name", or null when absent. */
internal fun jsonPath(json: String, path: String): String? {
    var current: JsonElement = try { Json.parseToJsonElement(json) } catch (_: Exception) { return null }
    for (part in path.split('.').filter { it.isNotEmpty() }) {
        current = when (current) {
            is JsonObject -> current[part] ?: return null
            is JsonArray -> part.toIntOrNull()?.let { current.getOrNull(it) } ?: return null
            else -> return null
        }
    }
    return when (current) {
        is JsonPrimitive -> current.content
        else -> current.toString()
    }
}
