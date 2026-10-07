package com.jarvis.android.registry

import com.jarvis.android.JarvisContainer
import com.jarvis.android.plugins.InstalledPlugins
import com.jarvis.android.plugins.MAX_DECLARED_PLUGINS
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.jarvis.android.tool.Tool
import com.jarvis.android.actions.AgentTool
import com.jarvis.android.actions.AirQualityTool
import com.jarvis.android.actions.TidesTool
import com.jarvis.android.actions.PcControlTool
import com.jarvis.android.actions.AlarmTool
import com.jarvis.android.actions.AnalyzeFileTool
import com.jarvis.android.actions.BirthdaysTool
import com.jarvis.android.actions.BrowserTool
import com.jarvis.android.actions.BudgetTool
import com.jarvis.android.actions.CalendarTool
import com.jarvis.android.actions.CallLogTool
import com.jarvis.android.actions.ChangeVoiceTool
import com.jarvis.android.actions.ClipboardTool
import com.jarvis.android.actions.CodeHelperTool
import com.jarvis.android.actions.ContactTool
import com.jarvis.android.actions.DeviceSettingsTool
import com.jarvis.android.actions.DocumentTool
import com.jarvis.android.actions.DriveTool
import com.jarvis.android.actions.DrivingModeTool
import com.jarvis.android.actions.EndSessionTool
import com.jarvis.android.actions.ExpensesTool
import com.jarvis.android.actions.FileManagerTool
import com.jarvis.android.actions.FindPhoneTool
import com.jarvis.android.actions.FlightSearchTool
import com.jarvis.android.actions.ForgetMemoryTool
import com.jarvis.android.actions.FuelPriceTool
import com.jarvis.android.actions.GmailTool
import com.jarvis.android.actions.HabitsTool
import com.jarvis.android.actions.HealthTool
import com.jarvis.android.actions.InterpreterTool
import com.jarvis.android.actions.LibertyMusicTool
import com.jarvis.android.actions.MailCleanupTool
import com.jarvis.android.actions.MeetingTool
import com.jarvis.android.actions.NotificationsTool
import com.jarvis.android.actions.ObsidianTool
import com.jarvis.android.actions.OpenAppTool
import com.jarvis.android.actions.ParcelTool
import com.jarvis.android.actions.ParkingTool
import com.jarvis.android.actions.PersonReminderTool
import com.jarvis.android.actions.PhotoSearchTool
import com.jarvis.android.actions.PlaceReminderTool
import com.jarvis.android.actions.PlanesOverheadTool
import com.jarvis.android.actions.PlayVideoTool
import com.jarvis.android.actions.QuietModeTool
import com.jarvis.android.actions.RadioAlarmTool
import com.jarvis.android.actions.RadioTool
import com.jarvis.android.actions.RainSoonTool
import com.jarvis.android.actions.ReadTextTool
import com.jarvis.android.actions.ReadWebpageTool
import com.jarvis.android.actions.RecallMemoryTool
import com.jarvis.android.actions.ReceiptTool
import com.jarvis.android.actions.RecipeTool
import com.jarvis.android.actions.RememberTool
import com.jarvis.android.actions.ReminderTool
import com.jarvis.android.actions.RoutineTool
import com.jarvis.android.actions.ScreenLookTool
import com.jarvis.android.actions.ScreenNavigateTool
import com.jarvis.android.actions.ScreenReadTool
import com.jarvis.android.actions.ScreenScrollTool
import com.jarvis.android.actions.ScreenSwipeTool
import com.jarvis.android.actions.ScreenTapTool
import com.jarvis.android.actions.ScreenTypeTool
import com.jarvis.android.actions.SendMessageTool
import com.jarvis.android.actions.SmartHomeTool
import com.jarvis.android.actions.SosTool
import com.jarvis.android.actions.SpotifyTool
import com.jarvis.android.actions.SubscriptionsTool
import com.jarvis.android.actions.SystemMonitorTool
import com.jarvis.android.actions.TakePhotoTool
import com.jarvis.android.actions.TaskListTool
import com.jarvis.android.actions.TimerTool
import com.jarvis.android.actions.TranslateTool
import com.jarvis.android.actions.TransportTool
import com.jarvis.android.actions.UndoTool
import com.jarvis.android.actions.VisionStreamTool
import com.jarvis.android.actions.WakeBriefingTool
import com.jarvis.android.actions.WatchTool
import com.jarvis.android.actions.WeatherTool
import com.jarvis.android.actions.WebSearchTool
import com.jarvis.android.actions.YoutubeTool

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
 * screen capture). On the PC those are reached through Jarvis PC instead:
 * `jarvis_pc` (PcControlTool) is a client of its `dashboard/`.
 */
object ToolRegistry {
    val ALL: List<Tool> = listOf(
        WebSearchTool,
        ReadWebpageTool,
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
        ChangeVoiceTool,
        ObsidianTool,
        PlayVideoTool,
        RadioTool,
        RadioAlarmTool,
        com.jarvis.android.space.SatelliteTool,
        com.jarvis.android.space.SkyViewTool,
        com.jarvis.android.space.NightSkyTool,
        com.jarvis.android.space.FlightTool,
        com.jarvis.android.space.RainRadarTool,
        com.jarvis.android.space.LaunchTool,
        com.jarvis.android.space.AuroraTool,
        com.jarvis.android.space.MyFlightsTool,
        com.jarvis.android.driving.RouteWeatherTool,
        com.jarvis.android.space.QuakeTool,
        com.jarvis.android.space.SkyEventsTool,
        com.jarvis.android.space.ObservingTool,
        com.jarvis.android.weather.SunUvTool,
        com.jarvis.android.weather.VigilanceTool,
        com.jarvis.android.energy.EnergyTool,
        com.jarvis.android.transport.MyTripsTool,
        com.jarvis.android.weekly.WeeklySummaryTool,
        com.jarvis.android.recalls.RecallsTool,
        com.jarvis.android.outages.OutagesTool,
        com.jarvis.android.trash.TrashTool,
        com.jarvis.android.podcasts.PodcastTool,
        com.jarvis.android.plugins.PluginManageTool,
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
        RoutineTool,
        LibertyMusicTool,
        AlarmTool,
        ContactTool,
        CalendarTool,
        NotificationsTool,
        VisionStreamTool,
        FileManagerTool,
        DocumentTool,
        MeetingTool,
        WatchTool,
        GmailTool,
        MailCleanupTool,
        DriveTool,
        TaskListTool,
        SmartHomeTool,
        TranslateTool,
        SpotifyTool,
        AirQualityTool,
        PlanesOverheadTool,
        ExpensesTool,
        HabitsTool,
        FindPhoneTool,
        PlaceReminderTool,
        FuelPriceTool,
        com.jarvis.android.nearby.NearbyTool,
        ParkingTool,
        RainSoonTool,
        BirthdaysTool,
        InterpreterTool,
        CallLogTool, SosTool, PhotoSearchTool, ReceiptTool, PersonReminderTool, DrivingModeTool, HealthTool, QuietModeTool, SubscriptionsTool, RecipeTool, ParcelTool, BudgetTool, ReadTextTool, WakeBriefingTool, TransportTool, TidesTool, PcControlTool,
        com.jarvis.android.actions.HaseoArTool,
    )

    private val byName = ALL.associateBy { it.name }

    private val plugins: List<Tool> get() = InstalledPlugins.all()

    /** The user's plugins (see the plugins package); replaced as a whole whenever they change. */
    internal fun setPlugins(list: List<Tool>) {
        InstalledPlugins.set(list.filter { it.name !in byName })
    }

    internal fun pluginTools(): List<Tool> = plugins

    /** The plugins declared to the model one by one, and the ones reached through `plugin_run`. */
    internal fun declaredPlugins(): List<Tool> = InstalledPlugins.declared()
    internal fun extraPlugins(): List<Tool> = InstalledPlugins.extra()

    /** The names a plugin cannot take: the built-in tools and `plugin_run`. */
    internal fun builtInNames(): Set<String> = byName.keys + com.jarvis.android.plugins.PluginRunTool.name

    fun get(name: String): Tool? = byName[name] ?: plugins.firstOrNull { it.name == name }
        ?: com.jarvis.android.plugins.PluginRunTool.takeIf { name == it.name && plugins.size > MAX_DECLARED_PLUGINS }

    fun declarations(): List<JsonObject> = (ALL + declaredPlugins() + listOfNotNull(com.jarvis.android.plugins.PluginRunTool.takeIf { plugins.size > MAX_DECLARED_PLUGINS })).map { tool ->
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
