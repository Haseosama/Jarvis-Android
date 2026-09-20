package com.jarvis.android.rest

import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.JarvisContainer
import com.jarvis.android.actions.ToolRegistry
import com.jarvis.android.core.ConversationMessage
import com.jarvis.android.core.ConversationRole
import com.jarvis.android.core.buildSystemInstruction
import com.jarvis.android.core.appendConversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.TimeUnit

internal const val MAX_TOOL_ROUNDS = 6

/**
 * A text conversation over plain `generateContent`, with function calling. Works without the
 * Live WebSocket, so it stays usable when the key or project has no Live access.
 *
 * The history is kept as the raw JSON turns Gemini expects, and model turns are echoed back
 * verbatim (newer models attach signatures to them that must be returned unchanged).
 * A failed or cancelled [send] leaves the history exactly as it was before the call.
 */
internal class RestChatSession(
    private val transport: GenerateTransport,
    private val model: suspend () -> String,
    private val systemInstruction: suspend () -> String,
    private val toolDeclarations: () -> List<JsonObject>,
    private val runTool: suspend (name: String, args: JsonObject) -> String,
    private val maxRounds: Int = MAX_TOOL_ROUNDS,
) {
    private val contents = mutableListOf<JsonObject>()

    fun snapshot(): List<JsonObject> = contents.toList()

    /** Replaces the model's context with saved turns (used when the chat is reloaded from disk). */
    fun restore(turns: List<JsonObject>) {
        contents.clear()
        contents += turns
    }

    fun reset() {
        contents.clear()
    }

    suspend fun send(text: String): String {
        val draft = text.trim()
        if (draft.isEmpty()) throw RestChatException(ERROR_EMPTY_DRAFT)
        val start = contents.size
        contents += userTurn(draft)
        try {
            var rounds = 0
            while (true) {
                val request = buildGenerateRequest(systemInstruction(), contents, toolDeclarations())
                when (val reply = parseGenerateResponse(transport.generate(model(), request))) {
                    is RestReply.Text -> {
                        contents += modelTurn(reply.content)
                        return reply.text
                    }
                    is RestReply.Calls -> {
                        if (++rounds > maxRounds) throw RestChatException(ERROR_TOO_MANY_TOOLS)
                        contents += modelTurn(reply.content)
                        val results = reply.calls.map { call -> call to runTool(call.name, call.args) }
                        contents += functionResponseTurn(results)
                    }
                }
            }
        } catch (e: Throwable) {
            while (contents.size > start) contents.removeAt(contents.lastIndex)
            throw e
        }
    }
}

/** App-wide text chat: the state shown by the chat screen, on top of a [RestChatSession]. */
class RestChat internal constructor(
    private val container: JarvisContainer,
    transport: GenerateTransport? = null,
) {
    private val lock = Mutex()

    private val httpClient = container.http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()

    internal val transport: GenerateTransport = transport ?: OkHttpGenerateTransport(
        client = httpClient,
        apiKey = { container.configStore.getApiKey() },
        nextKey = { container.configStore.keyRejected(it) },
    )

    private val session = RestChatSession(
        transport = this.transport,
        model = { container.configStore.snapshotRestModel() },
        systemInstruction = { buildSystemInstruction(container, textMode = true) },
        toolDeclarations = { ToolRegistry.declarations() },
        runTool = { name, args ->
            _messages.update { appendConversation(it, ConversationRole.SYSTEM, trf("Action : {0}", name), complete = true) }
            ToolRegistry.run(name, args, container)
        },
    )

    private val speechModels = SpeechModelResolver(httpClient) { container.configStore.getApiKey() }

    /** Push-to-talk on top of this chat. */
    internal val voice: RestVoice by lazy {
        RestVoice(
            recorder = AudioRecorder(container.appContext),
            output = AudioPlayer(container.appContext),
            transport = this.transport,
            textModel = { container.configStore.snapshotRestModel() },
            speechModel = {
                container.configStore.snapshotTtsModel().trim().ifEmpty { speechModels.resolve() }
            },
            voice = { container.configStore.snapshotVoice() },
            sendText = { send(it) },
            lastReply = { _messages.value.lastOrNull { it.role == ConversationRole.ASSISTANT }?.text },
            onMetrics = { android.util.Log.i("JarvisRestVoice", it) },
        )
    }

    private val history = ChatHistoryStore(java.io.File(container.appContext.filesDir, "chat_history.json"))
    private val historyScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Saves the chat (or deletes the file when saving is switched off). Runs in the background. */
    private fun persist() {
        val messages = _messages.value
        val turns = session.snapshot()
        historyScope.launch {
            if (!container.configStore.snapshotChatHistoryEnabled()) {
                history.clear()
                return@launch
            }
            history.save(
                SavedChat(
                    messages.filter { it.complete || it.role != ConversationRole.ASSISTANT }.takeLast(60).map { SavedMessage(it.role.name, it.text) },
                    trimTurns(turns, MAX_SAVED_TURN_CHARS),
                )
            )
        }
    }

    /** Deletes the saved chat file (when the user switches saving off). */
    fun clearSavedHistory() {
        historyScope.launch { history.clear() }
    }

    private val _messages = MutableStateFlow<List<ConversationMessage>>(emptyList())
    val messages: StateFlow<List<ConversationMessage>> = _messages.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /** Sends [text]; returns null on success, otherwise a message to show to the user. */
    suspend fun send(text: String): String? = lock.withLock {
        val draft = text.trim()
        if (draft.isEmpty()) return@withLock ERROR_EMPTY_DRAFT
        val before = _messages.value
        _sending.value = true
        _messages.update { appendConversation(it, ConversationRole.USER, draft, complete = true) }
        try {
            val reply = session.send(draft)
            _messages.update { appendConversation(it, ConversationRole.ASSISTANT, reply, complete = true) }
            persist()
            null
        } catch (e: CancellationException) {
            _messages.value = before
            throw e
        } catch (e: RestChatException) {
            _messages.value = before
            e.message
        } catch (_: Exception) {
            _messages.value = before
            tr("Erreur inattendue. Réessayez.")
        } finally {
            _sending.value = false
        }
    }

    /** Adds an answer that did not come from a message the user just sent (a finished background task). */
    fun postAssistant(text: String) {
        _messages.update { appendConversation(it, ConversationRole.ASSISTANT, text, complete = true) }
        persist()
    }

    private val summaryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    init {
        historyScope.launch {
            if (!container.configStore.snapshotChatHistoryEnabled()) return@launch
            val saved = history.load() ?: return@launch
            lock.withLock {
                if (_messages.value.isNotEmpty()) return@withLock
                session.restore(saved.turns)
                _messages.value = saved.messages.mapNotNull { m ->
                    val role = ConversationRole.entries.firstOrNull { it.name == m.role } ?: return@mapNotNull null
                    ConversationMessage(role, m.text, complete = true)
                }
            }
        }
    }

    /** Starts over, keeping a one-line memory of the conversation if it was long enough. Ignored while a message is being sent. */
    fun reset() {
        if (!lock.tryLock()) return
        try {
            val finished = _messages.value
            if (com.jarvis.android.memory.worthSummarizing(finished)) {
                summaryScope.launch { summarizeConversation(container, finished) }
            }
            session.reset()
            _messages.value = emptyList()
            historyScope.launch { history.clear() }
        } finally {
            lock.unlock()
        }
    }
}

/** Keeps a one-line memory of a finished conversation, for tomorrow's briefing. Best effort: failures are ignored. */
internal suspend fun summarizeConversation(container: JarvisContainer, messages: List<ConversationMessage>) {
    try {
        val model = container.configStore.snapshotRestModel()
        val request = com.jarvis.android.memory.buildSummaryRequest(com.jarvis.android.memory.transcriptForSummary(messages))
        val reply = container.restChat.transport.generate(model, request)
        val text = (parseGenerateResponse(reply) as? RestReply.Text)?.text
        if (!text.isNullOrBlank()) container.memoryManager.saveSessionSummary(text.trim())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}
