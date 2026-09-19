package com.jarvis.android.rest

import com.jarvis.android.JarvisContainer
import com.jarvis.android.actions.ToolRegistry
import com.jarvis.android.core.ConversationMessage
import com.jarvis.android.core.ConversationRole
import com.jarvis.android.core.buildSystemInstruction
import com.jarvis.android.core.appendConversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
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
) {
    private val contents = mutableListOf<JsonObject>()

    fun snapshot(): List<JsonObject> = contents.toList()

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
                        if (++rounds > MAX_TOOL_ROUNDS) throw RestChatException(ERROR_TOO_MANY_TOOLS)
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

    private val transport: GenerateTransport = transport ?: OkHttpGenerateTransport(
        client = httpClient,
        apiKey = { container.configStore.getApiKey() },
    )

    private val session = RestChatSession(
        transport = this.transport,
        model = { container.configStore.snapshotRestModel() },
        systemInstruction = { buildSystemInstruction(container, textMode = true) },
        toolDeclarations = { ToolRegistry.declarations() },
        runTool = { name, args ->
            _messages.update { appendConversation(it, ConversationRole.SYSTEM, "Action : $name", complete = true) }
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
        )
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
            null
        } catch (e: CancellationException) {
            _messages.value = before
            throw e
        } catch (e: RestChatException) {
            _messages.value = before
            e.message
        } catch (_: Exception) {
            _messages.value = before
            "Erreur inattendue. Réessayez."
        } finally {
            _sending.value = false
        }
    }

    /** Starts over. Ignored while a message is being sent. */
    fun reset() {
        if (!lock.tryLock()) return
        try {
            session.reset()
            _messages.value = emptyList()
        } finally {
            lock.unlock()
        }
    }
}
