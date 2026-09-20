package com.jarvis.android.core

import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.JarvisContainer
import com.jarvis.android.actions.ToolRegistry
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.jarvis.android.memory.BRIEFING_TRIGGER
import com.jarvis.android.memory.buildSummaryRequest
import com.jarvis.android.memory.transcriptForSummary
import com.jarvis.android.memory.worthSummarizing
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong

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
    /** The server itself closed the socket (a close frame), as opposed to a transport failure. */
    val serverClosed: Boolean = false,
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
 * - A resumed connection that never became ready because the server closed it means the
 *   handle was refused (for example expired): retry once from scratch, without the handle.
 *   If it failed for a transport reason (the network was still changing), the handle is
 *   probably still good: retry with it.
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
    serverClosed: Boolean = false,
): ReconnectDecision {
    if (!wasReady && !hadHandle) return ReconnectDecision.GiveUp
    val drops = if (wasReady && liveMs >= HEALTHY_SESSION_MS) 1 else consecutiveDrops + 1
    if (drops > MAX_CONSECUTIVE_DROPS) return ReconnectDecision.GiveUp
    val delayMs = (RECONNECT_BASE_DELAY_MS shl (drops - 1)).coerceAtMost(RECONNECT_MAX_DELAY_MS)
    val useHandle = hasHandle && (wasReady || !serverClosed)
    return ReconnectDecision.Retry(delayMs, useHandle, drops)
}

class JarvisEngine(
    private val container: JarvisContainer,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(JarvisState.ASLEEP)
    val state: StateFlow<JarvisState> = _state.asStateFlow()

    private val _outputLevel = MutableStateFlow(0f)

    /** Loudness (0..1) of the latest audio chunk Jarvis played; only meaningful while speaking. */
    val outputLevel: StateFlow<Float> = _outputLevel.asStateFlow()

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
    private val endOfSession = EndOfSession()
    private val _videoSource = MutableStateFlow(VideoSource.OFF)

    /** What the live session currently sees, if anything. */
    val videoSource: StateFlow<VideoSource> = _videoSource.asStateFlow()
    private var videoJob: Job? = null
    private var wakeDetector: WakeDetector? = null
    private var wakeIsOffline = false
    private var wakeClassifier = ""
    @Volatile private var wakeThreshold = com.jarvis.android.wake.WAKE_THRESHOLD
    private val pendingAnnouncements = ArrayDeque<String>()
    private var resumeHandle: String? = null
    @Volatile private var connectionReadyAt = 0L

    init {
        // A session that ends in an error, or that the system cut, still gets its end time in the history.
        scope.launch {
            // The first value is the initial state at start-up: it says nothing about a session that a
            // killed process left unfinished, which correctly stays "en cours ou interrompue".
            state.drop(1).collect { if (it == JarvisState.ASLEEP || it == JarvisState.ERROR) container.sessionLog.ended() }
        }
        scope.launch {
            container.configStore.wakeSensitivity.collect { wakeThreshold = com.jarvis.android.wake.wakeThresholdFor(it) }
        }
        scope.launch {
            combine(container.configStore.wakeWordEnabled, state) { enabled, s -> enabled to s }
                .collect { (enabled, s) -> updateWakeDetection(enabled, s) }
        }
    }

    /** Re-evaluates which wake-word detector to use, for example after the offline model was installed or removed. */
    fun refreshWakeDetection() {
        scope.launch { updateWakeDetection(container.configStore.wakeWordEnabled.first(), state.value) }
    }

    private fun updateWakeDetection(enabled: Boolean, s: JarvisState) {
        if (!enabled || s != JarvisState.ASLEEP) {
            wakeDetector?.stop()
            return
        }
        if (ContextCompat.checkSelfPermission(container.appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log(tr("Mot d’activation en pause : autorisation du microphone manquante."))
            wakeDetector?.stop()
            return
        }
        val offline = container.wakeModel.installed()
        val classifier = if (offline) container.wakeModel.selected() else ""
        if (wakeDetector == null || wakeIsOffline != offline || wakeClassifier != classifier) {
            wakeDetector?.stop()
            wakeDetector = if (offline) {
                com.jarvis.android.wake.OpenWakeWordDetector(container.appContext, container.wakeModel.dir, classifier, { wakeThreshold }, { log(tr(it)) }) { toggleAwake(SessionTrigger.WAKE_WORD) }
            } else {
                WakeWordDetector(container.appContext) { toggleAwake(SessionTrigger.WAKE_WORD) }
            }
            wakeIsOffline = offline
            wakeClassifier = classifier
        }
        val detector = wakeDetector!!
        if (!detector.isAvailable) {
            log(tr("Mot d’activation indisponible sur cet appareil."))
            return
        }
        detector.start()
        log(tr(if (wakeIsOffline) "Mot d’activation : écoute hors ligne démarrée." else "Mot d’activation : écoute (reconnaissance d’Android) démarrée."))
    }

    private fun log(message: String) {
        _activityLog.update { (it + message).takeLast(200) }
    }

    suspend fun start(trigger: SessionTrigger = SessionTrigger.UNKNOWN) {
        val version = stopVersion.get()
        lifecycle.withLock {
            withContext(Dispatchers.Main.immediate) {
                if (version == stopVersion.get()) startLocked(trigger)
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

    /**
     * Asks for the session to close once the model has said goodbye (see [EndOfSession]). A timer
     * ends it anyway if the goodbye never completes. False when there is no session to close.
     */
    fun requestEndSession(): Boolean {
        if (state.value == JarvisState.ASLEEP || state.value == JarvisState.ERROR) return false
        endOfSession.request()
        scope.launch {
            delay(END_SESSION_TIMEOUT_MS)
            if (endOfSession.pending) finishEndSession()
        }
        return true
    }

    private suspend fun finishEndSession() {
        if (!endOfSession.pending) return
        endOfSession.reset()
        // Let the last words of the goodbye reach the speaker before the audio is cut.
        delay(END_SESSION_GRACE_MS)
        log(tr("Session terminée à votre demande."))
        stop()
        container.releaseVoiceService()
    }

    /** Sends one camera or screen picture to the session. Returns false when there is no live session. */
    fun sendVideoFrame(jpeg: ByteArray): Boolean = _sessionReady.value && client?.sendVideoFrame(jpeg) == true

    /**
     * Starts or stops sharing the screen or the camera with the live session. The screen is
     * captured here (through the accessibility service); camera frames come from the UI, which owns
     * the camera lifecycle. Returns what to tell the user.
     */
    fun setVideoSource(source: VideoSource): String {
        if (source != VideoSource.OFF && !_sessionReady.value) return tr("Aucune session vocale active : démarrez-la d’abord.")
        videoJob?.cancel()
        videoJob = null
        _videoSource.value = source
        return when (source) {
            VideoSource.OFF -> tr("Partage de l’écran et de la caméra arrêté.")
            VideoSource.CAMERA -> {
                log(tr("Caméra partagée avec la session."))
                tr("Caméra activée. Elle n’envoie des images que si l’écran principal de Jarvis est au premier plan.")
            }
            VideoSource.SCREEN -> {
                if (com.jarvis.android.device.JarvisAccessibilityService.instance == null) {
                    _videoSource.value = VideoSource.OFF
                    return tr("Le contrôle du téléphone n’est pas activé : impossible de partager l’écran (Paramètres > Accessibilité > Jarvis).")
                }
                log(tr("Écran partagé avec la session."))
                videoJob = scope.launch(Dispatchers.Default) { streamScreen() }
                tr("Écran partagé : une image environ toutes les deux secondes, seulement si l’écran change.")
            }
        }
    }

    private suspend fun streamScreen() {
        val gate = FrameGate()
        var pausedForPassword = false
        while (currentCoroutineContext().isActive && _sessionReady.value && _videoSource.value == VideoSource.SCREEN) {
            val service = com.jarvis.android.device.JarvisAccessibilityService.instance
            if (service == null) {
                withContext(Dispatchers.Main.immediate) { _videoSource.value = VideoSource.OFF }
                log(tr("Partage d’écran arrêté : le service d’accessibilité s’est déconnecté."))
                return
            }
            if (service.hasVisiblePasswordField()) {
                if (!pausedForPassword) log(tr("Partage d’écran en pause : un champ de mot de passe est visible."))
                pausedForPassword = true
            } else {
                pausedForPassword = false
                val (jpeg, _) = service.screenshotJpeg(VIDEO_MAX_SIDE)
                if (jpeg != null && gate.shouldSend(android.os.SystemClock.elapsedRealtime(), jpeg.contentHashCode())) {
                    withContext(Dispatchers.Main.immediate) { sendVideoFrame(jpeg) }
                }
            }
            delay(1_200)
        }
    }

    fun toggleAwake(trigger: SessionTrigger = SessionTrigger.APP_BUTTON) {
        if (state.value == JarvisState.ASLEEP || state.value == JarvisState.ERROR) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { start(trigger) }
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
            log(tr("Message non envoyé. Vérifiez la session puis réessayez."))
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

    private suspend fun startLocked(trigger: SessionTrigger) {
        if (sessionJob?.isActive == true) return
        endOfSession.reset()
        container.sessionLog.started(trigger)
        log(trf("Session lancée par : {0}.", trigger.label))
        sessionJob?.join()
        _conversation.value = emptyList()
        _sessionReady.value = false
        _state.value = JarvisState.CONNECTING
        sessionJob = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            runSession()
        }.also { it.start() }
    }

    private suspend fun stopLocked() {
        endOfSession.reset()
        container.sessionLog.ended()
        videoJob?.cancel()
        videoJob = null
        _videoSource.value = VideoSource.OFF
        container.agent.cancel()
        val finished = _conversation.value
        if (worthSummarizing(finished)) scope.launch(Dispatchers.IO) { summarizeSession(finished) }
        _sessionReady.value = false
        pendingAnnouncements.clear()
        sessionJob?.cancelAndJoin()
        sessionJob = null
        resumeHandle = null
        _conversation.value = emptyList()
        _state.value = JarvisState.ASLEEP
    }

    /** Keeps a one-line memory of the session that just ended, for tomorrow's briefing. Best effort. */
    private suspend fun summarizeSession(messages: List<ConversationMessage>) =
        com.jarvis.android.rest.summarizeConversation(container, messages)

    private fun dropped(detail: String, serverClosed: Boolean = false): ConnectionDropped {
        val readyAt = connectionReadyAt
        return ConnectionDropped(
            wasReady = readyAt > 0L,
            liveMs = if (readyAt > 0L) android.os.SystemClock.elapsedRealtime() - readyAt else 0L,
            detail = detail,
            serverClosed = serverClosed,
        )
    }

    private suspend fun runSession() {
        try {
            val apiKey = container.configStore.getApiKey()
            if (apiKey.isNullOrBlank()) {
                log(tr("Aucune clé API Gemini configurée."))
                _state.value = JarvisState.ERROR
                return
            }
            if (container.configStore.wakeWordEnabled.first()) {
                log(tr("Session démarrée : détection du mot d’activation en pause jusqu’à la mise en veille."))
            }
            val model = container.configStore.snapshotModel()
            val voice = container.configStore.snapshotVoice()
            val instruction = withContext(Dispatchers.IO) { buildSystemInstruction(container) }
            currentCoroutineContext().ensureActive()

            val language = container.configStore.speechLanguage.first()
            val muteWhileSpeaking = container.configStore.muteMicWhileSpeaking.first()
            resumeHandle = null
            var handleToSend: String? = null
            var consecutiveDrops = 0
            var tuneDetection = true
            while (true) {
                val drop = try {
                    runConnection(apiKey, model, voice, instruction, handleToSend, language.ifBlank { null }, tuneDetection, muteWhileSpeaking)
                } catch (d: ConnectionDropped) {
                    d
                }
                if (tuneDetection && !drop.wasReady && drop.detail.contains("(1007)")) {
                    // The server refused the setup: most likely the voice-detection tuning. Retry once without it.
                    tuneDetection = false
                    log(tr("Réglage de détection vocale refusé par le serveur : nouvel essai sans."))
                    continue
                }
                when (
                    val decision = decideReconnect(
                        wasReady = drop.wasReady,
                        hadHandle = handleToSend != null,
                        hasHandle = resumeHandle != null,
                        liveMs = drop.liveMs,
                        consecutiveDrops = consecutiveDrops,
                        serverClosed = drop.serverClosed,
                    )
                ) {
                    ReconnectDecision.GiveUp -> {
                        log(tr("Session interrompue. Vérifiez la connexion et les autorisations, puis réessayez."))
                        log(trf("Détail : {0}", drop.detail))
                        _state.value = JarvisState.ERROR
                        return
                    }
                    is ReconnectDecision.Retry -> {
                        consecutiveDrops = decision.drops
                        if (!decision.useHandle) resumeHandle = null
                        handleToSend = if (decision.useHandle) resumeHandle else null
                        _state.value = JarvisState.CONNECTING
                        log(
                            if (decision.useHandle) trf("Connexion perdue ({0}) : reprise de la session…", drop.detail)
                            else trf("Connexion perdue ({0}) : nouvelle session, le contexte n’a pas pu être conservé.", drop.detail)
                        )
                        delay(decision.delayMs)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.value = JarvisState.ERROR
            log(tr("Session interrompue. Vérifiez la connexion et les autorisations, puis réessayez."))
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
        languageCode: String?,
        tuneDetection: Boolean,
        muteWhileSpeaking: Boolean,
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
                        throw dropped(tr("Délai de connexion dépassé."))
                    }
                }
                connection.connect(model, instruction, ToolRegistry.declarations(), voice, handle, languageCode, tuneDetection)
                    .collect { event ->
                        currentCoroutineContext().ensureActive()
                        when (event) {
                            is LiveEvent.SetupComplete -> if (!ready.isCompleted) {
                                if (!audio.startPlayback()) {
                                    log(trf("Focus audio refusé par Android ({0}) : la lecture continue sans, mais Android peut couper le son.", audio.focusDiagnostic()))
                                }
                                ready.complete(Unit)
                                handshake.cancel()
                                connectionReadyAt = android.os.SystemClock.elapsedRealtime()
                                _sessionReady.value = true
                                _state.value = JarvisState.LISTENING
                                launch { if (container.briefing.consumeTrigger()) announce(BRIEFING_TRIGGER) }
                                log(
                                    if (handle != null) tr("Session reprise. Microphone actif.")
                                    else tr("Session connectée. Microphone actif.")
                                )
                                launch(Dispatchers.IO) {
                                    audio.micFrames().collect { frame ->
                                        currentCoroutineContext().ensureActive()
                                        // Half-duplex on the loudspeaker: while Jarvis talks, send silence so his own
                                        // voice cannot make the server think we interrupted him.
                                        val out = if (muteWhileSpeaking && audio.isPlaybackActive() && audio.playsOnLoudspeaker()) ByteArray(frame.size) else frame
                                        if (!connection.sendAudio(out)) throw dropped(tr("Envoi audio interrompu."))
                                    }
                                }
                                launch {
                                    while (true) {
                                        delay(5_000)
                                        if (container.configStore.wakeWordEnabled.first() &&
                                            android.os.SystemClock.elapsedRealtime() - lastActivityAt > AUTO_SLEEP_MS
                                        ) {
                                            log(tr("Mise en veille après deux minutes sans échange."))
                                            stop()
                                            break
                                        }
                                    }
                                }
                            }
                            is LiveEvent.ResumptionUpdate -> {
                                resumeHandle = event.handle
                            }
                            is LiveEvent.Error -> throw dropped(tr("Erreur réseau."))
                            is LiveEvent.Closed -> throw dropped(trf("Session fermée ({0}) : {1}", event.code, event.reason.take(160)), serverClosed = true)
                            else -> if (ready.isCompleted) handleEvent(event, connection)
                        }
                    }
                throw dropped(tr("Session terminée."))
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
                _outputLevel.value = pcm16Level(event.pcm16)
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
                log(tr("Interruption détectée : la phrase en cours est coupée."))
                audio.flushPlayback()
                _conversation.update { finishConversationTurn(it) }
                _state.value = JarvisState.LISTENING
            }
            is LiveEvent.TurnComplete -> {
                if (endOfSession.turnCompleted()) {
                    _conversation.update { finishConversationTurn(it) }
                    finishEndSession()
                    return
                }
                _conversation.update { finishConversationTurn(it) }
                _state.value = JarvisState.LISTENING
                flushAnnouncements(cl)
            }
            is LiveEvent.ToolCall -> {
                _state.value = JarvisState.THINKING
                for (call in event.calls) {
                    log(tr("Exécution d’une action."))
                    val result = withContext(Dispatchers.IO) {
                        ToolRegistry.run(call.name, call.args, container)
                    }
                    currentCoroutineContext().ensureActive()
                    if (!cl.sendToolResponse(call.id, call.name, result)) throw dropped(tr("Réponse non envoyée."))
                    endOfSession.toolResponseSent()
                }
            }
            else -> Unit
        }
    }

    private companion object {
        const val AUTO_SLEEP_MS = 120_000L
        const val END_SESSION_TIMEOUT_MS = 12_000L
        const val END_SESSION_GRACE_MS = 1_500L
        const val HANDSHAKE_TIMEOUT_MS = 20_000L
        const val MAX_PENDING_ANNOUNCEMENTS = 5
    }
}

internal fun buildLanguageDirective(defaultLanguage: String = "French (France)"): String =
    "[LANGUAGE]\n" +
        "The user speaks $defaultLanguage by default: start every session in ${defaultLanguage.substringBefore(" (")}, in speech and in text. " +
        "The ONLY thing that changes the reply language is an explicit request from the user, in any language, to speak or answer in another language, " +
        "for example English or Tagalog (Filipino). When they ask, switch at once and answer in that language, in speech and in text, " +
        "until they ask for another language or for French again. Speak Tagalog and English naturally, as a native speaker would. " +
        "This rule overrides the LANGUAGE section below and any other language rule in these instructions. " +
        "Ignore the device locale, the system language, connected accessories (Android Auto, Bluetooth, car systems), stored memories and transcription quirks: they never decide the reply language. " +
        "Never switch language on your own because of an accent, a foreign word or background speech. " +
        "If the language is ever ambiguous, use the one currently in use, or ${defaultLanguage.substringBefore(" (")} at the start of a session."
