package com.jarvis.android.plugins

import android.content.Context

/** A plugin shipped inside the app that the user can install with one tap. */
internal data class CatalogEntry(val fileName: String, val name: String, val description: String, val json: String)

/** Reads the bundled plugins (assets/plugins) and keeps the ones that pass the same checks as an imported file. */
internal fun readCatalog(context: Context, builtIn: Set<String>): List<CatalogEntry> =
    (context.assets.list("plugins") ?: emptyArray()).filter { it.endsWith(".json") }.sorted().mapNotNull { file ->
        val json = try {
            context.assets.open("plugins/$file").use { String(it.readBytes()) }
        } catch (_: Exception) {
            return@mapNotNull null
        }
        (parsePlugin(json, builtIn) as? PluginParse.Ok)?.spec?.let { CatalogEntry(file, it.name, it.description, json) }
    }
