package com.jarvis.android.core

import com.jarvis.android.JarvisContainer
import com.jarvis.android.actions.ToolRegistry
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class JarvisState { ASLEEP, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }

enum class ConversationRole { USER, ASSISTANT, SYSTEM }

data class ConversationMessage(
    val role: ConversationRole,
    val text: String,
    val complete: Boolean = false,
)

internal const val MAX_MESSAGE_CHARS = 8_000
internal const val MAX_CONVERSATION_MESSAGES = 100

internal fun appendConversation(
    messages: List<ConversationMessage>,
    role: ConversationRole,
    text: String,
    complete: Boolean = false,
): List<ConversationMessage> {
    if (text.isEmpty()) return messages
    val last = messages.lastOrNull()
    val result = if (!complete && last?.role == role && !last.complete) {
        messages.dropLast(1) + last.copy(text = (last.text + text).take(MAX_MESSAGE_CHARS))
    } else {
        finishConversationTurn(messages) + ConversationMessage(role, text.take(MAX_MESSAGE_CHARS), complete)
    }
    return result.takeLast(MAX_CONVERSATION_MESSAGES)
}

internal fun finishConversationTurn(messages: List<ConversationMessage>): List<ConversationMessage> =
    messages.map { if (it.complete) it else it.copy(complete = true) }

class JarvisEngine(
    private val container: JarvisContainer,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(JarvisState.ASLEEP)
    val state: StateFlow<JarvisState> = _state.asStateFlow()

    private val _activityLog = MutableStateFlow<List<String>>(emptyList())
    val activityLog: StateFlow<List<String>> = _activityLog.asStateFlow()

    private val _conversation = MutableStateFlow<List<ConversationMessage>>(emptyList())
    val conversation: StateFlow<List<ConversationMessage>> = _conversation.asStateFlow()

    private val _sessionReady = MutableStateFlow(false)
    val sessionReady: StateFlow<Boolean> = _sessionReady.asStateFlow()

    private val lifecycle = Mutex()
    private val stopVersion = AtomicLong()
    private val audio = AudioEngine(container.appContext)
    private var client: GeminiLiveClient? = null
    private var sessionJob: Job? = null
    private var lastActivityAt = 0L
    private var wakeDetector: WakeWordDetector? = null
    private val pendingAnnouncements = ArrayDeque<String>()

    init {
        scope.launch {
            combine(container.configStore.wakeWordEnabled, state) { enabled, s -> enabled to s }
                .collect { (enabled, s) -> updateWakeDetection(enabled, s) }
        }
    }

    private fun updateWakeDetection(enabled: Boolean, s: JarvisState) {
        if (!enabled || s != JarvisState.ASLEEP) {
            wakeDetector?.stop()
            return
        }
        if (ContextCompat.checkSelfPermission(container.appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("Mot d’activation en pause : autorisation du microphone manquante.")
            wakeDetector?.stop()
            return
        }
        val detector = wakeDetector
            ?: WakeWordDetector(container.appContext) { toggleAwake() }.also { wakeDetector = it }
        if (!detector.isAvailable) {
            log("Mot d’activation indisponible sur cet appareil.")
            return
        }
        detector.start()
    }

    private fun log(message: String) {
        _activityLog.update { (it + message).takeLast(200) }
    }

    suspend fun start() {
        val version = stopVersion.get()
        lifecycle.withLock {
            withContext(Dispatchers.Main.immediate) {
                if (version == stopVersion.get()) startLocked()
            }
        }
    }

    fun stop() {
        stopVersion.incrementAndGet()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.withLock {
                withContext(Dispatchers.Main.immediate) { stopLocked() }
            }
        }
    }

    fun toggleAwake() {
        if (state.value == JarvisState.ASLEEP || state.value == JarvisState.ERROR) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { start() }
        } else {
            stop()
        }
    }

    suspend fun sendText(text: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val content = text.trim()
        if (content.isEmpty() || content.length > MAX_MESSAGE_CHARS || !_sessionReady.value) {
            return@withContext false
        }
        sendContent(content, ConversationRole.USER)
    }

    suspend fun announce(text: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val content = text.trim()
        if (content.isEmpty() || content.length > MAX_MESSAGE_CHARS || !_sessionReady.value) {
            return@withContext false
        }
        if (_state.value == JarvisState.SPEAKING || _state.value == JarvisState.THINKING) {
            if (pendingAnnouncements.size >= MAX_PENDING_ANNOUNCEMENTS) pendingAnnouncements.removeFirst()
            pendingAnnouncements.add(content)
            return@withContext true
        }
        sendContent(content, ConversationRole.SYSTEM)
    }

    private suspend fun sendContent(text: String, role: ConversationRole): Boolean {
        val sent = client?.sendText(text) == true
        if (sent) {
            _conversation.update { appendConversation(it, role, text, complete = true) }
            lastActivityAt = android.os.SystemClock.elapsedRealtime()
            _state.value = JarvisState.THINKING
        } else {
            log("Message non envoyé. Vérifiez la session puis réessayez.")
        }
        return sent
    }

    private suspend fun flushAnnouncements(cl: GeminiLiveClient) {
        while (pendingAnnouncements.isNotEmpty()) {
            if (!_sessionReady.value) {
                pendingAnnouncements.clear()
                return
            }
            if (_state.value != JarvisState.LISTENING) return
            val next = pendingAnnouncements.removeFirst()
            if (!sendContent(next, ConversationRole.SYSTEM)) {
                pendingAnnouncements.clear()
                return
            }
            return
        }
    }

    private suspend fun startLocked() {
        if (sessionJob?.isActive == true) return
        sessionJob?.join()
        _conversation.value = emptyList()
        _sessionReady.value = false
        _state.value = JarvisState.CONNECTING
        sessionJob = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            runSession()
        }.also { it.start() }
    }

    private suspend fun stopLocked() {
        _sessionReady.value = false
        pendingAnnouncements.clear()
        sessionJob?.cancelAndJoin()
        sessionJob = null
        _conversation.value = emptyList()
        _state.value = JarvisState.ASLEEP
    }

    private suspend fun runSession() {
        var cl: GeminiLiveClient? = null
        try {
            val apiKey = container.configStore.getApiKey()
            if (apiKey.isNullOrBlank()) {
                log("Aucune clé API Gemini configurée.")
                _state.value = JarvisState.ERROR
                return
            }
            if (container.configStore.wakeWordEnabled.first()) {
                log("Session démarrée : détection du mot d’activation en pause jusqu’à la mise en veille.")
            }
            val model = container.configStore.snapshotModel()
            val voice = container.configStore.snapshotVoice()
            val instruction = withContext(Dispatchers.IO) { buildSystemInstruction() }
            currentCoroutineContext().ensureActive()
            val connection = GeminiLiveClient(apiKey)
            cl = connection
            client = connection
            lastActivityAt = android.os.SystemClock.elapsedRealtime()
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val handshake = launch {
                    try {
                        withTimeout(HANDSHAKE_TIMEOUT_MS) { ready.await() }
                    } catch (e: TimeoutCancellationException) {
                        throw IllegalStateException("Délai de connexion dépassé.")
                    }
                }
                connection.connect(model, instruction, ToolRegistry.declarations(), voice, null)
                    .collect { event ->
                        currentCoroutineContext().ensureActive()
                        when (event) {
                            is LiveEvent.SetupComplete -> if (!ready.isCompleted) {
                                if (!audio.startPlayback()) {
                                    log("Impossible d'acquérir le focus audio.")
                                    _state.value = JarvisState.ERROR
                                    throw IllegalStateException("Focus audio non acquis.")
                                }
                                ready.complete(Unit)
                                handshake.cancel()
                                _sessionReady.value = true
                                _state.value = JarvisState.LISTENING
                                log("Session connectée. Microphone actif.")
                                launch(Dispatchers.IO) {
                                    audio.micFrames().collect { frame ->
                                        currentCoroutineContext().ensureActive()
                                        check(connection.sendAudio(frame)) { "Envoi audio interrompu." }
                                    }
                                }
                                launch {
                                    while (true) {
                                        delay(5_000)
                                        if (container.configStore.wakeWordEnabled.first() &&
                                            android.os.SystemClock.elapsedRealtime() - lastActivityAt > AUTO_SLEEP_MS
                                        ) {
                                            log("Mise en veille après deux minutes sans échange.")
                                            stop()
                                            break
                                        }
                                    }
                                }
                            }
                            is LiveEvent.Error -> throw IllegalStateException("Connexion interrompue.")
                            is LiveEvent.Closed -> throw IllegalStateException("Session fermée.")
                            else -> if (ready.isCompleted) handleEvent(event, connection)
                        }
                    }
                throw IllegalStateException("Session terminée.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.value = JarvisState.ERROR
            log("Session interrompue. Vérifiez la connexion et les autorisations, puis réessayez.")
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                _sessionReady.value = false
                pendingAnnouncements.clear()
                cl?.close()
                client = null
                audio.stopPlayback()
                audio.abandonAudioFocus()
                _conversation.update { finishConversationTurn(it) }
                if (_state.value != JarvisState.ERROR) _state.value = JarvisState.ASLEEP
            }
        }
    }

    private suspend fun handleEvent(event: LiveEvent, cl: GeminiLiveClient) {
        lastActivityAt = android.os.SystemClock.elapsedRealtime()
        when (event) {
            is LiveEvent.AudioChunk -> {
                _state.value = JarvisState.SPEAKING
                withContext(Dispatchers.IO) { audio.playChunk(event.pcm16) }
            }
            is LiveEvent.OutputTranscript -> {
                _conversation.update { appendConversation(it, ConversationRole.ASSISTANT, event.text) }
            }
            is LiveEvent.InputTranscript -> {
                _conversation.update { appendConversation(it, ConversationRole.USER, event.text) }
                _state.value = JarvisState.THINKING
            }
            is LiveEvent.Interrupted -> {
                audio.flushPlayback()
                _conversation.update { finishConversationTurn(it) }
                _state.value = JarvisState.LISTENING
            }
            is LiveEvent.TurnComplete -> {
                _conversation.update { finishConversationTurn(it) }
                _state.value = JarvisState.LISTENING
                flushAnnouncements(cl)
            }
            is LiveEvent.ToolCall -> {
                _state.value = JarvisState.THINKING
                for (call in event.calls) {
                    log("Exécution d’une action.")
                    val result = withContext(Dispatchers.IO) {
                        ToolRegistry.run(call.name, call.args, container)
                    }
                    currentCoroutineContext().ensureActive()
                    check(cl.sendToolResponse(call.id, call.name, result)) { "Réponse non envoyée." }
                }
            }
            else -> Unit
        }
    }

    private companion object {
        const val AUTO_SLEEP_MS = 120_000L
        const val HANDSHAKE_TIMEOUT_MS = 20_000L
        const val MAX_PENDING_ANNOUNCEMENTS = 5
    }

    private suspend fun buildSystemInstruction(): String {
        val assistantName = container.configStore.snapshotAssistantName()
        val userName = container.configStore.snapshotUserName()
        val memoryBlock = container.memoryManager.formatForPrompt()
        val base = readAsset("system_prompt.txt")

        val now = SimpleDateFormat("EEEE, MMMM d, yyyy — hh:mm a", Locale.getDefault()).format(Date())
        val timeCtx = "[CURRENT DATE & TIME]\nRight now it is: $now\nUse this to calculate exact times for reminders.\n"

        val addr = if (userName.isNotBlank()) "ADDRESS: Always call the user '$userName'."
        else "ADDRESS: Address the user with the ordinary respectful form for a superior in the language you are currently speaking."
        val identityCtx = "[IDENTITY]\nYour name is $assistantName. Always refer to yourself as $assistantName.\n$addr\n"

        return listOf(buildLanguageDirective(), timeCtx, identityCtx, memoryBlock, base).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun readAsset(name: String): String {
        return try {
            container.appContext.assets.open(name).use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            }
        } catch (e: Exception) {
            ""
        }
    }
}

internal fun buildLanguageDirective(): String =
    "[LANGUAGE]\n" +
        "The user speaks French (France). ALWAYS reply in French, in speech and in text, with no exceptions. " +
        "This rule overrides the LANGUAGE section below and any other language rule in these instructions. " +
        "Ignore the device locale, the system language, connected accessories (Android Auto, Bluetooth, car systems) and transcription quirks: they never decide the reply language. " +
        "If the user's language is ever ambiguous, default to French. " +
        "Never reply in Spanish or any other language unless the user explicitly asks, in French, to switch languages."
