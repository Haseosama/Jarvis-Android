package com.jarvis.android

import android.content.Context
import com.jarvis.android.core.ConfirmManager
import com.jarvis.android.core.JarvisState
import com.jarvis.android.core.wantsStandby
import com.jarvis.android.core.JarvisEngine
import com.jarvis.android.core.UndoManager
import com.jarvis.android.memory.ConfigStore
import com.jarvis.android.memory.MemoryManager
import com.jarvis.android.rest.RestChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * App-wide dependency bag handed to every [com.jarvis.android.actions.Tool].
 * Equivalent of the `ctx` dict (`player`, `speak`, `response`, `session_memory`)
 * that Mark-LIII's action_loader injects into handlers by signature introspection.
 */
class JarvisContainer(val appContext: Context) {
    val configStore = ConfigStore(appContext)
    val memoryManager = MemoryManager(appContext)
    val undoManager = UndoManager()
    val routines = com.jarvis.android.routines.RoutineStore(appContext)
    val confirmManager = ConfirmManager()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Activity-log sink — surfaced in the HUD, mirrors `ui.write_log`. */
    var onLog: (String) -> Unit = {}

    fun log(message: String) {
        onLog(message)
    }

    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Mirrors the wake-word setting so that non-suspending code can read it. */
    @Volatile var wakeEnabled: Boolean = false
        private set

    /** Mirror of the setting for sending messages on the user's word, read by the tools that must not suspend to look it up. */
    @Volatile var messageAutoSend: Boolean = false

    /** The two languages while interpreter mode is on (see actions/InterpreterTool.kt), null otherwise. */
    @Volatile var interpreterPair: Pair<String, String>? = null

    /** Mirror of the setting that skips the confirmation banner for volume, file changes and on-screen taps (never for system/security screens, see ConfigStore.skipConfirmations). */
    @Volatile var skipConfirmations: Boolean = false

    internal val sentMessages: com.jarvis.android.messaging.SentMessages by lazy {
        com.jarvis.android.messaging.SentMessages(java.io.File(appContext.noBackupFilesDir, "sent_messages.json"))
    }

    fun micGranted(): Boolean =
        appContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Brings the foreground service in line with the settings: kept alive listening for the wake
     * word when it is on, stopped when it is off and no session is running. Only starts while the
     * app is in front (Android refuses microphone services started from the background).
     */
    fun syncVoiceService() {
        if (wantsStandby(wakeEnabled, micGranted())) {
            com.jarvis.android.core.VoiceServiceControl.startStandby(appContext)
        } else if (engine.state.value == JarvisState.ASLEEP) {
            com.jarvis.android.core.VoiceServiceControl.stop(appContext)
        }
    }

    /** After a session ended: back to standby if the wake word is on, otherwise the service can go. */
    fun releaseVoiceService() {
        if (wantsStandby(wakeEnabled, micGranted())) {
            com.jarvis.android.core.VoiceServiceControl.startStandby(appContext)
        } else {
            com.jarvis.android.core.VoiceServiceControl.stop(appContext)
        }
    }

    /** One engine per process, shared by the foreground service and the UI. */
    val engine: JarvisEngine by lazy { JarvisEngine(this, appScope) }

    /** A video shown in place of the avatar (play_video). */
    val videoPanel: com.jarvis.android.video.VideoPanel by lazy { com.jarvis.android.video.VideoPanel(log = { log(it) }) }

    /** Where each video was left (on the phone only). */
    val videoHistory: com.jarvis.android.video.VideoHistory by lazy {
        val prefs = appContext.getSharedPreferences("video_history", Context.MODE_PRIVATE)
        com.jarvis.android.video.VideoHistory({ prefs.getString("entries", null) }, { prefs.edit().putString("entries", it).apply() })
    }
    private val videoMedia by lazy { com.jarvis.android.video.VideoMedia(appContext, videoPanel, appScope) }

    internal val briefing: com.jarvis.android.memory.BriefingCoordinator by lazy { com.jarvis.android.memory.BriefingCoordinator(this) }

    internal val attachedFiles = com.jarvis.android.files.AttachedFileStore()
    internal val shareInbox = com.jarvis.android.share.ShareInbox()
    internal val avatar = com.jarvis.android.avatar.AvatarController(appContext)

    internal val pluginStore = com.jarvis.android.plugins.PluginStore(java.io.File(appContext.filesDir, "plugins"))

    init {
        pluginStore.reload()
    }

    internal val sessionLog = com.jarvis.android.core.SessionLog(java.io.File(appContext.noBackupFilesDir, "session_log.json"))

    /** What was said in the voice sessions, kept on the phone (see SessionTranscripts.kt). */
    internal val sessionTranscripts = com.jarvis.android.memory.SessionTranscripts(java.io.File(appContext.filesDir, "session_transcripts.json"))

    internal val wakeModel = com.jarvis.android.wake.WakeModelManager(appContext, http)

    /** The offline mode's local model (imported by the user; see LocalModelStore.kt). */
    internal val localModelStore = com.jarvis.android.offline.LocalModelStore(appContext)

    /** Shopping and to-do lists, shared by the online and the offline mode (see tasks/TaskListStore.kt). */
    internal val taskListStore = com.jarvis.android.tasks.TaskListStore(java.io.File(appContext.filesDir, "task_lists.json"))

    /** Spending noted by voice (see expenses/ExpenseStore.kt). */
    internal val expenseStore = com.jarvis.android.expenses.ExpenseStore(java.io.File(appContext.filesDir, "expenses.json"))

    /** Medications and habits at fixed times, with their log (see habits/Habits.kt). */
    internal val habitStore = com.jarvis.android.habits.HabitStore(java.io.File(appContext.filesDir, "habits.json"))

    /** Named places and location reminders (see places/PlaceReminders.kt). */
    internal val placeStore = com.jarvis.android.places.PlaceStore(java.io.File(appContext.filesDir, "place_reminders.json"))

    /** Where the car is parked, and the car's Bluetooth for saving it by itself (see parking/Parking.kt). */
    internal val parkingStore = com.jarvis.android.parking.ParkingStore(java.io.File(appContext.filesDir, "parking.json"))

    /** The trusted contacts of the SOS alert (see sos/Sos.kt). */
    internal val sosStore = com.jarvis.android.sos.SosStore(java.io.File(appContext.filesDir, "sos.json"))

    /** Notes tied to a contact, shown when they call or write (see people/PersonReminders.kt). */
    /** Driving mode's settings (see driving/DrivingMode.kt). */
    internal val drivingStore = com.jarvis.android.driving.DrivingStore(java.io.File(appContext.filesDir, "driving.json"))

    /** The quiet mode ("je suis en réunion jusqu'à 15 h") and its automatic answer (see quiet/QuietMode.kt). */
    internal val quietStore = com.jarvis.android.quiet.QuietStore(java.io.File(appContext.filesDir, "quiet.json"))

    /** Subscriptions and regular payments (see subscriptions/Subscriptions.kt). */
    internal val subscriptionStore = com.jarvis.android.subscriptions.SubscriptionStore(java.io.File(appContext.filesDir, "subscriptions.json"))

    /** The recipe being cooked and the kept ones (see recipes/Recipes.kt). */
    internal val recipeStore = com.jarvis.android.recipes.RecipeStore(java.io.File(appContext.filesDir, "recipes.json")).also { store ->
        store.current()?.let { com.jarvis.android.recipes.RecipeLive.lastUsed = it.updatedAt }
    }

    /** Parcels being followed (see parcels/Parcels.kt). */
    internal val parcelStore = com.jarvis.android.parcels.ParcelStore(java.io.File(appContext.filesDir, "parcels.json"))

    /** Monthly budgets per category (see budgets/Budgets.kt), told of every expense noted. */
    internal val budgetStore = com.jarvis.android.budgets.BudgetStore(java.io.File(appContext.filesDir, "budgets.json"))

    init {
        expenseStore.onAdded = { e -> com.jarvis.android.budgets.Budgets.afterExpense(appContext, this, e) }
    }

    /** The briefing on waking up and the alarm it watches (see wakeup/WakeBriefing.kt). */
    internal val wakeStore = com.jarvis.android.wakeup.WakeStore(java.io.File(appContext.filesDir, "wake_briefing.json"))

    internal val personReminderStore = com.jarvis.android.people.PersonReminderStore(java.io.File(appContext.filesDir, "person_reminders.json"))

    internal val agent: com.jarvis.android.agent.AgentRunner by lazy { com.jarvis.android.agent.AgentRunner(this, appScope) }

    /** Text chat over generateContent; independent of the Live session. */
    val restChat: RestChat by lazy { RestChat(this) }

    /**
     * Last of the class on purpose: these collectors start at once, on other threads, and touch the engine, the avatar and the others,
     * which have to be built first (a cold start with no stored settings used to crash on them).
     */
    init {
        appScope.launch {
            configStore.wakeWordEnabled.collect {
                wakeEnabled = it
                syncVoiceService()
            }
        }
        com.jarvis.android.device.AccessibilityKeeper.ensureEnabled(appContext)
        // What was said in a session that ended before its summary could be stored is stored now.
        appScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(8_000)   // the container is fully built by then
            com.jarvis.android.rest.retryPendingTranscripts(this@JarvisContainer)
        }
        appScope.launch {
            configStore.proactiveEnabled.collect { com.jarvis.android.proactive.ProactiveScheduler.apply(appContext, it) }
        }
        appScope.launch { configStore.rainAlerts.collect { com.jarvis.android.weather.RainWatchWorker.apply(appContext, it) } }
        appScope.launch { configStore.messageAutoSend.collect { messageAutoSend = it } }
        appScope.launch { configStore.skipConfirmations.collect { skipConfirmations = it } }
        appScope.launch { configStore.avatarFace.collect { avatar.enabled = it } }
        appScope.launch { configStore.avatarModel.collect { avatar.model = it } }
        appScope.launch { configStore.avatarLight.collect { avatar.light = it } }
        // a video: the face watches it (its small face in the video's header) and reacts when it starts and when it goes
        videoPanel.onShown = { appScope.launch(kotlinx.coroutines.Dispatchers.Main) { avatar.watching = true; avatar.reactions++ } }
        videoPanel.onClosed = {
            videoHistory.save()
            appScope.launch(kotlinx.coroutines.Dispatchers.Main) { avatar.watching = false; avatar.reactions++ }
        }
        // where it is: kept for coming back to it, and shown on the lock screen
        videoPanel.onProgress = { v, p, d -> videoHistory.record(v, p, d, System.currentTimeMillis()); videoMedia.progress(d) }
        appScope.launch(kotlinx.coroutines.Dispatchers.Main) { videoMedia }
        // "arrête la vidéo dans 20 minutes"
        appScope.launch {
            videoPanel.timer.collectLatest {
                val left = videoPanel.timerLeftMs() ?: return@collectLatest
                kotlinx.coroutines.delay(left)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { videoPanel.checkTimer() }
            }
        }
        // the user is heard over the video: the small face looks up, once
        videoPanel.onFloor = { appScope.launch(kotlinx.coroutines.Dispatchers.Main) { avatar.reactions++ } }
        videoPanel.wakePhrase = { if (wakeModel.installed()) wakeModel.label(wakeModel.selected()) else null }
        appScope.launch {
            kotlinx.coroutines.flow.combine(configStore.avatarModel, configStore.avatarHair) { m, h -> h[com.jarvis.android.avatar.avatarFace(m).label].orEmpty() }
                .collect { avatar.hair = it }
        }
        appScope.launch {
            kotlinx.coroutines.flow.combine(configStore.avatarModel, configStore.avatarHairColour) { m, h -> h[com.jarvis.android.avatar.avatarFace(m).label].orEmpty() }
                .collect { avatar.hairColour = it }
        }
        appScope.launch { configStore.avatarSkin.collect { avatar.skin = it } }
        appScope.launch { configStore.avatarLips.collect { avatar.lips = it } }
        appScope.launch { configStore.avatarCap.collect { avatar.cap = it } }
        com.jarvis.android.routines.RoutineScheduler.sync(appContext)
        // Habit alarms and geofences are gone after a force-stop: armed again at every start (both are idempotent).
        appScope.launch(Dispatchers.IO) {
            try { com.jarvis.android.habits.HabitAlarms.reschedule(appContext) } catch (_: Exception) {}
            try { com.jarvis.android.places.Geofences.registerAll(appContext) } catch (_: Exception) {}
        }
        com.jarvis.android.watch.WatchScheduler.sync(appContext)
        appScope.launch { configStore.audioInputKey.collect { com.jarvis.android.core.AudioRoute.inputKey = it } }
        appScope.launch { configStore.audioOutputKey.collect { com.jarvis.android.core.AudioRoute.outputKey = it } }
        appScope.launch { configStore.carAudioMode.collect { com.jarvis.android.core.CarAudio.setting = it } }
        val notifier = com.jarvis.android.core.ConfirmNotifier(appContext)
        appScope.launch { confirmManager.pending.collect { notifier.show(it) } }
    }

}
