package com.jarvis.android

import android.content.Context
import com.jarvis.android.core.ConfirmManager
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

    init {
        com.jarvis.android.device.AccessibilityKeeper.ensureEnabled(appContext)
        val notifier = com.jarvis.android.core.ConfirmNotifier(appContext)
        appScope.launch { confirmManager.pending.collect { notifier.show(it) } }
    }

    /** One engine per process, shared by the foreground service and the UI. */
    val engine: JarvisEngine by lazy { JarvisEngine(this, appScope) }

    internal val briefing: com.jarvis.android.memory.BriefingCoordinator by lazy { com.jarvis.android.memory.BriefingCoordinator(this) }

    /** Text chat over generateContent; independent of the Live session. */
    val restChat: RestChat by lazy { RestChat(this) }
}
