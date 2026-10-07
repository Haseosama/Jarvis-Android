package com.jarvis.android.connectors

import com.jarvis.android.JarvisContainer
import com.jarvis.android.tool.Tool
import kotlinx.serialization.json.JsonObject

/** How many connector tools are declared to the model one by one; the others go through the `connecteurs` tool (see ConnectorTool). */
internal const val MAX_DECLARED_CONNECTOR_TOOLS = 30

/** The tools of the user's connectors, replaced as a whole whenever they change. The tool registry reads them from here. */
internal object InstalledConnectors {
    @Volatile private var tools: List<McpTool> = emptyList()

    fun set(list: List<McpTool>) {
        tools = list
    }

    fun all(): List<McpTool> = tools
    fun declared(): List<McpTool> = tools.take(MAX_DECLARED_CONNECTOR_TOOLS)
    fun extra(): List<McpTool> = tools.drop(MAX_DECLARED_CONNECTOR_TOOLS)
}

/** One tool of a connector, offered to the model under [name] (the connector's id, then the tool's own name). */
internal class McpTool(
    val connectorId: String,
    val connectorName: String,
    val remote: RemoteTool,
    override val name: String,
) : Tool {
    override val description: String =
        "[Connecteur $connectorName] " + remote.description.trim().ifEmpty { remote.name }.let { if (it.length > 1000) it.take(1000) + "…" else it }

    override val parameters: JsonObject by lazy { geminiParameters(remote.inputSchema) }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = ctx.connectors.call(connectorId, remote, args)

    /** One line for the list of the `connecteurs` tool. */
    fun summary(): String = "$name : " + remote.description.trim().replace(Regex("\\s+"), " ").let { if (it.length > 120) it.take(120) + "…" else it }
}
