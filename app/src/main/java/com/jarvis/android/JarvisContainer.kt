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

    init {
        appScope.launch {
            configStore.wakeWordEnabled.collect {
                wakeEnabled = it
                syncVoiceService()
            }
        }
        com.jarvis.android.device.AccessibilityKeeper.ensureEnabled(appContext)
        appScope.launch {
            configStore.proactiveEnabled.collect { com.jarvis.android.proactive.ProactiveScheduler.apply(appContext, it) }
        }
        appScope.launch { configStore.avatarFace.collect { avatar.enabled = it } }
        com.jarvis.android.routines.RoutineScheduler.ensureScheduled(appContext)
        appScope.launch { configStore.audioInputKey.collect { com.jarvis.android.core.AudioRoute.inputKey = it } }
        appScope.launch { configStore.audioOutputKey.collect { com.jarvis.android.core.AudioRoute.outputKey = it } }
        val notifier = com.jarvis.android.core.ConfirmNotifier(appContext)
        appScope.launch { confirmManager.pending.collect { notifier.show(it) } }
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

    internal val wakeModel = com.jarvis.android.wake.WakeModelManager(appContext, http)

    internal val agent: com.jarvis.android.agent.AgentRunner by lazy { com.jarvis.android.agent.AgentRunner(this, appScope) }

    /** Text chat over generateContent; independent of the Live session. */
    val restChat: RestChat by lazy { RestChat(this) }
}
