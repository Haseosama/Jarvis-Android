package com.jarvis.android.core

import com.jarvis.android.JarvisContainer
import com.jarvis.android.actions.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class JarvisState { ASLEEP, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }

/**
 * Orchestrates one voice session — the Android counterpart of Mark-LIII's
 * `JarvisLive` class in `main.py`: builds the system prompt (identity + time +
 * memory core), opens the Live socket, pumps mic audio in and speaker audio
 * out, dispatches tool calls through [ToolRegistry], and gates everything
 * behind the wake word when enabled.
 */
class JarvisEngine(
    private val container: JarvisContainer,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(JarvisState.ASLEEP)
    val state: StateFlow<JarvisState> = _state

    private val _activityLog = MutableStateFlow<List<String>>(emptyList())
    val activityLog: StateFlow<List<String>> = _activityLog

    private val audio = AudioEngine()
    private var client: GeminiLiveClient? = null
    private var wakeDetector: WakeWordDetector? = null
    private var sessionJob: Job? = null
    private var micJob: Job? = null
    private var idleWatchJob: Job? = null

    private var resumeHandle: String? = null
    private var lastActivityAt = System.currentTimeMillis()
    private val sessionTranscript = mutableListOf<String>()
    private var pendingOutputTranscript = StringBuilder()

    private val AUTO_SLEEP_MS = 120_000L

    fun log(message: String) {
        container.log(message)
        _activityLog.value = (_activityLog.value + message).takeLast(200)
    }

    /** Entry point: called once the app has mic permission and an API key. */
    suspend fun start() {
        val wakeEnabled = container.configStore.wakeWordEnabled.first()
        if (wakeEnabled) {
            enterWakeWordMode()
        } else {
            connectSession()
        }
    }

    fun stop() {
        sessionJob?.cancel()
        micJob?.cancel()
        idleWatchJob?.cancel()
        wakeDetector?.stop()
        client?.close()
        audio.stopPlayback()
        _state.value = JarvisState.ASLEEP
    }

    /** Manual sleep/wake toggle, exposed to the HUD's mic button. */
    fun toggleAwake() {
        scope.launch {
            if (_state.value == JarvisState.ASLEEP) {
                if (container.configStore.wakeWordEnabled.first()) wakeDetector?.stop()
                connectSession()
            } else {
                sleep()
            }
        }
    }

    private fun enterWakeWordMode() {
        _state.value = JarvisState.ASLEEP
        wakeDetector = WakeWordDetector(container.appContext) {
            log("Wake word detected — 'Hey Jarvis'.")
            scope.launch { connectSession() }
        }
        wakeDetector?.start()
    }

    private suspend fun sleep() {
        sessionJob?.cancel()
        micJob?.cancel()
        idleWatchJob?.cancel()
        client?.close()
        audio.stopPlayback()
        saveSessionSummaryIfAny()
        _state.value = JarvisState.ASLEEP
        if (container.configStore.wakeWordEnabled.first()) {
            wakeDetector?.start()
        }
    }

    private suspend fun connectSession() {
        val apiKey = container.configStore.getApiKey()
        if (apiKey.isNullOrBlank()) {
            _state.value = JarvisState.ERROR
            log("No Gemini API key configured.")
            return
        }
        _state.value = JarvisState.CONNECTING
        wakeDetector?.stop()

        val model = container.configStore.snapshotModel()
        val voice = container.configStore.snapshotVoice()
        val systemInstruction = buildSystemInstruction()
        val cl = GeminiLiveClient(apiKey)
        client = cl
        audio.startPlayback()
        lastActivityAt = System.currentTimeMillis()

        sessionJob = scope.launch(Dispatchers.IO) {
            cl.connect(model, systemInstruction, ToolRegistry.declarations(), voice, resumeHandle)
                .collect { event -> handleEvent(event, cl) }
        }

        micJob = scope.launch(Dispatchers.IO) {
            audio.micFrames().collect { frame ->
                lastActivityAt = System.currentTimeMillis()
                cl.sendAudio(frame)
            }
        }

        idleWatchJob = scope.launch {
            while (true) {
                delay(5_000)
                if (container.configStore.wakeWordEnabled.first() &&
                    System.currentTimeMillis() - lastActivityAt > AUTO_SLEEP_MS
                ) {
                    log("Auto-sleep after 2 minutes of silence.")
                    sleep()
                    break
                }
            }
        }
    }

    private suspend fun handleEvent(event: LiveEvent, cl: GeminiLiveClient) {
        lastActivityAt = System.currentTimeMillis()
        when (event) {
            is LiveEvent.SetupComplete -> {
                _state.value = JarvisState.LISTENING
                log("Connected.")
            }
            is LiveEvent.AudioChunk -> {
                _state.value = JarvisState.SPEAKING
                audio.playChunk(event.pcm16)
            }
            is LiveEvent.OutputTranscript -> {
                pendingOutputTranscript.append(event.text)
            }
            is LiveEvent.InputTranscript -> {
                if (event.text.isNotBlank()) sessionTranscript += "User: ${event.text}"
            }
            is LiveEvent.Interrupted -> {
                audio.flushPlayback()
            }
            is LiveEvent.TurnComplete -> {
                if (pendingOutputTranscript.isNotBlank()) {
                    sessionTranscript += "Jarvis: ${pendingOutputTranscript}"
                    pendingOutputTranscript = StringBuilder()
                }
                _state.value = JarvisState.LISTENING
            }
            is LiveEvent.ToolCall -> {
                _state.value = JarvisState.THINKING
                for (call in event.calls) {
                    log("Tool: ${call.name} ${call.args}")
                    val result = ToolRegistry.run(call.name, call.args, container)
                    cl.sendToolResponse(call.id, call.name, result)
                }
            }
            is LiveEvent.ResumptionUpdate -> {
                resumeHandle = event.handle
            }
            is LiveEvent.Error -> {
                log("Error: ${event.message}")
                _state.value = JarvisState.ERROR
            }
            is LiveEvent.Closed -> {
                log("Session closed (${event.code}): ${event.reason}")
                if (_state.value != JarvisState.ASLEEP) _state.value = JarvisState.ERROR
            }
        }
    }

    private suspend fun saveSessionSummaryIfAny() {
        if (sessionTranscript.isEmpty()) return
        val summary = sessionTranscript.takeLast(4).joinToString(" ").take(280)
        container.memoryManager.saveSessionSummary(summary)
        sessionTranscript.clear()
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

        return listOf(timeCtx, identityCtx, memoryBlock, base).filter { it.isNotBlank() }.joinToString("\n")
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
