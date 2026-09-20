package com.jarvis.android.plugins

import com.jarvis.android.actions.ToolRegistry
import java.io.File

/** The plugin files of the app, one JSON per plugin, in its private storage. */
internal class PluginStore(private val dir: File) {
    /** Loads every valid plugin file and hands the result to the tool registry. Invalid files are skipped. */
    @Synchronized
    fun reload(): List<PluginTool> {
        val builtIn = ToolRegistry.builtInNames()
        val tools = (dir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name } ?: emptyList())
            .mapNotNull { file ->
                val parsed = try { parsePlugin(file.readText(), builtIn) } catch (_: Exception) { null }
                (parsed as? PluginParse.Ok)?.spec?.let { PluginTool(it) }
            }
            .distinctBy { it.name }
            .take(MAX_PLUGINS)
        ToolRegistry.setPlugins(tools)
        return tools
    }

    /** Checks [text] and saves it; returns null on success or a message for the user. */
    @Synchronized
    fun install(text: String): String? {
        val builtIn = ToolRegistry.builtInNames()
        return when (val parsed = parsePlugin(text, builtIn)) {
            is PluginParse.Error -> parsed.message
            is PluginParse.Ok -> {
                val existing = dir.listFiles { f -> f.extension == "json" }?.size ?: 0
                val target = File(dir, parsed.spec.name + ".json")
                if (!target.exists() && existing >= MAX_PLUGINS) return "$MAX_PLUGINS plugins au maximum : supprimez-en un d’abord."
                dir.mkdirs()
                target.writeText(text)
                reload()
                null
            }
        }
    }

    @Synchronized
    fun remove(name: String) {
        File(dir, "$name.json").delete()
        reload()
    }

    fun names(): List<String> = ToolRegistry.pluginTools().map { it.name }
}
