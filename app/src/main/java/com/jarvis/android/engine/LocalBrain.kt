package com.jarvis.android.engine

import com.jarvis.android.JarvisContainer
import com.jarvis.android.core.ConversationMessage
import com.jarvis.android.offline.LOCAL_BUDGET_LARGE
import com.jarvis.android.offline.LOCAL_BUDGET_SMALL
import com.jarvis.android.offline.LocalAgentResult
import com.jarvis.android.offline.LocalLlm
import com.jarvis.android.offline.localToolSpec
import com.jarvis.android.offline.pickLocalTools
import com.jarvis.android.offline.runLocalAgent
import com.jarvis.android.registry.ToolRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tools the local model is not offered: they need Gemini itself, or end a session the offline loop ends by its own words. */
internal val LOCAL_EXCLUDED_TOOLS = setOf("end_session", "agent_task", "vision_stream", "interpreter", "change_voice")

/** How long the model stays loaded after a text-chat answer, so the next message does not wait for it to load again. */
private const val LOCAL_IDLE_UNLOAD_MS = 5 * 60_000L

/**
 * The local model as Jarvis's brain when there is no network: the same tools (the registry, run the same way) and the same conversation
 * (the voice session's or the text chat's own messages), plus what Jarvis knows (date, names, memory, the last sessions). Shared by the
 * voice and the text chat, one answer at a time.
 */
internal class LocalBrain(private val container: JarvisContainer) {
    private val llm = LocalLlm(container.appContext)
    private val lock = Mutex()
    private var idleUnload: Job? = null

    /** A model is installed and the local AI is switched on. */
    suspend fun available(): Boolean = container.localModelStore.installed() && container.configStore.localAiEnabled.first()

    /**
     * Answers [question] in the conversation [history] (the messages before it). [recentTools] are the tools used just before, newest
     * first; [onStep] is told of each tool run. [unloadWhenIdle] keeps the model loaded for a while instead of until [unload].
     */
    suspend fun answer(
        question: String,
        history: List<ConversationMessage>,
        recentTools: List<String>,
        onStep: (String) -> Unit,
        unloadWhenIdle: Boolean = false,
    ): LocalAgentResult = lock.withLock {
        idleUnload?.cancel()
        val path = container.localModelStore.file.absolutePath
        val large = path.endsWith(".litertlm")
        val budget = if (large) LOCAL_BUDGET_LARGE else LOCAL_BUDGET_SMALL
        val all = ToolRegistry.declarations().mapNotNull { localToolSpec(it) }.filter { it.name !in LOCAL_EXCLUDED_TOOLS }
        val tools = pickLocalTools(question, history, all, recentTools, max = if (large) 12 else 5)
        try {
            runLocalAgent(
                question = question,
                history = history,
                context = context(),
                tools = tools,
                budget = budget,
                generate = { prompt -> llm.reply(prompt, path) },
                runTool = { name, args -> ToolRegistry.run(name, args, container) },
                onStep = onStep,
            )
        } finally {
            if (unloadWhenIdle) idleUnload = container.appScope.launch {
                delay(LOCAL_IDLE_UNLOAD_MS)
                lock.withLock { llm.unload() }
            }
        }
    }

    /** Frees the model's memory (the end of an offline voice session). */
    suspend fun unload() {
        idleUnload?.cancel()
        lock.withLock { llm.unload() }
    }

    /** What Jarvis knows, most useful first (the prompt cuts from the end): the time, who is who, the memory, the last sessions. */
    private suspend fun context(): String {
        val store = container.configStore
        val assistant = store.snapshotAssistantName()
        val user = store.snapshotUserName()
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.FRANCE).format(Date())
        val memory = try { container.memoryManager.formatForPrompt() } catch (_: Exception) { "" }
        val sessions = try {
            if (store.keepSessionTranscripts.first()) container.sessionTranscripts.promptBlock() else ""
        } catch (_: Exception) { "" }
        return buildString {
            append("Nous sommes le ").append(now).append(".\n")
            append("Tu t'appelles ").append(assistant).append('.')
            if (user.isNotBlank()) append(" L'utilisateur s'appelle ").append(user).append('.')
            append('\n')
            if (memory.isNotBlank()) append(memory.trim()).append('\n')
            if (sessions.isNotBlank()) append(sessions.trim()).append('\n')
        }
    }
}
