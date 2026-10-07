package com.jarvis.android.engine

import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.JarvisContainer
import com.jarvis.android.registry.ToolRegistry
import com.jarvis.android.core.ConversationMessage
import com.jarvis.android.core.ConversationRole
import com.jarvis.android.core.appendConversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.TimeUnit
import com.jarvis.android.rest.AudioPlayer
import com.jarvis.android.rest.AudioRecorder
import com.jarvis.android.rest.ChatHistoryStore
import com.jarvis.android.rest.ERROR_EMPTY_DRAFT
import com.jarvis.android.rest.ERROR_NETWORK
import com.jarvis.android.rest.ERROR_NO_KEY
import com.jarvis.android.rest.ERROR_TOO_MANY_TOOLS
import com.jarvis.android.rest.GenerateTransport
import com.jarvis.android.rest.MAX_SAVED_TURN_CHARS
import com.jarvis.android.rest.ModelLadder
import com.jarvis.android.rest.OkHttpGenerateTransport
import com.jarvis.android.rest.RestCall
import com.jarvis.android.rest.RestChatException
import com.jarvis.android.rest.RestReply
import com.jarvis.android.rest.RestVoice
import com.jarvis.android.rest.SavedChat
import com.jarvis.android.rest.SavedMessage
import com.jarvis.android.rest.SpeechModelResolver
import com.jarvis.android.rest.VoicePreview
import com.jarvis.android.rest.buildGenerateRequest
import com.jarvis.android.rest.functionResponseTurn
import com.jarvis.android.rest.modelTurn
import com.jarvis.android.rest.parseGenerateResponse
import com.jarvis.android.rest.trimTurns
import com.jarvis.android.rest.userTurn

internal const val MAX_TOOL_ROUNDS = 10

/** What the calls left over at the round limit answer, so the model sums up instead of the whole request being lost. */
internal const val ROUND_LIMIT_RESULT =
    "Not run: too many actions in a row. Stop calling tools and answer the user now: what you did, what you found, and what is left."

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

    /** An exchange answered elsewhere (the local model, without the network), so the model knows it once the network is back. */
    fun appendExchange(question: String, answer: String) {
        contents += userTurn(question)
        contents += modelTurn(buildJsonObject {
            putJsonArray("parts") { addJsonObject { put("text", answer) } }
        })
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
                        contents += modelTurn(reply.content)
                        if (++rounds > maxRounds) return conclude(reply.calls)
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

    /**
     * The round limit is reached: [pending] are not run, and the model is asked once more with tools switched off, so the user hears what
     * was done rather than an error that throws the work away. Only a model that still calls a tool then ends in [ERROR_TOO_MANY_TOOLS].
     */
    private suspend fun conclude(pending: List<RestCall>): String {
        contents += functionResponseTurn(pending.map { it to ROUND_LIMIT_RESULT })
        val request = buildGenerateRequest(systemInstruction(), contents, toolDeclarations(), allowCalls = false)
        val reply = parseGenerateResponse(transport.generate(model(), request)) as? RestReply.Text
            ?: throw RestChatException(ERROR_TOO_MANY_TOOLS)
        contents += modelTurn(reply.content)
        return reply.text
    }
}

/** App-wide text chat: the state shown by the chat screen, on top of a [RestChatSession]. */
class RestChat internal constructor(
    private val container: JarvisContainer,
    transport: GenerateTransport? = null,
) {
    private val lock = Mutex()

    // a deadline for the whole call as well as for each read: a model that holds the line without answering is let go (and the
    // ladder moves on to the next one) instead of being waited for without end
    private val httpClient = container.http.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()

    internal val transport: GenerateTransport = transport ?: OkHttpGenerateTransport(
        client = httpClient,
        apiKey = { container.configStore.getApiKey() },
        nextKey = { container.configStore.keyRejected(it) },
        ladder = ModelLadder.shared,
        log = { android.util.Log.i("JarvisModels", it) },
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

    /** Samples of the voices, for choosing one in the settings. */
    internal val voicePreview: VoicePreview by lazy {
        VoicePreview(
            transport = this.transport,
            speechModel = { container.configStore.snapshotTtsModel().trim().ifEmpty { speechModels.resolve() } },
            output = AudioPlayer(container.appContext),
            cacheDir = container.appContext.cacheDir,
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
            val mode = container.configStore.offlineMode.first()
            val offline = mode != OFFLINE_NEVER &&
                (mode == OFFLINE_ALWAYS || container.configStore.getApiKey().isNullOrBlank() || !phoneOnline(container.appContext))
            if (offline) {
                if (container.localBrain.available()) return@withLock sendLocal(draft, before)
                if (mode == OFFLINE_ALWAYS) {
                    _messages.value = before
                    return@withLock tr("Mode hors ligne : aucun modèle local n’est installé ou l’IA locale est coupée (Paramètres > IA locale).")
                }
            }
            val reply = try {
                session.send(draft)
            } catch (e: RestChatException) {
                // the network went while sending: the same conversation goes on with the local model
                if (mode == OFFLINE_AUTO && (e.message == ERROR_NETWORK || e.message == ERROR_NO_KEY) && container.localBrain.available()) {
                    return@withLock sendLocal(draft, before)
                }
                throw e
            }
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

    /**
     * [draft] answered by the local model, with the same tools and this chat's messages ([before], what came before it), shown and
     * kept like any answer and added to the model's history too. Null on success, otherwise a message to show to the user.
     */
    private suspend fun sendLocal(draft: String, before: List<ConversationMessage>): String? {
        val result = container.localBrain.answer(
            question = draft,
            history = before,
            recentTools = com.jarvis.android.offline.recentToolNames(before),
            onStep = { name -> _messages.update { appendConversation(it, ConversationRole.SYSTEM, trf("Action : {0}", name), complete = true) } },
            unloadWhenIdle = true,
        )
        val text = result.text?.trim()?.takeIf { it.isNotBlank() }
        if (text == null) {
            // tools that ran stay shown: they did run
            _messages.update { msgs -> if (result.steps.isEmpty()) before else msgs }
            return result.reason ?: tr("L’IA locale n’a pas pu répondre. Réessayez.")
        }
        _messages.update { appendConversation(it, ConversationRole.ASSISTANT, text, complete = true) }
        session.appendExchange(draft, text)
        persist()
        return null
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

/**
 * Keeps what a finished conversation taught: a one-line summary (for the next briefing and the prompt) and the lasting facts about the user.
 * The transcript is put aside first and only dropped once the answer has been stored, so a failed request or a killed process does not lose it:
 * [retryPendingTranscripts] plays the waiting ones again at the next start.
 */
internal suspend fun summarizeConversation(container: JarvisContainer, messages: List<ConversationMessage>) {
    val transcript = com.jarvis.android.memory.transcriptForSummary(messages)
    if (transcript.isBlank()) return
    val file = pendingTranscripts(container).add(transcript)
    processTranscript(container, transcript, file)
}

internal fun pendingTranscripts(container: JarvisContainer) =
    com.jarvis.android.memory.PendingTranscripts(java.io.File(container.appContext.filesDir, "pending_transcripts"))

/** Plays again the conversations whose summary could not be stored (no network, app closed, no key at the time). */
internal suspend fun retryPendingTranscripts(container: JarvisContainer) {
    for (file in pendingTranscripts(container).list()) {
        val transcript = try { file.readText(Charsets.UTF_8) } catch (_: java.io.IOException) { continue }
        processTranscript(container, transcript, file)
    }
}

private suspend fun processTranscript(container: JarvisContainer, transcript: String, file: java.io.File) {
    try {
        val known = container.memoryManager.allEntriesForUi().take(60).joinToString("\n") { "${it.category}/${it.key}: ${it.value.take(80)}" }
        val model = container.configStore.snapshotRestModel()
        val reply = container.restChat.transport.generate(model, com.jarvis.android.memory.buildExtractionRequest(transcript, known))
        val text = (parseGenerateResponse(reply) as? RestReply.Text)?.text
        val extraction = text?.let { com.jarvis.android.memory.parseExtraction(it) } ?: return
        if (extraction.summary.isNotBlank()) container.memoryManager.saveSessionSummary(extraction.summary)
        if (extraction.facts.isNotEmpty()) container.memoryManager.update(extraction.facts)
        file.delete()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // kept for the next start
    }
}
