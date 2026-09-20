# Jarvis Android

An Android port of [FatihMakes/Mark-LIII](https://github.com/FatihMakes/Mark-LIII) — a
real-time voice AI assistant built on the Gemini Live API. This is a from-scratch
Kotlin/Jetpack Compose app, not a wrapper: there is no first-party Android/Kotlin SDK
for Gemini's Live (BidiGenerateContent) API, so it talks the WebSocket protocol
directly (see [`core/GeminiLiveClient.kt`](app/src/main/java/com/jarvis/android/core/GeminiLiveClient.kt)
and [`core/LiveProtocol.kt`](app/src/main/java/com/jarvis/android/core/LiveProtocol.kt)).

## Status

Version 0.3.0. The voice loop works end to end on a real phone: microphone →
Gemini Live (`models/gemini-3.8-live`) → spoken reply with live transcripts. The
unit-test suite (107 tests, `./gradlew :app:testDebugUnitTest`) passes. This is an
actively in-progress port; see "Not ported" and "Known gaps" below.

Still unverified on a device: reminders re-armed after a reboot, timers, flight search, and
the wake word. Reconnection after a real network drop was tested by cutting the phone's Wi-Fi
(see "Connection drops" below).

## Build variants

| Variant | Application id | App name | Notes |
|---|---|---|---|
| `debug` | `com.jarvis.android.dev` | Jarvis Dev | Installs next to the release app. |
| `release` | `com.jarvis.android` | Jarvis Dev | Signed with your own key; minification is off. |

Both variants currently share one label (`app_name` in `res/values/strings.xml`), so on a
phone with both installed they look identical — tell them apart by the `-dev` version name.

Release signing keys are read from `local.properties` (never committed):

```
jarvis.keystore.path=keystore/jarvis-release.keystore
jarvis.keystore.password=...
jarvis.key.alias=...
jarvis.key.password=...
```

If any of the four is missing, Gradle prints a warning, debug builds still work, and the
release APK comes out unsigned. `keystore/`, `*.keystore` and `*.jks` are git-ignored.

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

`web_search`, `flight_search` (web results + Google Flights link), `weather_report`,
`open_app`, `browser_control`, `reminder` (create / list / cancel, persisted, re-armed after
a reboot, read aloud when due), `timer` (create / list / cancel, read aloud at the end),
`system_monitor` (battery/storage), `device_settings` (volume behind an on-screen
confirmation; Wi-Fi/brightness panels), `send_message` (WhatsApp, Telegram, Messenger or SMS: opens the app with the text ready,
draft only — the user picks the contact and sends; the app cannot type into other apps or press
their buttons, which would need an accessibility service),
`youtube_video`, `read_clipboard`, `code_helper`, `recall_memory`, `remember_fact`,
`forget_fact`, `undo`, `end_session`, and the phone-control tools below.

`end_session` lets you close the voice session by voice ("arrête la session"): the model says
goodbye, and once that turn is complete the session and the foreground service are stopped (a
12 s timer ends it anyway if the goodbye never completes; a 1.5 s pause lets the last words be
heard). In the text chat it does nothing. Not yet checked on a real spoken session.

The HUD also shows the conversation transcript (ephemeral, not stored) and lets you type
a message into the running voice session.

## Not ported — no Android equivalent

Mouse/keyboard automation, desktop/taskbar/window management (`computer_control`,
`desktop.py`), Steam/Epic game updates (`game_updater`), full desktop screen capture
(`screen_processor`), and the remote dashboard all depend on APIs a sandboxed phone
app cannot reach. (These are now covered: file attachments, proactive checks, vision, the audio-device picker and the agent mode.)

## Setup

1. Open in Android Studio (or `./gradlew assembleDebug` from the CLI — requires the
   Android SDK, referenced by `sdk.dir` in `local.properties`).
2. Get a Gemini API key at [aistudio.google.com/apikey](https://aistudio.google.com/apikey).
3. Run the app, paste the key on first launch. It's stored encrypted, on-device only.
4. Grant microphone (and, on Android 13+, notification) permission when prompted.

### Live model and API access

The default Live model is `models/gemini-3.8-live` (`ConfigStore.DEFAULT_MODEL`). Which
models accept the Live (`bidiGenerateContent`) WebSocket depends on your key and project,
and preview ids change often. If a session closes with a "model not found" style error,
open **Paramètres → Options avancées**: *Tester la clé enregistrée (REST)* checks that the key
works at all, and *Lister les modèles compatibles Live* shows the models your key can use as a
list — touch one to select it (it fills the *Modèle Live* field; you can still type any id by
hand). The change applies at the next session start, no rebuild needed.

Close codes seen in practice: `1008 … not found … or is not supported for
bidiGenerateContent` means the model id is not Live-capable. A generic `1007 Request
contains an invalid argument` that repeats for every model, while the REST test passes,
was fixed here by creating a fresh key at aistudio.google.com/apikey (cause not
established).

### Connection drops and stored key

If the connection drops mid-session, the engine reconnects by itself: up to 3 quick attempts
(1 s, 2 s, 4 s backoff), first trying to resume with the latest server resumption handle. If the
server closes that resumed attempt, it starts a fresh session instead and says so in the activity
log. A fresh connection that fails during setup (bad key, unsupported model) is not retried. The
handle lives in memory only and is discarded when you stop the session.

Measured on a real phone (Wi-Fi cut while a session was running, `models/gemini-3.8-live`): the
drop is detected after about 6 s and the app is back on a working session a few seconds later
without user action. **Resuming with the handle did not work**: the server closed every resumed
attempt with `1011 Internal error encountered`, so the fresh session did not carry the model's
context (the on-screen transcript is kept, the model does not remember it). The model may still
answer from long-term memory when it saved a fact with `remember_fact`, which makes "does it
remember?" questions a poor test of resumption. Whether other Live models resume correctly has
not been checked.

The API key is kept in `noBackupFilesDir/jarvis_api_key.enc` (`memory/SecureStore.kt`): AES-256-GCM
with a key held in the Android Keystore, no dependency on the deprecated `security-crypto`
library for new writes. Files in `noBackupFilesDir` are never backed up, so the file cannot
outlive its Keystore key after a restore (the cause of an earlier startup crash). A write goes to a
temporary file, the previous good copy is kept as `.bak`, and the write only counts as successful
once it reads back identical. If the key is really gone the file is wiped and you enter the key
again; a momentarily unavailable Keystore leaves the file untouched.

Older builds stored the key in `EncryptedSharedPreferences`. On first launch it is copied to the new
store and the old one is removed only after the copy has been written and read back
(`security-crypto` stays as a dependency for that migration only). Checked on an emulator: the key
moved and the app opened on the main screen. Not exercised on the real phone.

**Up to 3 keys.** Settings → the *Clé 1 / 2 / 3* chips let you store up to three keys, each in its
own encrypted file (`jarvis_api_key.enc`, `jarvis_api_key_2.enc`, `_3.enc`). The first key is used until
Gemini refuses it: a quota error (429), a refused key (401/403) or a 400 that says the key is invalid.
The request is then repeated with the next key, and that key stays in use until it fails in turn
(`memory/KeyRotation.kt`); after an app restart it starts again from key 1. Other failures (server
errors, a bad model name) do not switch keys, and if every key is refused the last status is
shown. This applies to the text chat, its voice mode and the key test. The Live voice session
connects with the key in use at that moment and does not switch keys by itself. The key values are
never shown in the settings, only which slots are filled.

The stored key can be removed from **Paramètres → Supprimer toutes les clés** (after a
confirmation). This stops the running session, erases every key and returns to the key entry
screen; saving and deleting report whether the write really reached storage.

### Animated reactor core

The circle on the main screen moves with the assistant's state (`ui/ReactorMotion.kt`): still when
asleep or in error, a slow breath while listening, faster while connecting and thinking. While
speaking, the inner core also grows with the loudness of the audio actually being played
(`core/AudioLevel.kt`: RMS of each PCM chunk, exposed as `JarvisEngine.outputLevel`). Checked
on an emulator (size changes during the connecting state, still once in error). The voice-driven
part has not been seen on a device with a real spoken answer. The microphone level is not used
while listening.

### Phone control (accessibility service)

Jarvis can operate any app like a person would: `screen_read` (numbered list of what is visible),
`screen_tap` (by number or visible text), `screen_type`, `screen_scroll`, `screen_swipe` (left =
next photo or page) and `screen_navigate` (back, home, recents, notifications, quick settings).
The model works in small steps: `open_app`, read, one action, read again to check. Android only
allows this through an accessibility service, which **you must switch on yourself**: Settings →
Accessibility → *Jarvis : contrôle du téléphone* (on Xiaomi/MIUI, if greyed out: Settings → Apps →
Jarvis → ⋮ → *Allow restricted settings*). A switch in Jarvis' settings turns the tools off without
touching the system setting, and the system switch can be turned off at any time.

Safeguards (`device/ScreenModel.kt`, `actions/ScreenTools.kt`):
- Taps on buttons that send, pay, delete, install, grant access, accept terms or call, and **every**
  tap inside Settings, the permission dialogs, the package installer and the system UI, need your
  confirmation in a notification with *Confirmer / Annuler* buttons (visible over any app). A newer
  request refuses an older one, and nothing else can act while a confirmation is waiting. The
  system UI is included so the assistant cannot press *Confirmer* on its own notification.
- Password fields are never typed into and their content is never listed.
- Text read on the screen is marked as data, never as instructions; the system prompt tells the
  model so.
- What is read on screen is sent to Gemini to process your request.

Checked on an emulator with the service switched on, through a debug-only adb trigger: reading the
Clock and Settings apps, tapping a tab by its text, an unknown text refused, swipe, scroll, home,
typing without a field refused, and the confirmation notification appearing for a Settings tap
(left unanswered, so nothing was touched). **Not checked:** pressing *Confirmer / Annuler* from the
notification, typing into a real text field, Photos/Messenger flows, and anything on the real phone
(the service has to be enabled there first). Apps that mark their window secure or draw their own
UI without accessibility labels (some games, banking apps) may not be readable.

**Vision (`screen_look`).** Where `screen_read` lists labelled elements, `screen_look` takes a screenshot
through the accessibility service (Android 11+, scaled to 1280 px, JPEG), sends it with the user's question
to the text model (`generateContent`, same key rotation) and returns the answer, for images, games and
unlabelled buttons. The screenshot leaves the phone for Gemini, so it is only taken when asked for; a
window an app marks as secure cannot be captured and the tool says so. Checked: unit tests for the request,
scaling and answer parsing, and on an emulator that the screenshot is captured and the call reaches the
network step (no key there). Not checked: an actual description from Gemini.

**Taking a photo.** `take_photo` opens the camera and presses the shutter through the accessibility
service (`front` for the selfie camera). It reports exactly what it did and never claims the picture
exists: a phone app cannot see whether the camera saved it. If the service is off, or the shutter
button cannot be found (first-run screens, permission dialogs, which Jarvis does not answer for you),
it says no photo was taken. The system prompt also forbids announcing any action as done unless the
tool result says so. Checked on an emulator: a real photo file was created by the shutter press.

**Keeping the service on.** Xiaomi/MIUI and some other systems switch an accessibility service off
when the app is updated (a reinstall from a computer does it too) or killed. Jarvis can switch its own
service back on at start-up and when a tool finds it off, but Android only allows that after a one-time
grant from a computer: `adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS`
(`device/AccessibilityKeeper.kt`; the settings page says whether it is granted). Without it, re-enable the
service by hand. The settings also have buttons for the battery exemption and the MIUI auto-start
screen, which stop MIUI from killing the app in the background.

**Languages.** Sessions start in French. Asking, in any language, to speak English or Tagalog (or another
language) switches the replies and the voice until you ask for another one; nothing switches on its own
because of an accent, the phone's locale or a stored memory. The fixed `languageCode` was removed from the
Live setup so the voice is not pinned to French. Not yet heard with a real voice session.

The debug build also contains a receiver that runs a tool from adb (`DebugToolReceiver`), protected
by the `DUMP` permission so only adb can call it; it is not in the release build.

### More features (all switchable in the settings unless noted)

- **Session summary and morning briefing** (`memory/Briefing*.kt`). When a voice session with at least two
  exchanges ends, Gemini writes a one- or two-sentence summary (kept on the device, three at most). At the
  first session of the day the assistant is asked to give a ~20 s briefing: the last summary and today's
  reminders. Given once a day; the summary is only consumed when the briefing was really requested.
- **Google search** (`rest/Grounding.kt`). `web_search` first asks Gemini with the Google Search tool and
  returns the answer with numbered sources; on any failure it falls back to DuckDuckGo as before.
- **Audio device picker** (`core/AudioRoute.kt`). Choose the microphone and the output in the settings
  (automatic by default). A saved device that is unplugged is ignored. Applied to the Live session, the wake
  word and the text-chat voice.
- **Background checks** (`proactive/`, off by default). Every ~15 minutes, locally and without network:
  low battery (once until it recovers), low storage (once a day) and a morning notification (7 h to 11 h)
  with the last summary and today's reminders.
- **File attachments** (`files/`, paperclip in the main screen and the chat). One file in memory (15 MB max):
  PDF, images, audio and text are sent to Gemini as they are, Word (.docx) as extracted text. The `analyze_file`
  tool answers a question about it; nothing leaves the phone before that.
- **Agent mode** (`agent/`). `agent_task` runs a multi-step goal in the background with the same tools (not
  itself, not `end_session`), up to 25 tool rounds and 5 minutes; it returns at once, and the summary is
  spoken and posted in the chat when done. Sensitive actions still ask for confirmation; stopping the voice
  session cancels it.
- **Screen or camera to the Live session** (`core/VideoSource.kt`, `ui/CameraStreamer.kt`). Buttons on the
  main screen, or the `vision_stream` tool. About one picture every 1.5 s, never twice the same one, 768 px
  JPEG. The screen goes through the accessibility service (paused while a password field is visible); the camera
  only runs while the main screen is in front. The red banner and the service notification say when it is on.
  Not verified against a real Live session.
- **Offline wake word** (`wake/`). Settings → wake word → *Télécharger les modèles* fetches the three openWakeWord
  files (~4 MB, from github.com/dscripka/openWakeWord) that the desktop version also uses; the detector listens
  to the microphone in 80 ms steps, offline. Without the models the older Android speech recognizer is used.
  Measured with synthetic voices on an emulator: an English voice says "hey Jarvis" at a score of 1.0, a French voice
  reading it with an accent about 0.2 (so a French speaker may need the *Sensible* level, threshold 0.15), and
  "hey Travis" 0.38 to 0.75 depending on speed (so *Prudente*, 0.75, in noisy places). **Not tested with a human
  voice**, so the level to use is yours to find. A first try with a Vosk keyword model was dropped: "jarvis" is
  not in its vocabulary.

### Permanent wake-word listening

Android only gives the microphone to an app that is in front or that runs a foreground service, so
listening for the wake word needs one. With the wake word switched on and the microphone allowed, the
voice service now stays alive in *standby* (`core/VoiceServiceControl.kt`): a permanent notification "Jarvis ·
écoute du mot d'activation", the system's microphone indicator on, and the detector listening while no session
runs. Saying the wake word opens a session; when it ends (or after two idle minutes) Jarvis goes back to
standby. The notification button ends a session, or, in standby, switches the wake word off; switching it off in
the settings stops the service. The service is started when the app is opened or the setting is changed,
because Android refuses to start a microphone service from the background: after a reboot, or if the system
kills the app, open Jarvis once to resume listening. On Xiaomi/MIUI also allow auto-start and unrestricted
battery use (buttons in the settings) or the system may kill it. Continuous listening costs battery.

**Session history.** Settings → *Historique des sessions* lists the last 30 voice sessions with what started them
(wake word, the app's button, or unknown), the time and the length; a session whose process was killed shows
"en cours ou interrompue". Kept in a small file on the device that is never backed up, and the activity log
shows "Session lancée par : …" too. Checked on an emulator (button start, duration, error end).

Checked on an emulator: enabling the setting starts a microphone foreground service and the notification,
listening continues with the app closed, and disabling the setting stops the service and frees the microphone.
Not checked on the real phone with a real voice.

### File management and device controls

- **`file_manager`** (`filemanager/`, port of Mark-LIII's `file_controller`). Works only inside **one folder chosen by
  the user** (Settings → *Dossier de travail*, through Android's folder picker, which refuses the storage root;
  the access can be withdrawn there). Actions: list, info, read (start of a text file), find (name/extension),
  largest, usage, create file/folder, write/append, rename, move, copy, delete and organize (sorts the files of a
  folder into Images/Documents/Audio/Videos/Archives/Apps/Autres). Paths are relative to the folder and `..`
  is refused. Delete moves to a `.jarvis_trash` folder inside it (nothing is erased for good), and deleting,
  overwriting and organizing ask for confirmation in the notification. Every change is registered for `undo`,
  and undoing a delete will not overwrite a newer file.
  Checked with unit tests on an in-memory tree (22 cases) and on an emulator on a real folder: list, read, create,
  rename, copy, move, find, largest, usage, undo and the `..` refusal, then confirmed on disk. **Not exercised
  on a device:** the confirmation of delete/write/organize (it needs a tap on the notification), and any
  provider other than the emulator's.
- **`device_settings`** now also sets brightness (needs the "modify system settings" special access, which the app
  opens for you to grant), switches the flashlight, presses media keys (play/pause, next, previous, stop), locks
  the screen and takes a screenshot (both through the accessibility service), and opens named settings pages
  (Wi-Fi, Bluetooth, airplane, display, sound, battery, location, apps, storage, NFC, date, language,
  accessibility, security, network). Media keys and screenshots cannot be verified: the tool says so.

### Plugins and self-knowledge

**Plugins** (`plugins/`, the Android counterpart of Mark-LIII's `plugins/` folder). A plugin is one JSON file,
imported in Settings → *Plugins*; it becomes a tool at the next voice session. Jarvis never runs downloaded code:
a plugin only describes one of three declarative actions, and the file is checked before it is kept.

```json
{ "name": "meteo_ville", "description": "What the assistant reads to decide when to use it (10-500 chars).",
  "parameters": [{ "name": "city", "description": "Nom de la ville", "required": true }],
  "type": "http", "url": "https://wttr.in/{city}?format=3" }
```

- `"type": "http"`: GET or POST (`"method"`, `"body"` as a JSON template) to an **https** address, optional `"result_path"`
  (dotted path such as `current.temp`) to return one value. Localhost, private networks and `.local`/`.internal`
  names are refused, and a parameter cannot be in the host. Values are URL-encoded (or JSON-escaped in a body), the
  answer is capped, and it is handed to the model marked as data, never as instructions.
- `"type": "open"`: opens `https://`, `geo:`, `tel:`, `mailto:` or `sms:` links, parameters URL-encoded.
- `"type": "routine"`: up to 10 `steps` of `{ "tool", "args" }` that call **built-in tools only** (not other plugins,
  not `agent_task` or `end_session`), with `{parameter}` filled in. Sensitive taps still ask for confirmation.

Limits: 20 plugins, 5 parameters each, 20 000 characters per file, a name that is not a built-in tool's.
Examples are in `plugins-examples/`. Checked with unit tests (29 cases, including the refusals) and on an emulator with
the real network: a weather call, a Maps link and a two-step routine ran, a missing parameter was reported and a
plugin aimed at a private address was not loaded. Not exercised: importing through the file picker on a phone.

**Self-knowledge** (`core/SelfKnowledge.kt`, after Mark-LIII's runtime self-knowledge). Each session's system prompt
now contains a block generated from the real state: the assistant's name, the phone model and Android version,
the tools and plugins actually present, whether phone control, the work folder, the wake word, the permissions
(microphone, camera, notifications), the API-key count and the optional features are on, and a fixed list of
limits (no sending, paying or typing passwords on its own, no way to confirm a photo or a media key, live view is
not real time, files only in the work folder). Something switched off is reported as off, with what to enable.

### Text chat (REST)

The chat icon in the top bar opens a text conversation over plain `generateContent`
(`rest/`), with no microphone and no Live WebSocket, so it works even when the key has no Live
access. It uses the same system prompt and the same tools as the voice session (a tool call is
run, its result is sent back, up to 6 rounds); actions that need confirmation show the usual
banner. The model is set in **Paramètres → Modèle texte (chat)** (default
`models/gemini-3.6-flash`) and is also used by the key test. History is in memory only; the
reset button starts over. A failed send restores the draft and shows a specific message
(invalid key or model 400/401/403, unknown model 404, quota 429, server 5xx, no network, blocked
answer).

Checked: unit tests with a fake transport, the error path on an emulator with a fake key, and
a tool round trip on a real phone with a real key (battery question answered through
`system_monitor`, matching `dumpsys battery`).

#### Spoken mode (push-to-talk)

The microphone button in the chat records up to 60 s (16 kHz mono, `rest/VoiceDevices.kt`);
pressing stop sends the recording to `generateContent` as WAV for a verbatim transcription, sends
that text as a normal chat message (tools included), then asks a speech model for the answer and
plays it (24 kHz PCM through an `AudioTrack`). The speech is requested with
`streamGenerateContent?alt=sse` and played while it is still being produced, after a 0.3 s head
start, instead of waiting for the whole audio; timings are logged under the `JarvisRestVoice` tag
(time to first sound, chunk count). Typed messages are not read aloud. The voice is the
one chosen in the settings. The speech model can be set in **Paramètres → Modèle voix**; when left
empty the app reads ListModels and takes the first model that supports `generateContent` and has
"tts" in its name (`SpeechModelResolver`). Each step reports a specific error; if only the speech
step fails, the written answer stays on screen. Leaving the screen cancels a recording or a
playback.

Checked: unit tests with fake recorder, player and transport (round trip, each failure, cancel), and
on an emulator the record → transcribe → error path with a fake key. Not yet checked with a real key:
the transcription quality, the speech-model auto-detection and the actual playback. It does not stop
a running Live voice session; use one or the other.

## Instrumented tests

`app/src/androidTest` holds 14 tests that need a real Android runtime: `SecureStore` on the real
Keystore (round trip, no clear text, backup fallback, a restored file whose key is gone, wrong file
name), the real `AudioRecorder` and `AudioPlayer` (duration captured, second start refused, streamed
playback, stop on request, source errors passed on) and a push-to-talk round trip on the real
recorder and player with a scripted network.

They run inside the app's own process, so they only use a scratch cache directory and throw-away
Keystore aliases and never touch the stored API keys. **Run them on an emulator, not on a phone
that holds your key**: Gradle uninstalls the app afterwards, which erases its data.

```
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

Checked on an emulator (14 of 14 pass). Not covered: `ConfigStore` (the key slots and the migration
from the old encrypted preferences), because it works on the app's real files.

## Known gaps / next steps

- The wake word and the video streaming have not been checked with a real voice or a real Live session.
- Session summaries are only made for voice sessions; the text chat is not summarized.
- Desktop-only features (mouse/keyboard automation, game updaters, the remote dashboard) have no Android
  equivalent and are not planned.
