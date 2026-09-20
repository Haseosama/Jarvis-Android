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

### Weather at your position, plugin catalogue, interface

- **Weather at your position.** `weather_report` no longer needs a city: with none (or "ici", "chez moi"), it reads the phone's
  approximate position once, resolves the town name with Android's geocoder and asks Open-Meteo for the conditions;
  with a named city nothing changes. It needs the location permission (Settings → *Position (météo)* → *Autoriser la
  position*); the position is used for that one request and never stored. Android may refuse location to an app that is
  not in front, so it is most reliable with Jarvis open; the tool then says how to fix it or asks for a city. Checked
  on an emulator (position → town → weather, named city unchanged); not on the real phone.
- **Plugin catalogue.** Settings → *Plugins* lists 11 bundled plugins with an *Installer* button (crypto prices, exchange
  rates, Wikipedia summary, public holidays, Maps search and directions, YouTube, translation, news, recipes, night and
  meeting routines). Each is checked like an imported file, and the same files are in `plugins-examples/`.
  Their web services (CoinGecko, Frankfurter, Wikipedia, Nager.Date) need no key and were called for real while writing them.
- **Interface.** A hue-driven theme with tinted dark surfaces and a gradient background on every screen; a glowing arc
  reactor with rings that turn while the assistant is active and a core that swells with its voice; a state pill;
  chat bubbles (you on the right, Jarvis on the left); a rounded message bar with a send button; and a Settings page made of
  folding cards with icons (identity and voice open, the rest closed). Checked on an emulator with screenshots of the
  home screen, the settings, the plugin list, the chat and the error state.

### Sentence cut off and repeated (echo)

Symptom reported: Jarvis stops in the middle of a sentence and says it again, sometimes with a different voice.
Probable cause (inferred from the code and the audio state of the phone, **not reproduced on a device**): on the
loudspeaker his own voice re-enters the microphone, the server detects "speech", sends `interrupted`, and the model
starts the answer again. Changes:

- The microphone recording now enables the phone's acoustic echo canceller and noise suppressor when available.
- Voice-activity detection is made less sensitive in the setup message (`realtimeInputConfig`). If the server rejects
  it (close code 1007) the session retries once without it.
- While Jarvis speaks on the phone's loudspeaker the app sends silence instead of the microphone signal (half-duplex),
  so he cannot be interrupted by voice in that case. Headset, Bluetooth and USB audio are not affected. Switch it
  off in Settings > Voice.
- Settings > Voice > "Langue de la voix" can pin the language (fixed accent); automatic keeps the ability to switch
  language on request.
- Each interruption is written to the activity log ("Interruption détectée…") so the cause can be checked next time.

### Wake phrase, widget, routines, backup

- **Wake phrase.** openWakeWord models are trained for one phrase each, so the text cannot simply be edited.
  Settings > Wake word offers the ready-made phrases of the openWakeWord release (« Hey Jarvis », « Alexa »,
  « Hey Mycroft », « Hey Rhasspy », downloaded on demand) and **Mon modèle**, which imports a `.tflite` classifier
  you trained yourself (for example « Debout Jarvis », with openWakeWord's `automatic_model_training` notebook).
  Not done here: training a « Debout Jarvis » model, and testing an imported model. Only the file header and size
  are checked on import.
- **Home screen.** A "Parler à Jarvis" widget and a long-press launcher shortcut open the app and start a session.
  Checked on the emulator: the widget receiver is registered and the shortcut is published; the tap itself was not tried.
- **Routines.** "Chaque matin à 7 h, donne-moi la météo": the `routine` tool stores a daily task (optional weekdays).
  A WorkManager job (15-minute granularity, so it can be late, and Android may delay it further in battery saving)
  runs it as a background task (same tools as the agent mode) and posts the result as a notification. A run more than
  3 hours late is skipped. Checked: creating and listing on the emulator, and the due-time logic in unit tests; a real
  scheduled run was not observed.
- **Chat summaries.** Resetting the text chat now keeps a one-line memory of it (if it had at least the same number
  of turns as a voice session) for the morning briefing.
- **Memory backup.** Settings > Sauvegarde de la mémoire exports the memory to a JSON file and restores it.
  The file is not encrypted.
- **Plugins.** New examples: `musique_recherche`, `agenda_evenement`, `heure_monde`, `mode_voiture`.

- **Uninstalling plugins.** Settings > Plugins shows "Désinstaller" (with a confirmation) next to every installed
  plugin, in the installed list and in the catalogue; the catalogue entry can be installed again.
- **Liberty Music.** The built-in `liberty_music` tool can: play a title by name (`play` with a `query`), open the app,
  send play / pause / next / previous / stop media keys, and open a `music.youtube.com` watch, playlist or channel link
  inside Liberty. It is a built-in tool, not a JSON plugin, because JSON plugins cannot send media keys or pin a link to
  one app. Liberty accepts no search request from other apps (checked with adb: `MEDIA_PLAY_FROM_SEARCH` and search
  links do not resolve), so `play` first searches YouTube like the website does (the desktop results page, with a
  consent cookie; the first video is taken) and hands that video link to Liberty. Checked: the search from inside the
  app on the emulator (real network, "daft punk get lucky" finds "Get Lucky (Official Audio)"); on the phone, with adb,
  Liberty opens a watch link and starts playing it, and answers play / pause media keys (`input keyevent`). Not checked:
  the whole chain triggered by voice on the phone, and the app-side media-key call itself (adb uses the system's
  dispatch). Limits: the first search hit can be the wrong version (a live recording, a cover), so the tool reports
  the title found and Jarvis is told to say it; parsing YouTube's web page can break whenever YouTube changes its
  layout; media keys go to the active media app, which is not always Liberty.

- **English interface.** Settings > Appearance > "Langue de l'interface" switches the app screens between French and English
  at once, and remembers the choice. The source text is French; English texts live in `ui/I18nEnglish.kt` (a unit test
  fails if a text shown by the interface has no English version). Checked on the emulator: the switch, the persistence
  after a restart, the HUD and the settings. Also translated: the notifications, the chat error messages, the activity log,
  the audio device names and the session-start labels. Not translated: the accessibility service's name and
  description shown by Android (they follow the phone's language), the answers that tools give to the model, and the
  wording of some settings texts written after the switch was built if they are missing from `I18nEnglish.kt` (the test
  catches those). The assistant's default spoken language now follows the interface language (English interface:
  English by default; it can still switch on request); "Voice language" in Settings > Voice can pin it. Note that
  changing the interface language while a session is open only affects the next session's instructions.
- **Onboarding contrast.** The first screen (API key) had dark text on the dark background since the gradient backdrop
  was introduced; fixed.

- **Quick access** (Settings > Accès rapide):
  - *Assistant.* Jarvis can be chosen as the phone's digital assistant (voice interaction service + session service, and
    a recognition service that recognises nothing, which Android requires before it lists an app as a possible assistant).
    The assistant gesture (long press on the home or power button, depending on the phone) then opens the app and starts a
    voice session. The app never selects itself: the button only opens Android's assistant settings. Checked on the emulator
    with adb: the role manager accepts Jarvis, and the assist key opens Jarvis and starts a session (it then asks for the
    microphone permission, as expected). Not checked: on the Xiaomi or the Samsung, the exact gesture each brand uses, and
    the lock screen (Android asks to unlock first).
  - *Quick settings tile.* A "Jarvis" tile starts a session; the settings button asks Android to add it (Android 13+, the
    user confirms in a system dialog). Checked on the emulator with adb (`cmd statusbar click-tile` starts a session).
  - *Share to Jarvis.* "Share > Jarvis" (text, images, PDF) opens the text chat with the shared item on a card: nothing is
    sent until the user taps Summarise / Translate / Explain. A shared file is attached to the chat like the paperclip does.
    Checked on the emulator with a shared text (card and buttons shown). A shared image or PDF was not tried.

- **Alarms.** The `alarm` tool sets an alarm in the phone's Clock app ("réveille-moi à 7 h", optional label and weekdays)
  through the standard `AlarmClock` intent. Checked on the emulator: the alarm shows up in the Clock app's alarm
  schedule. Android gives no confirmation, so the tool tells the model to say so; Jarvis cannot delete an alarm
  (the standard intent has no way to), it can only open the alarm list.
- **Calls and SMS by contact name.** The `call_contact` tool looks a name up in the contacts (permission requested in
  Settings > Contacts, never automatically), matches it without accents or case, and opens the dialler with the number,
  or an SMS draft. Nothing is dialled or sent by itself, and the numbers are never given to the model (only names and
  types such as "Mobile" when several match). Checked on the emulator with a test contact: with the permission the
  dialler opens on the right contact (only while Jarvis is in front, see below), without it a clear message is
  returned. The matching rules are covered by unit tests.
- **Text chat history.** The text chat is now saved in a private, unencrypted file and comes back after the app is
  closed, with the model's context (text turns only, at most about 200,000 characters, images are never kept).
  Settings > Historique des sessions has a switch to turn it off (it also deletes the file); "Nouvelle conversation"
  deletes it. Covered by unit tests (trimming, reading back, broken file); a full close-and-reopen with a real
  answer from Gemini was not tried.
- **Calendar.** The `calendar` tool reads the phone's calendar ("qu'est-ce que j'ai demain ?": one or several days, time and
  title only) and opens the calendar app's "new event" form prefilled ("ajoute un rendez-vous"): the user saves it, nothing is
  created by itself. The morning briefing and the morning notification now include today's events. The permission is asked
  in Settings > Agenda, never automatically. Event titles are given to the model as data (marked as not instructions) and
  are sent to Gemini when asked for, or at the first session of the day if the briefing is on. Checked on the emulator with
  a local calendar and a test event (the tool listed it with the right time); the all-day handling (stored at UTC midnight)
  and the briefing text are covered by unit tests. Not checked: the events of a Google calendar synced on the phone,
  recurring events, the briefing spoken with events on the phone.
  The "add" form opens another app, so the background-launch limit described above applies.

- **Reading notifications.** The `notifications` tool ("qu'est-ce que j'ai manqué ?") reads the last notifications, newest first
  (app, time, title, start of the text), optionally for one app. It needs "Notification access", which only the user can
  switch on in Android's settings (Settings > Notifications shows the state and opens that screen; on a sideloaded APK the
  option can be greyed out until "Allow restricted settings" is chosen in the app info). Read only: Jarvis cannot open,
  answer or dismiss a notification. They are kept in memory only (the last 60, never written to disk, forgotten when the
  service stops); ongoing ones (media, downloads), group summaries and secret-visibility ones are skipped, and a message
  that contains both a "code / OTP / password" word and a long number is replaced by a placeholder before it can reach
  the model. The content is data from arbitrary senders and is marked as not instructions. Checked on the emulator with
  notification access enabled by adb: newest first, a normal message readable, a verification-code message masked, and a
  clear message when access is off. Not checked: on the Xiaomi or the Samsung (the access screen, and how each brand's
  notification shade feeds the service), messaging apps that hide the text on the lock screen.

- **Limit that applies to every tool that opens another app** (the dialler, the Clock, Liberty Music, Messenger…):
  since Android 10, an app running in the background may not start an activity. Tests show the dialler opens when Jarvis
  is in front and is blocked silently when it is not (the tool still says it opened it). On MIUI/HyperOS there is an
  extra permission, "Display pop-up windows while running in the background" (Settings > Apps > Jarvis > Other
  permissions). Not measured for a session started by the wake word on the phone.

### Holographic avatar (adapted from Mark-LIV)

The centre of the main screen shows an animated human head instead of the reactor core (Settings > Appearance turns it off).
It is an Android adaptation of the avatar of [Mark-LIV](https://github.com/FatihMakes/Mark-LIV) by FatihMakes. **That
work is licensed CC BY-NC 4.0: this avatar, and any app that includes it, may not be used commercially.** The face geometry is
MediaPipe's canonical face model (Apache-2.0). Details and credits: `app/src/main/assets/avatar/NOTICE.txt`; the credit is
also shown in the settings.

- **Face.** Real measured face geometry (468 vertices) with a cranium and a neck built around it, run once through Mark-LIV's
  generator and stored as a 64 KB asset (`head_mesh.bin`). Lit per facet, drawn on Android's canvas: no OpenGL, no extra library.
- **Lip-sync.** Each chunk of Jarvis's voice is analysed (formants: openness from the first, lip spread from the second) and
  fused with the words being spoken (lips close on m, b, p; language independent). The mouth is played on a clock tied
  to the speaker, so it follows what is heard and not what has only arrived over the network. An interruption clears it.
- **Expression.** Brows follow the phrase, the gaze flicks between points, blinks, idle sway; the face looks away while thinking,
  meets your eyes while listening and lowers its lids while asleep.
- **Changed from the original.** Structure lines (creases and silhouette) replace the arbitrary third of edges it drew;
  the lattice lights up as a scan sweeps by; a rim light in the accent colour; lids really close when asleep; a lip-sync clock
  based on the audio playing; half the frame rate while asleep. Colours follow the interface hue.
- **Checked:** on the emulator, the head draws in the idle, listening and thinking states and the mouth opens and closes on a
  synthetic voice (debug-only `DEBUG_AVATAR` broadcast feeding the real analysis path); 40 unit tests (mesh loading, text to
  shapes, formant analysis, fusion, clock, animation). **Not checked:** with Gemini's real voice on a phone (timing of the lips
  against the sound, the value of the 40 ms output-latency guess), frame rate and battery on a real phone, the drawing on
  Android 8 and 9 (there the triangles are drawn one by one, slower).

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
- Desktop-only features (mouse/keyboard automation, game updaters, the remote dashboard) have no Android
  equivalent and are not planned.
