# Jarvis Android

An Android port of [FatihMakes/Mark-LIII](https://github.com/FatihMakes/Mark-LIII) — a
real-time voice AI assistant built on the Gemini Live API. This is a from-scratch
Kotlin/Jetpack Compose app, not a wrapper: there is no first-party Android/Kotlin SDK
for Gemini's Live (BidiGenerateContent) API, so it talks the WebSocket protocol
directly (see [`core/GeminiLiveClient.kt`](app/src/main/java/com/jarvis/android/core/GeminiLiveClient.kt)
and [`core/LiveProtocol.kt`](app/src/main/java/com/jarvis/android/core/LiveProtocol.kt)).

## Status

Core engine + a first batch of skills are implemented and the app **builds
(`./gradlew assembleDebug` succeeds)**. It has not yet been run on a device/emulator
and exercised end-to-end — do that before relying on it. This is an actively
in-progress port; see "Not ported" below for what's intentionally missing so far.

## Architecture

| Desktop (Mark-LIII, Python) | Android (this repo, Kotlin) |
|---|---|
| `main.py` (`JarvisLive`, `google-genai` Live session) | [`core/JarvisEngine.kt`](app/src/main/java/com/jarvis/android/core/JarvisEngine.kt) + [`core/GeminiLiveClient.kt`](app/src/main/java/com/jarvis/android/core/GeminiLiveClient.kt) |
| `core/wake_word.py` (openWakeWord, fully offline ONNX) | [`core/WakeWordDetector.kt`](app/src/main/java/com/jarvis/android/core/WakeWordDetector.kt) — Android's on-device `SpeechRecognizer`, restarted in short bursts. **Not equivalent**: openWakeWord never touches the network; `SpeechRecognizer` on most OEM builds briefly does even in "prefer offline" mode. Treat this as a stopgap until a TFLite wake-word model is wired in. |
| `core/confirm.py` (real user confirmation for irreversible actions) | [`core/ConfirmManager.kt`](app/src/main/java/com/jarvis/android/core/ConfirmManager.kt) |
| `core/undo.py` | [`core/UndoManager.kt`](app/src/main/java/com/jarvis/android/core/UndoManager.kt) |
| `core/action_loader.py` + `plugins/` (runtime file auto-discovery) | [`actions/ToolRegistry.kt`](app/src/main/java/com/jarvis/android/actions/ToolRegistry.kt) — a compile-time list instead. Android can't safely load and execute arbitrary code dropped onto the device at runtime, so "one file, no core edits" survives as a Kotlin object implementing `Tool`, registered once in `ToolRegistry.ALL`. |
| `memory/memory_manager.py` | [`memory/MemoryManager.kt`](app/src/main/java/com/jarvis/android/memory/MemoryManager.kt) — same design: nothing is silently forgotten, only a budgeted "core" rides in every prompt, the rest is recalled on demand. |
| `config/api_keys.json` + UI settings | [`memory/ConfigStore.kt`](app/src/main/java/com/jarvis/android/memory/ConfigStore.kt) — API key in `EncryptedSharedPreferences`, everything else in DataStore. |
| `dashboard/` (remote control from your phone) | N/A — the assistant already *is* the phone. |

## Ported skills (`actions/`)

`web_search`, `weather_report`, `open_app`, `browser_control`, `reminder`,
`system_monitor` (battery/storage), `device_settings` (volume; Wi-Fi/brightness
panels), `send_message` (share-sheet prefill), `youtube_video`, `read_clipboard`,
`code_helper`, `recall_memory`, `remember_fact`, `forget_fact`, `undo`.

## Not ported — no Android equivalent

Mouse/keyboard automation, desktop/taskbar/window management (`computer_control`,
`desktop.py`), Steam/Epic game updates (`game_updater`), full desktop screen capture
(`screen_processor`), and the remote dashboard all depend on APIs a sandboxed phone
app cannot reach. `flight_finder`, `file_processor`, `background_monitor`/`proactive`,
and the multi-step `dev_agent`/agent-mode planner are not yet ported (not impossible
on Android, just not done in this pass).

## Setup

1. Open in Android Studio (or `./gradlew assembleDebug` from the CLI — requires the
   Android SDK; see `local.properties`).
2. Get a Gemini API key at [aistudio.google.com/apikey](https://aistudio.google.com/apikey).
3. Run the app, paste the key on first launch. It's stored encrypted, on-device only.
4. Grant microphone (and, on Android 13+, notification) permission when prompted.

## Known gaps / next steps

- Not yet run on a device/emulator — do that before trusting it end to end.
- Wake word is a `SpeechRecognizer`-based approximation (see table above) — swapping
  in a real on-device model only means replacing `WakeWordDetector.kt`.
- `web_search` always uses the DuckDuckGo path (Gemini's Grounded Search tool isn't
  wired into the raw Live WebSocket protocol here).
- The default Live model (`ConfigStore.DEFAULT_MODEL`) is a stable, documented one —
  override it in Settings once you have access to a newer preview model.
