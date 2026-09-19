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
    )

    private val byName = ALL.associateBy { it.name }

    fun get(name: String): Tool? = byName[name]

    fun declarations(): List<JsonObject> = ALL.map { tool ->
        buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", tool.parameters)
        }
    }

    suspend fun run(name: String, args: JsonObject, ctx: JarvisContainer): String {
        val tool = byName[name] ?: return "Action '$name' is not available."
        return try {
            tool.run(args, ctx)
        } catch (e: Exception) {
            "Tool '$name' failed: ${e.message}"
        }
    }
}
