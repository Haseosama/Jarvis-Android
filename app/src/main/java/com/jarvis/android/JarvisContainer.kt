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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Mirrors the wake-word setting so that non-suspending code can read it. */
    @Volatile var wakeEnabled: Boolean = false
        private set

    /** Mirror of the setting for sending messages on the user's word, read by the tools that must not suspend to look it up. */
    @Volatile var messageAutoSend: Boolean = false

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
        appScope.launch { configStore.messageAutoSend.collect { messageAutoSend = it } }
        appScope.launch { configStore.skipConfirmations.collect { skipConfirmations = it } }
        appScope.launch { configStore.avatarFace.collect { avatar.enabled = it } }
        appScope.launch { configStore.avatarModel.collect { avatar.model = it } }
        appScope.launch { configStore.avatarSkin.collect { avatar.skin = it } }
        appScope.launch { configStore.avatarLips.collect { avatar.lips = it } }
        appScope.launch { configStore.avatarCap.collect { avatar.cap = it } }
        com.jarvis.android.routines.RoutineScheduler.ensureScheduled(appContext)
        com.jarvis.android.watch.WatchScheduler.sync(appContext)
        appScope.launch { configStore.audioInputKey.collect { com.jarvis.android.core.AudioRoute.inputKey = it } }
        appScope.launch { configStore.audioOutputKey.collect { com.jarvis.android.core.AudioRoute.outputKey = it } }
        appScope.launch { configStore.carAudioMode.collect { com.jarvis.android.core.CarAudio.setting = it } }
        val notifier = com.jarvis.android.core.ConfirmNotifier(appContext)
        appScope.launch { confirmManager.pending.collect { notifier.show(it) } }
    }

}
