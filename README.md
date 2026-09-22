# Jarvis Android

An Android port of [FatihMakes/Mark-LIII](https://github.com/FatihMakes/Mark-LIII) — a
real-time voice AI assistant built on the Gemini Live API. This is a from-scratch
Kotlin/Jetpack Compose app, not a wrapper: there is no first-party Android/Kotlin SDK
for Gemini's Live (BidiGenerateContent) API, so it talks the WebSocket protocol
directly (see [`core/GeminiLiveClient.kt`](app/src/main/java/com/jarvis/android/core/GeminiLiveClient.kt)
and [`core/LiveProtocol.kt`](app/src/main/java/com/jarvis/android/core/LiveProtocol.kt)).

## Status

Version 0.5.x (see `app/build.gradle.kts`; the version goes up with every change, and releases are published on GitHub, see "Updating from
GitHub"). The voice loop works end to end on a real phone: microphone → Gemini Live (`models/gemini-3.8-live`) → spoken reply with live
transcripts. The unit-test suite (about 480 tests, `./gradlew :app:testDebugUnitTest`) passes. Each feature below says what was
checked and what was not; in short, a lot was checked on an emulator (with synthetic voices, or without a Gemini key), and **not yet on a
real phone with a real voice**: the wake word (built-in and taught), the meeting notes with a real Gemini answer, Gmail and Drive, the update
on a Xiaomi, reminders re-armed after a reboot, timers, flight search, and the live video. Reconnection after a real network drop was tested by
cutting the phone's Wi-Fi (see "Connection drops" below).

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
| `core/wake_word.py` (openWakeWord, fully offline ONNX) | [`wake/OpenWakeWordDetector.kt`](app/src/main/java/com/jarvis/android/wake/OpenWakeWordDetector.kt) — the same three openWakeWord models, run with TFLite, fully offline (downloaded on demand, see "Offline wake word"), plus [phrases you teach it yourself](app/src/main/java/com/jarvis/android/wake/WakeLearning.kt). Without the models, [`core/WakeWordDetector.kt`](app/src/main/java/com/jarvis/android/core/WakeWordDetector.kt) falls back to Android's `SpeechRecognizer` (approximate, and it may use the network). |
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
confirmation; Wi-Fi/brightness panels), `send_message` (WhatsApp, Telegram, Messenger or SMS: by default a draft the user sends themselves;
with *automatic sending* switched on it really sends, see "Sending messages on your word"),
`youtube_video`, `read_clipboard`, `code_helper`, `recall_memory`, `remember_fact`,
`forget_fact`, `undo`, `end_session`, and the phone-control tools below.

`end_session` lets you close the voice session by voice ("arrête la session"): the model says
goodbye, and once that turn is complete the session and the foreground service are stopped (a
12 s timer ends it anyway if the goodbye never completes; a 1.5 s pause lets the last words be
heard). In the text chat it does nothing. Not yet checked on a real spoken session.

The HUD also shows the conversation transcript (ephemeral, not stored) and lets you type
a message into the running voice session.

### Mark-LIII / Mark-LIV actions, one by one

| Original (`actions/`) | Here |
|---|---|
| `browser_control`, `web_search`, `weather_report`, `flight_finder`, `youtube_video`, `open_app`, `code_helper`, `send_message`, `system_monitor`, `reminder` | the tools of the same name (`browser_control`, `web_search`, `weather_report`, `flight_search`, `youtube_video`, `open_app`, `code_helper`, `send_message`, `system_monitor`, `reminder`) |
| `computer_settings` | `device_settings` (volume behind a confirmation, Wi-Fi and brightness panels) |
| `file_controller`, `file_processor` | `file_manager` (one work folder you grant, with confirmations and undo) and `analyze_file` (attachments) |
| `screen_processor` | `screen_look`, `vision_stream` (screen or camera shared with the live session), `take_photo` |
| `proactive`, `background_monitor` | background checks (`proactive/`) and the `watch` tool (prices, a site up or down, battery temperature, free memory) |
| `dev_agent` | `agent_task` (multi-step agent mode) and `code_helper`; not "create a project and run it on the machine" |
| `computer_control`, `desktop` | no phone equivalent; the accessibility tools (`screen_read`, `screen_tap`, `screen_type`, `screen_scroll`, `screen_swipe`, `screen_navigate`) drive the phone the way those drive a PC |
| `game_updater`, `dashboard` | none: not applicable to a phone |

Added here and not in the original: `alarm`, `calendar`, `call_contact`, `notifications`, `routine`, `timer`, `liberty_music`, `meeting_notes`,
`create_document`, `gmail`, `drive`, `watch`, `end_session`, `undo`, plus the widgets, the avatar, the taught wake word and the in-app update.

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
`screen_tap` (by number or visible text), `screen_type`, `screen_scroll` (reads the screen itself, so it works when the model calls it straight away by voice; it tries every scrollable area that takes the movement, the largest first, and falls back to a finger swipe for web pages and custom views), `screen_swipe` (left =
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
- **What Jarvis remembers after a session** (`memory/MemoryExtraction.kt`, `rest/RestChat.kt`). Until 0.4.4 it kept only what the model
  explicitly saved with `remember_fact`. Now, when a session with at least two exchanges ends, one Gemini call reads the conversation and
  returns a summary and the lasting facts the user gave about themselves (name, city, tastes, projects, people, wishes: at most 8, never a
  password, a long number or a health detail), which are stored in the memory. The transcript is put aside first and dropped only once the
  answer is stored, so a failed request or a killed app does not lose it: it is played again at the next start (a week, five conversations at
  most). The last three summaries are part of the prompt. *Checked:* the parsing, the safeguards and the waiting queue by unit tests, the storage
  across an app kill on the emulator. *Not checked:* the real Gemini answer (no key on the emulator), so how well the facts are chosen.
- **Google search** (`rest/Grounding.kt`). `web_search` first asks Gemini with the Google Search tool and
  returns the answer with numbered sources; on any failure it falls back to DuckDuckGo as before.
- **Reading a page** (`actions/ReadWebpageTool.kt`, tool `read_webpage`). `web_search` only ever gives short snippets; for a precise fact
  (an exact figure, a date, a quote, something the snippets disagree on) the model is told to open one of its links itself and read the
  page — its own judgment, no need to ask the user first — and it may follow a link the page lists to keep going, the way someone clicks
  through a site to find something. Fetches the page (HTTPS or HTTP, private and local addresses refused, up to 3 MB), strips scripts,
  styles, navigation, headers and footers with Jsoup, keeps the `<main>`/`<article>` text (or the whole body) up to 6,000 characters, and
  lists up to 10 of the page's own links (text and address) so the model can choose one. A page's text is a source to read, never
  instructions to follow — non-HTML content (a PDF, for example) is reported as such instead of being read as text. *Checked:* the text
  extraction and the link list by unit tests, and live on the emulator (a real Wikipedia page, a refused private address, a PDF correctly
  turned away).
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

Limits: 100 plugins, 5 parameters each, 20 000 characters per file, a name that is not a built-in tool's.
Settings: the catalogue (67 built-in plugins) is a dropdown, folded by default, with a search field on the name and description; the
installed ones stay listed above it.
**Many plugins.** Every installed plugin is usable, but only the first 25 (by file name) are declared to the model as tools of their own: a long list
of tool declarations weighs on every session, and one the service refuses would break the whole session, and Gemini's documentation gives no
figure to rely on. From the 26th, a single tool, `plugin_run` (a name and a JSON object of parameters), reaches the others, and its description
lists them with their parameters. *Checked:* the split, the reserved name, the parsing of the arguments and the dispatch by unit tests; *not
checked:* how reliably Gemini picks a plugin from that list in a real session.
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
- **Plugin catalogue.** Settings → *Plugins* lists 67 bundled plugins with an *Installer* button (up to 100 can be installed).
  The first 16: crypto prices, exchange rates, Wikipedia summary, public holidays, Maps search and directions, YouTube, translation, news,
  recipes, calendar event, night, meeting and car routines. Added later (13): the position of the International Space Station, a random
  French Wikipedia article, sunrise and sunset, the phase of the moon, NASA's astronomy picture of the day (its explanation, in English), a
  random quote (zenquotes.io, English), a random dish (themealdb.com, English), on-duty pharmacies near a place, Google Images, sports
  results, cinema showtimes, a morning routine (weather, today's calendar, reminders) and a reading mode (brightness and volume).
  A third batch (19): the latest earthquake of magnitude 4.5+ (USGS), a real text translation (MyMemory), the phone's public IP, the three cheapest
  fuel stations of a French town (data.economie.gouv.fr), the UV index, the price of gold, silver, platinum and palladium, dishes with a given
  ingredient, a random cocktail, a random odd fact, links to flights, hotels, price comparison, books, Stack Overflow, the definition, the
  conjugation and the synonyms of a word, a sport routine (volume, then music) and a phone check-up routine (battery, storage, notifications).
  All nine web ones and the two routines were run through the plugin runner on the emulator; two services were tried and dropped because they were
  down or returned a partial answer (French dictionaries, "on this day"). MyMemory has a daily quota, and the notification step of the check-up
  needs the notification access granted in Settings.
  A fourth batch (19), found in the community list of public APIs (the ones that need no key, over https, then tried one by one): French public
  services (a postal address, GPS coordinates and postal code of an address or a town, a town's population and department, a company by name, the
  latest product recalls and a search among them, the next school holidays of a zone), and world ones (a city's country, altitude and time zone, a
  book's author and year, Wikipedia search, a country's statistics from the World Bank, an airport's weather report, the Formula 1 standings, the
  asteroids passing today, the Wayback Machine copy of a site), plus links to service-public.fr, Légifrance and the Pages Jaunes. Sixteen web ones and
  the links were run through the plugin runner on the emulator. Limits seen: the address service knows addresses, not monuments ("Tour Eiffel" landed
  in the North), the company search returns the best match of the name given, and the school-holiday dates are in UTC. Tried and left out: the
  MyAnimeList API (time-out), Project Gutenberg's (unreachable) and a name-day calendar (not found).
  Each is checked like an imported file, and the same files are in `plugins-examples/`. Their web services need no key and were called for
  real while writing them; the seven web ones and both routines were also run through the app's own plugin runner on the emulator (the
  brightness step of the reading mode stops at the system-settings permission, as the night routine does). Not tried: the tap on *Installer*
  for the new ones on a phone, and the links opening in Google Maps and the browser on a real phone.
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
  you trained yourself with openWakeWord's `automatic_model_training` notebook. Only the file header and size
  are checked on import. For a phrase of your own without a computer, see the next item.
- **Teach a wake word on the phone** (Settings > *Apprendre mon mot d'activation*, `wake/WakeLearning.kt`). openWakeWord trains a
  classifier per phrase on thousands of synthetic voices (PyTorch, a computer), which cannot run on a phone. What the phone does instead
  reuses openWakeWord's speech embedding (the same TFLite models as the detector) and compares what it hears with **your own repetitions**:
  you say the phrase six times, then speak normally for twelve seconds; each repetition gives a few templates (the 16 embeddings that
  end with the word), the mean of your ordinary speech is subtracted, and the detector fires when the last 1.3 s are close enough
  (cosine similarity) to a template, twice in a row. The threshold comes from the repetitions (how well each one matches the others) and from
  your ordinary speech (how close it gets), and follows the *Sensibilité* setting. A live test runs before saving; taught words are kept in
  their own folder (removing the models does not delete them), and can be chosen or deleted. Nothing leaves the phone.
  *What I measured* (real openWakeWord models, in the app on the emulator, with Windows text-to-speech voices; a plain classifier head trained on
  the same recordings was tried first, in a Python prototype, and rejected: 71 of 80 phrases fired): with « Debout Jarvis » taught from five recordings of one voice, the
  same voice at other speeds was recognised 4 times out of 4, a different voice was not (0.5 against a threshold of 0.72, which is intended: it
  is a personal detector), and 1 of 40 unseen sentences fired, « Jarvis débranche la prise », which contains the word. In a Python prototype
  with the same models (ONNX, not the app), white noise of σ 600 on 16-bit samples lowered the same-voice scores only from about 0.9 to 0.8. *Not tested:* a human voice and a real microphone (the emulator's is silent), so how
  many false alarms you get in daily life is for you to find; the flow itself ran on the emulator up to its "not heard" message.
  Choose a phrase of three syllables or more; very short words cannot be told apart from ordinary speech.
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
work is licensed CC BY-NC 4.0: this avatar, and any app that includes it, may not be used commercially.** The head is a 3D head scan by Lee Perry-Smith (CC BY 3.0) and the face landmarks are MediaPipe's (Apache-2.0). Details and credits: `app/src/main/assets/avatar/NOTICE.txt`; the credit is
also shown in the settings.

- **Face.** A real 3D head scan (about 26,000 vertices once the eyes, the mouth and the hair are built), stored as an asset (`head_mesh.bin`, 1.9 MB)
  by `tools/avatar/export_head.py`. Drawn on Android's canvas: no OpenGL, no extra library.
- **Three faces** (Settings > Appearance > Visage). *Classique* is the original. *Léa* and *Marc* are **the same scan reshaped**, not other
  people scanned: `export_head.py` bends the finished geometry with a smooth warp (a narrower jaw, a smaller nose, bigger eyes and higher cheekbones
  for Léa; a wider square jaw, a heavier brow ridge, a bigger nose and smaller eyes for Marc), on every vertex so the eyes, the lids, the teeth and the
  lips stay lined up, and gives each its own hair (`groom.py` styles: Léa's is dark auburn, wavy and shoulder length, Marc's short, dark and touched
  with grey) and matching eyebrows. `JHM_FACE=lea python export_head.py …` rebuilds one (`head_mesh_lea.bin`, `head_mesh_marc.bin`). *Checked:* the three
  draw and switch on the emulator, the original is byte for byte what it was, unit tests on the rig, the placement of the eyes and mouth, and the
  differences between the faces. *Not checked:* the frame rate on a real phone (Léa has about a quarter more triangles than the original), and Léa's hair is
  stylised and a little angular.
- **Lip-sync.** Each chunk of Jarvis's voice is analysed (formants: openness from the first, lip spread from the second) and
  fused with the words being spoken (lips close on m, b, p; language independent). The mouth is played on a clock tied
  to the speaker, so it follows what is heard and not what has only arrived over the network. An interruption clears it.
- **Look.** After reference photos of a glowing point-cloud head, the surface is nearly black and the head is drawn as a web: about
  2,800 nodes spread evenly over the mesh (closer together round the eyes and mouth, brighter towards the contour, with a slow
  twinkle) joined to their nearest neighbours by fine lines, in the interface colour. The nodes sit on the mesh's triangles by
  barycentric weights, so the web follows the jaw and the head pose. The eyes are dark openings with a faint iris. The geometry
  is a real human head scan ("Infinite, 3D Head Scan" by Lee Perry-Smith, CC BY 3.0) with ears, nose, lips, jaw and neck, rigged
  for the jaw, brows and lips by `tools/avatar/export_head.py` and stored in `head_mesh.bin`; the eyes and lips are placed with
  MediaPipe's face landmarks. The animation and lip-sync are Mark-LIV's.
- **A cartoon character** (Settings > Appearance > Visage > *Dessin animé*, `avatar/CartoonAvatar.kt`). A fourth choice that is not a mesh: a soft 3D-cartoon
  man (round face, curly dark hair, round glasses, beard, a broad smile, a hoodie), drawn on the canvas with paths and gradients, in the style of the
  reference picture the user gave (a character, not a real person). It runs on the same animation as the scanned heads (`HoloAvatar`): the mouth opens and
  spreads with the voice (lip-sync), the eyes blink and follow the gaze, the brows lift with the phrase, the lids lower when asleep, the head sways and the
  features slide over it as it turns, the shoulders breathe. The skin tone and the lip colour of the settings apply; the hair and the beard are fixed.
  *Checked* on the emulator: the drawing, the mouth moving from a small smile to wide open with the tongue over a synthetic voice, the lids for the sleeping
  state, and unit tests on the pose it reads and its colours. *Not checked:* the frame rate on a real phone, and it is one character: there is no editor for
  the hair, the glasses or the beard (they are in the code, `hairFront`, `drawGlasses`, `drawBeard`).
- **Skin and lips.** Settings > Appearance > Peau: a skin tone (light by default; medium, tanned, dark) or the glowing web over the
  head, shaded per vertex, with dark brows and eyes with a white and an iris; Lèvres: natural (the skin tone pushed towards
  red, or the theme colour on the web), rose, red, plum or coral. Each lip is filled on its own, with a gloss line on the lower one.
- **Eyes and mouth are part of the head.** `export_head.py` cuts real holes in the scan: the eyes get openings with an eyeball
  behind each (sclera, iris, pupil, painted per vertex) that turns towards the gaze, and the skin round them drops and rises as
  the lids close; the mouth is split along the lip line, the lower lip follows the jaw, and a dark cavity with upper teeth sits
  behind the slit. Nothing is drawn over the face any more except the brows; the lips' colour is painted on the lip vertices.
  With a skin, the brows are about 160 hairs each, anchored to the brow's landmark vertices (thick and upright at the inner end, thinner and
  flatter at the outer end); the eyelid rims and the two lip edges are thin lines along the openings, so a shut mouth shows one
  mouth line and an open one two lip edges.
  With a skin the head also has hair, built by `tools/avatar/groom.py` as geometry on the scan: a thin dark cap over the scalp (clipped
  along a high hairline, kept clear of the ears, thinning into the skin at the sides) and about 520 locks, each a curved, tapering strip
  with a rounded section and a highlight along its middle, laid along the flow of the cut (up and back-and-right from the front) with
  waves that run together. It needs a skin (the glowing web look is the bare head).
  Where the eyes and the mouth go was measured on the scan itself (the closed eyelids meet at y = 0.02, the lips at y = -0.49, the
  face's midline is at x = -0.035); MediaPipe's own coordinates would put them about 0.06 off.
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

#### Hair shading (Classique face)

The strands drawn over the hair locks follow what real-time hair rendering does: each lock is a bundle of strands waving together (a shared slow wave, small differences between strands, some stopping short of the tip), and the sheen is lit with the Kajiya-Kay model, two bands per strand as in Marschner's model: a narrow whitish one and a wider, tinted one shifted along the strand. Sheen appears only where the strand's direction suits the light, so it forms streaks rather than a uniform grey. Checked on the emulator only.

### Offline mode

Without a network (or when Gemini cannot be reached), a session no longer fails: Jarvis switches to a local mode that uses the phone's own speech recognition (`SpeechRecognizer`, offline preferred, the on-device recogniser on Android 13+) and voice (`TextToSpeech`, an offline voice when the phone has one). Setting (card "Périphériques audio"): Automatique (default: no validated network, no API key, or Gemini unreachable at the first connection), Toujours, Jamais. The wake word already works offline, so it stays hands-free.

What it understands is a fixed list of French commands, matched by rules (`offline/OfflineIntents.kt`, covered by unit tests) and run through the existing tools, with their own safeguards (the volume still asks for confirmation): open an app, call a contact or draft an SMS (never sent), volume, brightness, flashlight, music (pause, play, next, previous), timer, time, date, battery, settings pages, lock the screen, screenshot, "aide", "au revoir". Time and date are worked out on the phone. Anything else is answered with "Je n'ai pas compris" and a pointer to "aide". It ends after three silences.

**What it is not:** it does not chat or reason: there is no local language model in this version (a model such as Gemma would mean a download of 1 to 3 GB, and is the next step if wanted). It needs the French offline language pack (Google speech services: offline speech recognition) and a French voice; the errors say so when they are missing. The spoken answers are French only. Verified on the emulator (commands through the debug receiver `DEBUG_OFFLINE`, and the automatic switch without network, which stops with the message about the missing language pack, as that emulator has none); **not** verified with real offline speech recognition on a phone.

#### Hologram look

The skin setting has, besides the tones and the glowing web alone, an **Hologramme** option: a solid skin (the light tone tinted a little by the web's colour), the fine web of nodes and lines drawn over it, no hair, and the brows and the lip line in the web's colour. The web itself is denser than before (nodes 0.031 apart in head half-heights, computed when a face loads, with a grid for the neighbour search). The skin of the hologram is solid, with no transparency effect: it is brighter than the plain looks, opaque down to the base of the neck, and has a soft bright edge at the contour instead of dots; the web of nodes and lines is no longer drawn over it (it is for the glowing-web look only). What makes it a hologram is the circuits, the colour of the brows and the lack of hair.

**Hologramme bleu** (skin setting): one skin that mixes two blues: a light blue skin (0x69B4F0), a deep blue (0x0C2160) for the edge all round and for the hollows (the parts turned away from the light), and about four in ten of the circuit tracks (and their pads) in deep blue. The gold circuits are more numerous (lattice pitch 0.036, up to 6,000 attempts, 9 to 33 points long) and glow: a wide, faint gold halo under each track, brighter under the pulse that runs along it. It is the default look for a new install and for anyone who never touched the setting (skin value 7); anyone who had already chosen
a look keeps it.

Gold **circuit tracks** are laid on the face in this look (`avatar/CircuitTraces.kt`): paths on a lattice that turn by 45 or 90 degrees and end in round pads, anchored on the mesh like the web's nodes so they follow the relief and the movement, keeping away from the eyes and the mouth, with a pulse of light running along each track. **Hologramme + cheveux** (skin setting): hair of optical fibres (`avatar/FiberHair.kt`). The mass of the hair is a dark blue, and over it each lock of the mesh carries three thin strands, added to what is behind them so that they glow: dim at the root, brighter towards the tip, which ends in a gold spark, with a pulse of light running from root to tip on some of them and a slow sway. They follow the locks of the mesh, so they move with the head. Not the realistic hair of the other looks (that one is in `drawFibres`, lit like real hair). Checked on the emulator only.

#### Cap

Setting Avatar > Casquette: none, black, blue, red, white, khaki, and a grey-green one with an embroidered emblem (modelled on a baseball cap seen in reference pictures: worn low, a wide visor that droops and shades the eyes, and an angular W with a chevron stitched on the front panel, drawn on the dome by (ring, column) so it bends with it) (`avatar/CapGeometry.kt`, drawn by `AvatarRenderer.drawCap`). The dome is a regular grid (64 columns, 14 rings and a top point) computed once in the rest pose from the outer hull of the skull, so it has no hole and its edge is a clean curve (level with the top of the ears, just above the brows, lower at the back). Each point is tied to the three nearest skin points by weights and stands off them along their normals: the positions are rebuilt every frame from the posed head, so the cap follows every movement. Six seams follow the columns of the grid up to a button. A band runs all round the edge (the thickness of the cap), between two dark lines. The visor is longer and curved (it droops more at its sides), with a thickness under its outer edge, two rows of dashed stitching, and a soft shadow thrown on the forehead; it tapers at its ends. With a cap on, the brows are not drawn either (they are behind the visor, which is long, 0.62 head half-heights, and droops), and no hair is drawn at all (the layer of hair, the locks and their fibres): hair cut off by the edge looked wrong from the side. The edge is nearly level all round the head (a little higher above the ears) and only slopes down behind them; the two rings at the edge are smoothed round the head so it does not ripple, and the grid has 96 columns. It works with every skin look, the hologram ones included. Not for the cartoon face. (A first version cut the dome out of the head's triangles and had holes and a ragged edge.) Checked on the emulator only (front view and a moving head).

### Kept exchanges of the voice sessions

What is said in a voice session (the user's words and the assistant's, not the system's announcements) is now kept on the phone (`memory/SessionTranscripts.kt`, file `session_transcripts.json`, private, not encrypted). It is saved as the session goes (a moment after each message, so a killed process loses little) and once more when it ends; a session where the user said nothing is not kept. Limits: the last 30 sessions, none older than 90 days, 400,000 characters in all (the oldest go first), each message cut at 2,000 characters.

- **Main screen:** when no session is running it shows the last kept session ("Dernière session", with its date), dimmed.
- **Next session:** the system instruction gets a `[RECENT EXCHANGES]` block with the last two sessions (eight messages each, 220 characters each), told to the model as data and never as instructions (so that a text read out in a session, a mail or a page, cannot give orders through it) and not to be read out unless the user asks. It comes on top of the one-line summaries and the facts of the long-term memory, which are unchanged.
- **Settings > Historique des sessions:** a switch to keep them (on by default), the count, and a button to erase them.

The offline mode's exchanges are kept the same way. Verified: the storage and the prompt by unit tests, and the display on the emulator with a test file; a real session was not run.

#### Plain skin tones

The four plain skin looks (Claire, Mate, Bronzée, Foncée — used by Classique, Léa and Marc, not the hologram ones) got a small pass: a faint natural
sheen (skin is not matte — a soft, narrow highlight where the surface faces the light, the same half-vector calculation as the hair) and a
touch of warmth added where the light lands most (like blood under thin skin), instead of a single flat tint. Checked on the emulator on a
light and a dark tone. The cartoon face and the hologram looks were not touched here.

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

### Sending messages on your word

By default `send_message` only prepares a draft and any tap on a « Envoyer » button asks for a confirmation on screen. Settings > *Envoi de
messages* has a switch, **off by default**, that changes this: when it is on and you clearly say to send (« envoie »), the message goes out, with no
further confirmation.

- **SMS**: sent straight from the phone with `SmsManager` (permission `SEND_SMS`, asked when the switch is turned on). Without that permission,
  it opens the messaging app and presses its send button through the accessibility service.
- **WhatsApp**: opens the conversation with the contact's number and the text already typed (`wa.me` link), then presses « Envoyer »/« Send »
  through the accessibility service. Telegram and Messenger stay drafts.
- **Guard rails**: the recipient must be a **contact of the phone** (the model never types a number; several matches are listed and it asks which);
  at most **5 messages every 10 minutes** (a refused or failed attempt does not use the allowance); every message is announced by a **notification**
  with its text and written in a **history** shown in the same card; the model is told in its instructions that a mail, a page or a notification
  is data and never a request to send, and that it may send only on a clear request. In the tool, `send = true` without the switch just opens the
  draft and says how to turn it on. Outside this path nothing changed: a tap on a send, pay, delete or install button in any app still asks, and
  the confirmation is only waived for the send button of a messaging app (WhatsApp, Telegram, Signal, Messenger, Google/Samsung/AOSP messages),
  never for a label that mentions money, and never on the system, settings or installer screens.
- **Risk, said plainly**: a message that has gone cannot be taken back, and a language model can be wrong or be tricked by a text it reads. The
  switch is yours to turn on; the limit, the contact rule and the notifications only reduce the damage.
- *Checked* on the emulator: sending an SMS (about 5 s, with its result, the history and the notification), the contact that is ambiguous, unknown
  or missing, the limit (and the give-back after a failure), the switch off (draft only), and the path through the screen (without the SMS
  permission: the messaging app opens, its « Send SMS » button is found and pressed, and the message goes out). 20 unit tests. *Not checked*:
  WhatsApp itself (not installed on the emulator: its button is looked up by the same rule, a clickable « Envoyer » or « Send » in the app),
  delivery to a real recipient, and a real spoken « envoie ».

### Meeting notes, documents, watches, Gmail and Drive

Four abilities inspired by [Brahma-Echo](https://github.com/titechprabhasolutions/Brahma-Echo) (a Windows assistant), each with its own tool for the
voice session and, where it makes sense, a card in the settings. All of them are covered by unit tests; what could not be tried without a real
Google account or a Gemini key is said below.

- **Meeting notes** (`meeting_notes`, Settings > Meeting notes). Records a meeting or a voice note (AAC, 32 kbit/s, an hour at most, as a microphone
  foreground service with a notification whose buttons finish or drop the recording), then sends the audio to Gemini, which writes a summary, key
  points, decisions, actions and a transcript in Markdown. The notes are kept in the app (excluded from backups) as Markdown, but handed over as a
  **PDF** (notification with Open and Share buttons; in the settings card also Word and plain text, written in Documents/Jarvis): the `.md` file
  itself did not open on phones, because almost none has an application for `text/markdown` (checked on the emulator: "No activity found", against a
  PDF viewer for `application/pdf`). Markdown documents from `create_document` now open as plain text for the same reason, and a file with no
  application at all gets Android's "open with" chooser instead of doing nothing; the audio is deleted once the notes are saved and kept if they could not be written ("Retry" in the notification and in the
  settings). The microphone serves one use at a time, so starting from a voice session closes the session first (after a short spoken
  announcement) and the recorder starts the moment the microphone is free, while the voice service still allows a background start; if Android
  refuses anyway, a notification starts it with one tap. The wake word stops listening while a meeting is recorded. The button in the settings
  always works. *Not tried:* the real Gemini answer (no key on the test emulator); the recording, the stop, the failure and retry paths were.
- **Documents** (`create_document`). Writes a PDF, a Word (.docx), an Excel (.xlsx), a PowerPoint (.pptx), a CSV, a Markdown or a text file from what the assistant
  composed (light Markdown for text documents; rows for tables, `=SUM(...)` cells become formulas), in the app's `Documents/Jarvis` folder, and shows a
  notification with Open and Share. The Word, Excel and PowerPoint files are written by hand (a small OOXML writer), the PDF with Android's `PdfDocument`. In a presentation, the
  title is the title slide, each `#`/`##` heading opens a slide, bullets stay bullets and a crowded slide continues on the next.
  *Checked:* the PDF was laid out and read back; the Word file round-trips through the app's own reader; the Excel XML was read back.
  The deck was read back with python-pptx. *Not tried:* opening the Word, Excel and PowerPoint files in Office itself.
- **Watches** (`watch`, Settings > Watches). Keeps an eye on a crypto price in euros (CoinGecko, no key), a website (alerts when it stops answering and
  when it is back), the battery temperature or the free memory, about every 15 minutes with WorkManager, and alerts once per crossing (with a small
  margin so a value at the threshold does not ring every check). Battery and storage alerts already existed in "Background checks". *Checked:* a live
  price and a site check on the emulator.
- **Gmail and Drive** (`gmail`, `drive`, Settings > Google). Reads unread or searched mail, reads a message, and prepares **drafts** (nothing is ever
  sent); searches and reads Drive files (Docs and Sheets exported as text, PDF and images analysed by Gemini), and uploads documents Jarvis wrote.
  Sign-in uses Google's Authorization API (Play services), so Jarvis never sees a password, with the narrowest scopes: `gmail.readonly`,
  `gmail.compose`, `drive.readonly`, `drive.file`. Mail and file contents reach the model as data, marked so that instructions inside them are not followed.
  *Not tried:* everything that needs a signed-in Google account (the emulator has none); the tools answer "Google is not connected" without one.
  **Each build needs its own OAuth client:** the debug build (`com.jarvis.android.dev`, debug key) and the release build (`com.jarvis.android`,
  release key) are different apps for Google. The release key's SHA-1 is shown in Settings > Google (for the release published here it is
  `78:B0:7C:86:A6:C8:48:4E:14:3D:A2:F3:4F:EB:A2:8F:0C:09:35:32`); register it with the package `com.jarvis.android`. "Check the connection" in the same
  card says which of the two problems it is (unregistered app, or expired access).
  To connect: in a Google Cloud project, enable the Gmail API and the Google Drive API; configure the OAuth consent screen (External, in testing, with
  your Google account as a test user, and the four scopes above); create an OAuth client ID of type **Android** with the app's package name
  (`com.jarvis.android`, or `com.jarvis.android.dev` for the debug build) and the SHA-1 of the key that signs the APK
  (`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`); then Settings > Google > Connect. Google
  treats Gmail scopes as restricted: an unverified app in testing mode is limited to its test users, and the access may need to be renewed about every
  week.

### Android Auto / car mode

In a car the speakers are reached through Bluetooth or USB, which Jarvis used to treat like a headset: the microphone stayed open while it spoke, it heard itself and kept answering itself, and the audio focus held for the whole session stopped the car's music for good. The **car mode** (settings, card "Périphériques audio": Automatique / Activé / Désactivé) changes three things:

- the microphone is muted while Jarvis speaks, with a longer tail (1.1 s) for the cabin's echo and the Bluetooth delay;
- the phone's own microphone is used (`VOICE_RECOGNITION`), which does not switch the Bluetooth link to a call;
- the audio focus is a light one (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`), taken only while Jarvis speaks and released 1.5 s after, so the music is lowered and comes back by itself.

"Automatique" detects the phone's car mode (`UiModeManager`) or an Android Auto projection (the `androidx.car.app.connection` provider). Unit tests cover the decisions (`CarAudioTest`); it has been exercised on the emulator with `adb shell cmd uimode car yes`, but **not in a real car**. If it still misbehaves, the activity log line "Focus audio refusé" / "Mode voiture" tells what was detected.

### Updating from GitHub

Settings > Update asks the GitHub releases of this repository for the latest version, compares it with the installed one, downloads the APK
(size and package name are checked) and installs it through Android's `PackageInstaller` (a session, so a refusal comes back with its reason
instead of nothing happening, which a plain "open this file" intent can do on some phones). Android then asks for confirmation; settings and
memory are kept. The first time, Android asks to allow installs from Jarvis (the file stays downloaded: come back and it goes on). A
notification offers the confirmation too, in case the window cannot open from the background. The phone may add steps of its own, for
example Google Play Protect's "App scan recommended" (choose *Scan app*, or *More details* > *Install without scanning*).
*Checked* on the emulator: an APK with a higher version code installed over the app (0.5.1 became 0.5.2), the refusal without the permission,
the confirmation window and the notification, and Cancel. *Not checked:* a Xiaomi phone (its own installer and security scan), and the
whole path from a real GitHub release. An update only installs over the same app signed with the same key, so a debug build (`.dev`,
debug key) needs the debug APK and the release build the release APK; the app picks the asset whose name says `debug` or `dev` for a debug build.

The version is bumped with every change (`versionCode` and `versionName` in `app/build.gradle.kts`). To publish a version once it is built:

```
gh release create v0.4.3 app/build/outputs/apk/debug/app-debug.apk#jarvis-0.4.3-debug.apk --title "0.4.3" --notes "What changed"
```

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

- Not yet checked on a real phone with a real voice: the built-in wake word, a phrase you taught (what the emulator could show is in "Teach a
  wake word on the phone"), the meeting notes with a real Gemini answer, Gmail and Drive, the update flow on a Xiaomi (its own installer and
  security scan), the live video, and what Jarvis keeps after a session with a real answer.
- "Rules to keep" (« toujours répondre en français ») are not a feature of their own; the automatic memory keeps some of them as preferences.
- Camera-based sport tracking (push-up counter, posture) was left out: heavy on the battery.
- Desktop-only features (mouse/keyboard automation, game updaters, the remote dashboard) have no Android
  equivalent and are not planned.
