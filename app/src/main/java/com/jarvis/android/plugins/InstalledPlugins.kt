package com.jarvis.android.plugins

import com.jarvis.android.tool.Tool

/** How many plugins are declared to the model as tools of their own; the others go through `plugin_run` (see PluginRunTool). */
internal const val MAX_DECLARED_PLUGINS = 25

/**
 * The user's plugins as tools, replaced as a whole whenever they change. The tool registry reads them from here, so this package
 * does not depend on the registry.
 */
internal object InstalledPlugins {
    @Volatile private var plugins: List<Tool> = emptyList()

    fun set(list: List<Tool>) {
        plugins = list
    }

    fun all(): List<Tool> = plugins

    /** The plugins declared to the model one by one, and the ones reached through `plugin_run`. */
    fun declared(): List<Tool> = plugins.take(MAX_DECLARED_PLUGINS)
    fun extra(): List<Tool> = plugins.drop(MAX_DECLARED_PLUGINS)
}
