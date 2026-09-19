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

internal const val MAX_CONSECUTIVE_DROPS = 3
internal const val HEALTHY_SESSION_MS = 30_000L
internal const val RECONNECT_BASE_DELAY_MS = 1_000L
internal const val RECONNECT_MAX_DELAY_MS = 4_000L

/** A connection ended without the user asking for it (network loss, server close, send failure). */
internal class ConnectionDropped(
    val wasReady: Boolean,
    val liveMs: Long,
    val detail: String,
) : Exception(detail)

internal sealed interface ReconnectDecision {
    data object GiveUp : ReconnectDecision

    /** [drops] is the consecutive-drop count to carry into the next decision. */
    data class Retry(val delayMs: Long, val useHandle: Boolean, val drops: Int) : ReconnectDecision
}

/**
 * Decides what to do after a connection dropped.
 *
 * - A fresh connection that never became ready is a configuration or authentication
 *   problem (bad key, unsupported model, no network at all), not a transient drop: give up.
 * - A resumed connection that never became ready means the handle was refused (for example
 *   expired): retry once from scratch, without the handle.
 * - A connection that was ready and dropped is retried, resuming with the last resumable
 *   handle when there is one, with a short exponential backoff. A connection that stayed
 *   ready for [HEALTHY_SESSION_MS] resets the counter, so a long-running session can
 *   recover from any number of well-spaced drops, while a flapping link is abandoned after
 *   [MAX_CONSECUTIVE_DROPS] quick drops.
 */
internal fun decideReconnect(
    wasReady: Boolean,
    hadHandle: Boolean,
    hasHandle: Boolean,
    liveMs: Long,
    consecutiveDrops: Int,
): ReconnectDecision {
    if (!wasReady && !hadHandle) return ReconnectDecision.GiveUp
    val drops = if (wasReady && liveMs >= HEALTHY_SESSION_MS) 1 else consecutiveDrops + 1
    if (drops > MAX_CONSECUTIVE_DROPS) return ReconnectDecision.GiveUp
    val delayMs = (RECONNECT_BASE_DELAY_MS shl (drops - 1)).coerceAtMost(RECONNECT_MAX_DELAY_MS)
    val useHandle = hasHandle && wasReady
    return ReconnectDecision.Retry(delayMs, useHandle, drops)
}

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
    private var resumeHandle: String? = null
    @Volatile private var connectionReadyAt = 0L

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
        resumeHandle = null
        _conversation.value = emptyList()
        _state.value = JarvisState.ASLEEP
    }

    private fun dropped(detail: String): ConnectionDropped {
        val readyAt = connectionReadyAt
        return ConnectionDropped(
            wasReady = readyAt > 0L,
            liveMs = if (readyAt > 0L) android.os.SystemClock.elapsedRealtime() - readyAt else 0L,
            detail = detail,
        )
    }

    private suspend fun runSession() {
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

            resumeHandle = null
            var handleToSend: String? = null
            var consecutiveDrops = 0
            while (true) {
                val drop = try {
                    runConnection(apiKey, model, voice, instruction, handleToSend)
                } catch (d: ConnectionDropped) {
                    d
                }
                when (
                    val decision = decideReconnect(
                        wasReady = drop.wasReady,
                        hadHandle = handleToSend != null,
                        hasHandle = resumeHandle != null,
                        liveMs = drop.liveMs,
                        consecutiveDrops = consecutiveDrops,
                    )
                ) {
                    ReconnectDecision.GiveUp -> {
                        log("Session interrompue. Vérifiez la connexion et les autorisations, puis réessayez.")
                        log("Détail : ${drop.detail}")
                        _state.value = JarvisState.ERROR
                        return
                    }
                    is ReconnectDecision.Retry -> {
                        consecutiveDrops = decision.drops
                        if (!decision.useHandle) resumeHandle = null
                        handleToSend = if (decision.useHandle) resumeHandle else null
                        _state.value = JarvisState.CONNECTING
                        log(
                            if (decision.useHandle) "Connexion perdue : reprise de la session…"
                            else "Connexion perdue : nouvelle session, le contexte n’a pas pu être conservé."
                        )
                        delay(decision.delayMs)
                    }
                }
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
                _conversation.update { finishConversationTurn(it) }
                if (_state.value != JarvisState.ERROR) _state.value = JarvisState.ASLEEP
            }
        }
    }

    /** Runs one WebSocket connection until it drops; always ends by throwing. */
    private suspend fun runConnection(
        apiKey: String,
        model: String,
        voice: String,
        instruction: String,
        handle: String?,
    ): Nothing {
        connectionReadyAt = 0L
        val connection = GeminiLiveClient(apiKey)
        client = connection
        lastActivityAt = android.os.SystemClock.elapsedRealtime()
        try {
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val handshake = launch {
                    try {
                        withTimeout(HANDSHAKE_TIMEOUT_MS) { ready.await() }
                    } catch (e: TimeoutCancellationException) {
                        throw dropped("Délai de connexion dépassé.")
                    }
                }
                connection.connect(model, instruction, ToolRegistry.declarations(), voice, handle)
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
                                connectionReadyAt = android.os.SystemClock.elapsedRealtime()
                                _sessionReady.value = true
                                _state.value = JarvisState.LISTENING
                                log(
                                    if (handle != null) "Session reprise. Microphone actif."
                                    else "Session connectée. Microphone actif."
                                )
                                launch(Dispatchers.IO) {
                                    audio.micFrames().collect { frame ->
                                        currentCoroutineContext().ensureActive()
                                        if (!connection.sendAudio(frame)) throw dropped("Envoi audio interrompu.")
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
                            is LiveEvent.ResumptionUpdate -> {
                                resumeHandle = event.handle
                            }
                            is LiveEvent.Error -> throw dropped("Erreur réseau.")
                            is LiveEvent.Closed -> throw dropped("Session fermée (${event.code}) : ${event.reason.take(160)}")
                            else -> if (ready.isCompleted) handleEvent(event, connection)
                        }
                    }
                throw dropped("Session terminée.")
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                _sessionReady.value = false
                connection.close()
                if (client === connection) client = null
                audio.stopPlayback()
                audio.abandonAudioFocus()
                _conversation.update { finishConversationTurn(it) }
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
                    if (!cl.sendToolResponse(call.id, call.name, result)) throw dropped("Réponse non envoyée.")
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
