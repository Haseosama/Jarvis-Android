package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Every built-in skill, in one place — the Android equivalent of Mark-LIII's
 * `core/action_loader.py` auto-discovery. See [Tool] for why this is a static
 * list instead of runtime file discovery.
 *
 * Some Mark-LIII actions have **no Android equivalent** and are deliberately
 * not ported — they depend on desktop-only APIs a sandboxed phone app cannot
 * reach: `computer_control`/`desktop.py` (mouse/keyboard automation, taskbar,
 * window management), `game_updater` (Steam/Epic), `flight_finder` (was tied
 * to the desktop's browser automation), `screen_processor` (full desktop
 * screen capture), and `dashboard/` (a *remote* control surface makes no
 * sense when the assistant already lives on your phone).
 */
object ToolRegistry {
    val ALL: List<Tool> = listOf(
        WebSearchTool,
        FlightSearchTool,
        WeatherTool,
        OpenAppTool,
        BrowserTool,
        ReminderTool,
        TimerTool,
        SystemMonitorTool,
        DeviceSettingsTool,
        SendMessageTool,
        YoutubeTool,
        ClipboardTool,
        CodeHelperTool,
        RecallMemoryTool,
        RememberTool,
        ForgetMemoryTool,
        UndoTool,
        EndSessionTool,
        ScreenReadTool,
        ScreenLookTool,
        ScreenTapTool,
        ScreenTypeTool,
        ScreenScrollTool,
        ScreenSwipeTool,
        ScreenNavigateTool,
        TakePhotoTool,
        AnalyzeFileTool,
        AgentTool,
        VisionStreamTool,
        FileManagerTool,
    )

    private val byName = ALL.associateBy { it.name }

    @Volatile private var plugins: List<Tool> = emptyList()

    /** The user's plugins (see the plugins package); replaced as a whole whenever they change. */
    internal fun setPlugins(list: List<Tool>) {
        plugins = list.filter { it.name !in byName }
    }

    internal fun pluginTools(): List<Tool> = plugins

    internal fun builtInNames(): Set<String> = byName.keys

    fun get(name: String): Tool? = byName[name] ?: plugins.firstOrNull { it.name == name }

    fun declarations(): List<JsonObject> = (ALL + plugins).map { tool ->
        buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", tool.parameters)
        }
    }

    /** Runs a built-in tool only (used by plugin routines, which must not start other plugins). */
    internal suspend fun runBuiltIn(name: String, args: JsonObject, ctx: JarvisContainer): String {
        val tool = byName[name] ?: return "Action '$name' is not available."
        return try {
            tool.run(args, ctx)
        } catch (e: Exception) {
            "Tool '$name' failed: ${e.message}"
        }
    }

    suspend fun run(name: String, args: JsonObject, ctx: JarvisContainer): String {
        val tool = get(name) ?: return "Action '$name' is not available."
        return try {
            tool.run(args, ctx)
        } catch (e: Exception) {
            "Tool '$name' failed: ${e.message}"
        }
    }
}
