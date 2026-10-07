package com.jarvis.android.plugins

import com.jarvis.android.JarvisContainer
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.IOException

/**
 * The address of a plugin file as it can be downloaded: a GitHub page ("github.com/owner/repo/blob/branch/path.json") becomes its
 * raw file; anything but https, or aimed at this phone or the local network, is refused (null).
 */
internal fun pluginDownloadUrl(link: String): String? {
    val url = link.trim()
    if (!url.startsWith("https://", ignoreCase = true) || url.any { it.isWhitespace() }) return null
    val u = try { java.net.URI(url) } catch (_: Exception) { return null }
    val host = u.host?.lowercase() ?: return null
    if (u.rawUserInfo != null || isForbiddenHost(host)) return null
    val blob = Regex("^https://github\\.com/([^/]+)/([^/]+)/blob/(.+)$", RegexOption.IGNORE_CASE).find(url)
    return blob?.let { "https://raw.githubusercontent.com/${it.groupValues[1]}/${it.groupValues[2]}/${it.groupValues[3]}" } ?: url
}

/**
 * The user's plugins by voice: « crée un plugin qui… » (the assistant writes the file, it is checked and tried, then installed once the
 * user confirms), a plugin taken from a link, the list, and removing one. Every install and removal is confirmed on screen: a plugin
 * is a skill the assistant will then use by itself.
 */
object PluginManageTool : Tool {
    override val name = "plugin_manage"
    override val description =
        "Gérer les plugins (des compétences sans code). action « create » : écrire un plugin pour ce que l’utilisateur demande (« crée un " +
            "plugin qui me donne… ») : plugin = le fichier JSON {name (minuscules, chiffres, _), description (10 à 500 caractères : quand " +
            "l’utiliser), parameters [{name, description, required}], type : \"http\" (url https avec {paramètres}, method GET/POST, body, " +
            "result_path \"a.b\" pour une valeur, ou result_fields [\"a\",\"b.c\"] et result_items \"liste\" + result_max pour une liste), " +
            "\"open\" (url https/geo/tel à ouvrir) ou \"routine\" (steps [{tool, args}] d’outils intégrés)} ; utiliser un service web gratuit " +
            "SANS clé ; test = un objet JSON de paramètres pour l’essayer. Il est vérifié, essayé (http), montré à l’utilisateur qui confirme, " +
            "puis installé (utilisable dès la session suivante ; le résultat de l’essai répond déjà à la demande). « import » : url d’un fichier " +
            "de plugin (lien GitHub accepté). « list » ; « remove » : name."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "create, import, list ou remove.")
        string("plugin", "Pour create : le plugin, en JSON.")
        string("test", "Pour create : les paramètres d’essai, en objet JSON, par exemple {\"ville\": \"Brest\"}.")
        string("url", "Pour import : l’adresse https du fichier du plugin.")
        string("name", "Pour remove : le nom du plugin.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = when (args.stringArg("action").trim().lowercase()) {
        "create", "creer", "créer" -> create(ctx, args.stringArg("plugin"), args.stringArg("test"))
        "import", "importer" -> import(ctx, args.stringArg("url"))
        "remove", "delete", "supprimer" -> {
            val name = args.stringArg("name").trim()
            if (name !in ctx.pluginStore.names()) "Aucun plugin installé ne s’appelle « $name »."
            else if (!confirm(ctx, "Désinstaller un plugin", "Retirer le plugin « $name » ?")) "Rien n’a été retiré."
            else { ctx.pluginStore.remove(name); "Plugin « $name » retiré." }
        }
        "list" -> InstalledPlugins.all().filterIsInstance<PluginTool>().let { list ->
            if (list.isEmpty()) "Aucun plugin installé." else list.sortedBy { it.name }.joinToString("\n", prefix = "Plugins installés (${list.size}) :\n") { "- ${it.name} : ${it.summary}" }
        }
        else -> "Action inconnue."
    }

    private suspend fun create(ctx: JarvisContainer, text: String, test: String): String {
        if (text.isBlank()) return "Écrivez le plugin (JSON) dans « plugin »."
        val spec = when (val parsed = parsePlugin(text, ctx.pluginStore.builtInNames())) {
            is PluginParse.Error -> return "Plugin refusé : ${parsed.message} Corrigez-le et réessayez."
            is PluginParse.Ok -> parsed.spec
        }
        val tool = PluginTool(spec)
        // an http plugin is tried for real (a link or a routine would act at once: they are only shown)
        val tried = if (spec.action is PluginAction.Http) {
            val testArgs = try {
                if (test.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(test).jsonObject
            } catch (_: Exception) {
                return "Les paramètres d’essai (test) doivent être un objet JSON, par exemple {\"ville\": \"Brest\"}."
            }
            tool.run(testArgs, ctx).also { r ->
                if (r.startsWith("Il manque") || r.startsWith("Le service") || r.startsWith("Adresse invalide") || r.startsWith("La réponse ne contient") || r == "Aucun résultat." || r == "Réponse vide.") {
                    return "L’essai a échoué : $r Corrigez le plugin (adresse, champs) et réessayez ; rien n’est installé."
                }
            }
        } else null
        val replaces = spec.name in ctx.pluginStore.names()
        val detail = "« ${spec.name} » : ${tool.summary}." + (tried?.let { " Essai : " + it.substringBefore(DATA_NOTE).take(220) } ?: "") +
            if (replaces) " (remplace le plugin du même nom)" else ""
        if (!confirm(ctx, "Installer un plugin", detail)) return "Plugin non installé." + (tried?.let { " Résultat de l’essai : $it" } ?: "")
        ctx.pluginStore.install(text)?.let { return "Installation impossible : $it" }
        return "Plugin « ${spec.name} » installé : il sera proposé dès la prochaine session vocale." + (tried?.let { " Résultat de l’essai, pour répondre maintenant : $it" } ?: "")
    }

    private suspend fun import(ctx: JarvisContainer, link: String): String {
        val url = pluginDownloadUrl(link) ?: return "Adresse refusée : un lien https vers un fichier de plugin (pas le réseau local)."
        val text = try { download(ctx, url) } catch (_: IOException) { return "Le fichier est inaccessible à cette adresse." }
            ?: return "Fichier trop gros pour un plugin (${MAX_PLUGIN_BYTES} caractères au plus)."
        val spec = when (val parsed = parsePlugin(text, ctx.pluginStore.builtInNames())) {
            is PluginParse.Error -> return "Ce fichier n’est pas un plugin valable : ${parsed.message}"
            is PluginParse.Ok -> parsed.spec
        }
        val detail = "« ${spec.name} » (${PluginTool(spec).summary}) : ${spec.description.take(200)}"
        if (!confirm(ctx, "Installer un plugin", detail)) return "Plugin non installé."
        return ctx.pluginStore.install(text)?.let { "Installation impossible : $it" } ?: "Plugin « ${spec.name} » installé : il sera proposé dès la prochaine session vocale."
    }

    /** The text at [url], or null when it is larger than a plugin may be. */
    internal suspend fun download(ctx: JarvisContainer, url: String): String? = withContext(Dispatchers.IO) {
        ctx.http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-Android").build()).execute().use { r ->
            if (!r.isSuccessful || !r.request.url.isHttps) throw IOException("HTTP ${r.code}")
            val source = r.body?.source() ?: throw IOException("empty")
            source.request(MAX_PLUGIN_BYTES + 1L)
            if (source.buffer.size > MAX_PLUGIN_BYTES) null else source.buffer.readUtf8()
        }
    }

    /** Asked only when the user turned confirmations back on: a plugin is a skill the assistant will use by itself. */
    private suspend fun confirm(ctx: JarvisContainer, label: String, detail: String): Boolean = ctx.skipConfirmations || try {
        withTimeout(60_000L) { ctx.confirmManager.request(label, detail) }
    } catch (_: TimeoutCancellationException) {
        false
    }
}
