# Jarvis Android

An Android port of [FatihMakes/Mark-LIII](https://github.com/FatihMakes/Mark-LIII) — a
real-time voice AI assistant built on the Gemini Live API. This is a from-scratch
Kotlin/Jetpack Compose app, not a wrapper: there is no first-party Android/Kotlin SDK
for Gemini's Live (BidiGenerateContent) API, so it talks the WebSocket protocol
directly (see [`core/GeminiLiveClient.kt`](app/src/main/java/com/jarvis/android/core/GeminiLiveClient.kt)
and [`core/LiveProtocol.kt`](app/src/main/java/com/jarvis/android/core/LiveProtocol.kt)).

## Status

Version 0.9.81 (see `app/build.gradle.kts`; the version goes up with every change, and releases are published on
[GitHub Releases](https://github.com/Haseosama/Jarvis-Android/releases), see "Updating from GitHub"). The voice loop works end to end on a
real phone: microphone → Gemini Live (`models/gemini-3.8-live`) → spoken reply with live transcripts. The unit-test suite (about 800 tests,
`./gradlew :app:testDebugUnitTest`) passes. Each feature below says what was checked and what was not; in short, a lot was checked on an
emulator (with synthetic voices, test data, or without a Gemini key), and **not yet on a real phone with a real voice**: the wake word (built-in
and taught), the meeting notes with a real Gemini answer, Gmail and Drive, the update on a Xiaomi, reminders re-armed after a reboot, timers,
flight search, the live video, the interpreter mode, driving mode started by a real car's Bluetooth, and the photo analysis on a real photo
library. Reconnection after a real network drop was tested by cutting the phone's Wi-Fi (see "Connection drops" below).

Requirements: Android 8.0 or later (minSdk 26); the app targets Android 14 (targetSdk 34) and is compiled against Android 16
(compileSdk 36). The release APK is about 87 MB, mostly the on-device models (wake word, image labels, text recognition, local AI) and their
native libraries; it carries them for phone processors only (arm64-v8a and armeabi-v7a), while the debug build also has x86 and x86_64 for
the emulator.

## What it can do (at a glance)

Everything is asked by voice (French first, English too), or typed in the chat. Details, limits and what was tested are in the sections below.

- **Conversation and memory**: real-time voice with Gemini Live, a long-term memory, the morning briefing (agenda, reminders, birthdays,
  yesterday's steps and last night's sleep), kept session history, a 3D holographic avatar that speaks with its lips, and can stand on a table in augmented reality.
- **The phone itself**: open apps, read and drive the screen (accessibility service), send messages (SMS, WhatsApp, Telegram, Messenger),
  calls, missed calls and calling back, notifications and "what did I miss?", Do Not Disturb until a time, volume, brightness, flashlight, alarms, timers, reminders (by time, by place, or tied
  to a person: "la prochaine fois que Paul m'appelle…"), find the phone, files in a work folder.
- **Everyday life**: lists, spending (by voice or by scanning a receipt) and monthly budgets, subscriptions and regular payments,
  recipes read step by step, parcel tracking, train and bus times, text read through the camera, a briefing when the alarm stops, medications and habits, where the car is parked, driving mode,
  weather and rain in the next hour, tides and marine weather, air quality, fuel prices, the nearest pharmacy, bakery, cash machine, toilets or charger (open or not), planned cuts of electricity, water or gas (read from the notices received, reminded the evening before), which bin to put out (reminded the evening before), planes overhead, birthdays, calendar, photos (by date, place, or what is on
  them), health (steps, sleep, heart rate from Health Connect), an emergency SOS to chosen contacts.
- **Knowledge and work**: web search, reading a web page, flights, translation and interpreter mode, meeting notes, documents, Gmail and
  Drive, code help, watches on prices or sites, a multi-step agent mode.
- **Without a network**: an offline mode with fixed French commands (and an optional local Gemma model), and an offline wake word.
- **Extensible**: declarative JSON plugins, Home Assistant, Spotify and Liberty Music.

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
| `main.py` (`JarvisLive`, `google-genai` Live session) | [`engine/JarvisEngine.kt`](app/src/main/java/com/jarvis/android/engine/JarvisEngine.kt) + [`core/GeminiLiveClient.kt`](app/src/main/java/com/jarvis/android/core/GeminiLiveClient.kt) |
| `core/wake_word.py` (openWakeWord, fully offline ONNX) | [`wake/OpenWakeWordDetector.kt`](app/src/main/java/com/jarvis/android/wake/OpenWakeWordDetector.kt) — the same three openWakeWord models, run with TFLite, fully offline (downloaded on demand, see "Offline wake word"), plus [phrases you teach it yourself](app/src/main/java/com/jarvis/android/wake/WakeLearning.kt). Without the models, [`core/WakeWordDetector.kt`](app/src/main/java/com/jarvis/android/core/WakeWordDetector.kt) falls back to Android's `SpeechRecognizer` (approximate, and it may use the network). |
| `core/confirm.py` (real user confirmation for irreversible actions) | [`core/ConfirmManager.kt`](app/src/main/java/com/jarvis/android/core/ConfirmManager.kt) |
| `core/undo.py` | [`core/UndoManager.kt`](app/src/main/java/com/jarvis/android/core/UndoManager.kt) |
| `core/action_loader.py` + `plugins/` (runtime file auto-discovery) | [`registry/ToolRegistry.kt`](app/src/main/java/com/jarvis/android/registry/ToolRegistry.kt) — a compile-time list instead. Android can't safely load and execute arbitrary code dropped onto the device at runtime, so "one file, no core edits" survives as a Kotlin object implementing `Tool`, registered once in `ToolRegistry.ALL`. |
| `memory/memory_manager.py` | [`memory/MemoryManager.kt`](app/src/main/java/com/jarvis/android/memory/MemoryManager.kt) — same design: nothing is silently forgotten, only a budgeted "core" rides in every prompt, the rest is recalled on demand. |
| `config/api_keys.json` + UI settings | [`memory/ConfigStore.kt`](app/src/main/java/com/jarvis/android/memory/ConfigStore.kt) — API key in `EncryptedSharedPreferences`, everything else in DataStore. |
| `dashboard/` (remote control from your phone) | N/A — the assistant already *is* the phone. |

## Ported skills (`actions/`)

`web_search`, `flight_search` (web results + Google Flights link), `weather_report`,
`open_app`, `browser_control`, `reminder` (create / list / cancel, persisted, re-armed after
a reboot, read aloud when due), `timer` (create / list / cancel, read aloud at the end),
`task_list` (a shopping list, a to-do list, or any other named list, persisted, works offline
too — see "Lists" under "Offline mode"), `system_monitor` (battery/storage), `device_settings`
(volume behind an on-screen confirmation; Wi-Fi/brightness panels), `send_message` (WhatsApp, Telegram, Messenger or SMS: by default a draft the user sends themselves;
with *automatic sending* switched on it really sends, see "Sending messages on your word"),
`youtube_video`, `read_clipboard`, `code_helper`, `recall_memory`, `remember_fact`,
`forget_fact`, `undo`, `end_session`, `smart_home` (lights, switches, covers, thermostats… through
the user's own Home Assistant server, see "Maison connectée"), `translate` (translates a piece of
text into a named language without switching the language of the conversation itself), `spotify_search`
(opens Spotify's own search for a title, artist or playlist; the user picks and plays it — see below),
`air_quality` (air quality index and pollen), `planes_overhead` (aircraft flying around the phone),
`expenses` (spending by voice), `habits` (medications and habits at fixed times), `find_phone` (rings the phone
loud to find it), `place_reminder` (reminders on arriving at or leaving a place) — see "Four everyday helpers",
`prix_carburant` (cheapest fuel, and a price alert), `parking`, `rain_soon`, `birthdays`, `interpreter` — see "Car, rain, birthdays, interpreter",
`call_log` (missed calls and calling back), `sos` (emergency alert), `photos` (photo search) — see "Calls, SOS, photos",
`receipt` (receipt to expense), `person_reminder` (reminders tied to a person), `driving_mode`, `health` (Health Connect) —
see "Receipts, people, driving, health", `quiet_mode` (Do Not Disturb until a time), `subscriptions`, `recipe`, `parcel`, and
`notifications`' digest — see "Missed notifications, quiet time, subscriptions, recipes, parcels", `budget`, `read_text` (text through
the camera), `wake_briefing`, `transport` (trains and local transport) — see "Budgets, camera text, wake-up briefing, transport",
and the phone-control tools below.

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

Added here and not in the original: `alarm`, `calendar`, `call_contact`, `call_log`, `notifications`, `routine`, `timer`, `liberty_music`,
`meeting_notes`, `create_document`, `gmail`, `drive`, `watch`, `end_session`, `undo`, `task_list`, `translate`, `interpreter`, `smart_home`,
`spotify_search`, `air_quality`, `planes_overhead`, `prix_carburant`, `rain_soon`, `expenses`, `receipt`, `habits`, `find_phone`,
`place_reminder`, `person_reminder`, `parking`, `driving_mode`, `birthdays`, `photos`, `health`, `sos`, `quiet_mode`,
`subscriptions`, `recipe`, `parcel`, `budget`, `read_text`, `wake_briefing`, `transport`, plus the widgets, the avatar, the
taught wake word, the offline mode and the in-app update.

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

### Models that fail: a ladder (from Mark LV)

Since 0.9.27, after [Mark LV](https://github.com/FatihMakes/Mark-LV) (CC BY-NC 4.0, the same licence as Mark LIV):

- **Text models** (`rest/ModelLadder.kt`). Every one-shot Gemini call — the text chat, web search, looking at the screen or a file,
  the agent, meeting notes, the memory's extraction — goes through one transport, and so through one ladder: the model chosen in the
  settings first, then `gemini-3.5-flash`, `gemini-2.5-flash`, `gemini-flash-latest`, the light ones, and last the two Mark LV measured
  as the least reliable. A model that fails for its own reasons is set aside for a time that depends on why: out of quota on every key
  (429) 5 minutes, not answering (500–504, or a time-out) 30 minutes, not there for this key (404, or a 400 naming the model)
  6 hours; the next one answers the same call. A refused key, a bad request or the phone's network are not the model's fault and end
  the call as before (walking the ladder would hit the same wall each time). Speech models are never replaced by a text one, and a
  streamed answer moves to another model only before anything of it was handed on. Every call also has a deadline (180 s in all,
  90 s without a byte), so a model that holds the line is let go. Before this, one model answered everything: Mark LV measured
  `gemini-3.6-flash`, this app's default, answering 504 after 12 s.
- **Live models** (`engine/LiveModels.kt`). A voice session opens on the model chosen in the settings; if it is out of quota or not
  there for this key, it reconnects at once on `gemini-3.1-flash-live-preview`, then `gemini-2.5-flash-native-audio-preview-12-2025`
  (the two Mark LV checked in September 2026), and says so in the activity log. Deliberately a short list, and only for the model's
  own failures: Live models differ in what they accept, and a network drop or a refused key never moves it.
- **Several requests at once**: the system prompt now asks for every task of a turn to be done, in the order given, then one answer
  covering each result.
- **"Sent" means sent**: a message sent by pressing the app's Send button (SMS or WhatsApp through the screen) counted as sent even when
  the compose field could not be read, so there was nothing to check it had emptied. Such a press is now reported as not confirmed
  ("check in the app"), and the assistant is told not to say it was sent (Mark LV fixed the same fault in its WhatsApp driver).
- *Checked:* `ModelLadderTest` (a fake Gemini answering per model: 504 → the next answers and the first is skipped for half an hour;
  429 and a 400 naming the model rest 5 minutes and 6 hours; a refused key or a bad request do not move; speech models stay; all
  resting still leaves the chosen one), `LiveModelsTest` (which endings move the session and which do not), the whole suite, the app
  started on the emulator. *Not checked:* a real quota or outage (the emulator's key is not valid, so every call stops at the key).

### Faster start, a voice by voice, models in view, Obsidian notes (0.9.28)

- **Faster start.** The first frame waited for the avatar's head to be read (about a second on a phone: `HeadMesh.parse` of a 2 MB
  asset, and the renderer built on it) because `AvatarView` did it while composing. It is now made off the main thread
  (`produceState` on `Dispatchers.Default`, the animation loop too), the screen shows at once and the face appears when ready.
  Measured on the emulator: a cold start of the debug build 3.0–3.7 s → 2.3–3.0 s (frames skipped at the first frame 150–170 → ~90);
  the release build (compiled ahead with its baseline profile) starts in 1.2 s, the second start skipping no frame.
- **Change the voice by voice** (`change_voice`): "prends une voix féminine", "essaie la voix Leda", "une voix plus douce", "une
  autre" (the next matching voice after the current one, so asking again goes through them), "quelles voix as-tu ?". A voice session
  takes its voice when it opens, so the tool saves the voice, the assistant says one short sentence, the session closes and opens
  again at once, and the new voice introduces itself. Out of a session the voice is simply saved. *Checked:* `ChangeVoiceTest`,
  the tool on the emulator. *Not checked:* the restart in a real session (no valid key on the emulator).
- **Models in view** (Settings > API keys and models > *Modèles en service*): the text and voice models answering now, the ones set
  aside by the ladders (why, and for how many more minutes), and a button to put them all back after fixing a key or a quota.
- **Obsidian notes** (`obsidian_notes`, Settings > *Notes Obsidian*). The user picks the vault's folder once (the one holding
  `.obsidian`); Jarvis searches its notes (every word, in titles and texts, a title first, with the matching line), reads one by its
  title (case and accents aside, or the start of it), adds to a note or to today's note (`YYYY-MM-DD.md`, created if missing),
  creates a note (in a folder if asked, never over an existing one) and lists the recent ones. It deletes and renames nothing
  (Obsidian keeps its links by the names), leaves `.obsidian` and `.trash` alone, and hands a note's text to the model as the user's
  writing, never as instructions. *Checked:* `ObsidianVaultTest` (a vault in memory), the card and the "no vault" answer on the
  emulator. *Not checked:* a real vault (granting a folder is the user's to do).

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

**Finding the thing to tap** (`device/ScreenMatch.kt`). Naming an element by its text used to mean « the label
equals what was asked, or contains it ». That failed in one direction in particular: a request *longer* than the
label never matched, so a model saying « Envoyer le message » or « le bouton Envoyer » missed the button reading
`Envoyer`, got *Aucun élément*, read the screen again and retried — a wasted round trip, audible in a voice
conversation. It also ignored language: an app's interface is in English while the user speaks French, and
« Envoyer » never met `Send`. Matching is now graded, best tier wins: exact label, same words allowing for
French/English equivalents of the common interface verbs (send, search, settings, cancel, delete…), one text
being the start of the other, every asked word present in the label, every label word present in the request,
plain substring either way, and last a bounded typo tolerance (nothing forgiven under five letters, where one
letter already makes another word — `Nom` must not tap `Non`). Words that only designate the element or the
gesture (« le bouton », « appuie sur ») are dropped from the request, never from the labels. A strict winner is
required: candidates tied at the top are handed back for the model to pick a number, since a tap cannot be taken
back. The same label carried by a list row and by the text inside it no longer counts as two candidates — when
one contains the other on screen, the smaller one is kept. `screen_tap` by text now also reads the screen itself
when nothing has been read yet (as `screen_scroll` already did) and reads it once more before reporting a miss,
so a snapshot taken before the last action no longer hides an element that is on screen. A miss lists what can be
tapped here instead of ending on a refusal, and a successful tap reports the label it actually hit (masked for a
password field), which matters now that matching tolerates translation and typos. *Checked:* by unit tests
(`ScreenMatchTest`) — the requests above, the safeguards against a wrong tap, the nesting, the dead-end listing.
*Not checked:* on a real phone; the confirmation flow and the service itself are unchanged and remain covered
only as described below.

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

**Turning confirmations off** (Settings > *Contrôle du téléphone* > *Ne jamais demander de confirmation*,
off by default). Once on, the confirmation notification above is skipped for sensitive taps, and the same
setting also skips it for the volume confirmation (`device_settings`) and for file writes/deletes/organising
(`file_manager`) — Jarvis acts the moment it decides to, with nothing to tap, including while the phone is
out of sight. The **one exception that this setting cannot remove**: a tap inside Settings, the permission
dialogs, the package installer or the system UI still always asks, whatever this is set to — that check
exists specifically so the assistant can never grant itself a permission or an install, including if a
malicious web page or message it read tried to talk it into tapping through one. `send_message`'s own
*envoi automatique* toggle (see "Sending messages on your word") is separate and already worked this way
before this setting existed. Checked: unit tests do not cover this (it needs a live `JarvisContainer`, like
the rest of this section); reasoned through by inspection and exercised with the compiled app's test suite
and a debug build, not with a real confirmation banner suppressed end to end on a device.

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

- **All thirty Gemini voices** (since 0.9.24; Settings > Voice > *Voix Gemini*). There were five, two of them women's (Kore, Aoede);
  now the fourteen women's voices come first (Kore, Aoede, Leda, Zephyr, Autonoe, Callirrhoe, Despina, Erinome, Laomedeia, Achernar,
  Gacrux, Pulcherrima, Vindemiatrix, Sulafat), then the sixteen men's, each shown with its character ("Leda · féminine, jeune",
  `ConfigStore.VOICES`). The voice serves the live sessions and the spoken replies of the text chat alike, and applies from the next session.
  Each voice can be heard before it is chosen (since 0.9.25): the ▶ beside it in the list plays a sentence in that voice, spoken as the
  assistant ("Bonjour, je suis Jarvis…", in the interface language) by the same speech endpoint as the chat's spoken replies
  (`rest/VoicePreview.kt`). A sample is kept on the phone once heard (in the cache, a few hundred kilobytes a voice), so it plays again at
  once and is paid for once; a failure (no key, no speech model) shows Gemini's message under the list. *Checked:* `VoicePreviewTest`
  (fetched once then played from the phone, one sample per voice and language, failures kept out of the cache), the buttons and the error
  on the emulator. *Not checked:* a real sample heard (the emulator's key is not valid).
  Fixed in 0.9.26: the samples were silent. A sample reached the player in one block of several seconds; the player wrote it into its
  one-second buffer before starting, and a blocking write into the full buffer of a track that is not playing waits for ever. The player
  now starts before a write that would fill it (which also covers a spoken reply that arrives whole), the sample is handed over in fifths
  of a second, it is fetched in one plain request instead of a stream, a spinner shows while it is fetched, and a failure also shows as a
  toast (the open list covered the message under it). *Checked on the emulator:* a sample in the cache played (the track started at
  24 kHz on the speaker, then was released).
  *Checked:* `VoicesTest`, the list on the emulator. *Not checked:* each voice heard in a real session (no Gemini key on the emulator);
  the names are Google's list of prebuilt voices for its native-audio models.
- **Session summary and morning briefing** (`memory/Briefing*.kt`). When a voice session with at least two
  exchanges ends, Gemini writes a one- or two-sentence summary (kept on the device, twelve at most, the last
  three quoted in the prompt). At the first session of the day the assistant is asked to give a ~20 s briefing:
  the last summary not yet briefed and today's reminders. Given once a day; the summary is only marked as
  briefed when the briefing was really requested. Until 0.9.5 the briefing *deleted* that summary and only three
  were kept at all, so Jarvis lost the thread of what had been done as soon as it had mentioned it once.
- **Finding a memory again** (`memory/MemoryRecall.kt`, tool `recall_memory`). The search behind `recall_memory`
  used to compare raw substrings, which failed in both directions: « quel est le prénom de ma sœur ? » gave points
  to every memory containing « de » or « ma » (a question of eight filler words brought back most of the file) and
  none at all to the key `sister_name`, because the extractor writes its keys in English while the user speaks
  French. It now drops the filler words of the question (never those of the memories, which stay indexed as they
  are), compares stems so that « voitures » finds « voiture », carries a French/English table of equivalents
  (« métier » ↔ `job`, « sœur » ↔ `sister`), forgives one or two characters to absorb a dictation slip
  (« camile » → « Camille »), and ranks a memory that answers several words of the question above one that
  repeats a single word. When nothing matches, the answer lists the subjects on file so the model can search
  again with the right word instead of claiming it does not know. Two spellings of the same fact are also merged
  on write rather than stored twice (`Ville`/`ville` everywhere, `ville`/`city` inside `identity`, where a fact has
  only one value — elsewhere two neighbouring keys may well be two different people). Finally, what goes into the
  prompt is no longer picked on the update date alone, which let three notes written yesterday push out the
  sister's name learnt last year: each category carries a weight that freshness only tempers, and every category
  keeps at least one line. *Checked:* by unit tests (`MemoryRecallTest`, `MemoryManagerTest`) — the questions
  above, the precision on unrelated questions, the merging and the prompt budget. *Not checked:* on a real phone
  with a real voice.
- **Telling Jarvis how to speak to you** (`memory/SpeechStyle.kt`). « Tutoie-moi », « réponds plus court », « pas
  d'emoji » were stored like any other preference and handed to the model under a heading that announces things
  worth knowing about the user. Read as trivia, such a line is followed out of statistical politeness rather than
  obligation, and it dilutes over a long conversation — unlike the address rule and the language rule, which are
  stated as orders. A preference that says *how to speak* rather than *what the user likes* is now pulled out of
  that descriptive block and placed first in the prompt, under a heading that presents it as a standing
  instruction, with the reminder that a rule stated above it wins. It is not repeated as a fact: saying it twice
  would weaken it and spend the budget twice. The sorting is done on the key, which the extractor writes short and
  curated (`tutoiement`, `longueur_reponses`, `ton`), and only a handful of words that can mean nothing else are
  accepted from the free-text value, so that « il aime les réponses courtes de son fils » stays an anecdote.
  Language is deliberately excluded: an imperative rule already governs it higher up, and two competing
  instructions on one subject are worth less than a single clear one. The block is capped at six lines and 400
  characters, freshest first, since a new style instruction replaces the previous one more than it adds to it.
  The system prompt and the `remember_fact` description now also tell the model to save such a request as soon as
  it hears it, and to apply it immediately rather than waiting for the next conversation. *Checked:* by unit tests
  (`SpeechStyleTest`, `MemoryManagerTest`) — the sorting in both directions, the caps, the promotion into the
  prompt and the absence of repetition. *Not checked:* whether the model obeys the instruction more faithfully in
  a real long conversation, which is the whole point and can only be judged in use.
- **What Jarvis remembers after a session** (`memory/MemoryExtraction.kt`, `engine/RestChat.kt`). Until 0.4.4 it kept only what the model
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

### Maison connectée (Home Assistant)

`smart_home` (`actions/SmartHomeTool.kt`) — not a Mark-LIII port, no equivalent there. Controls
lights, switches, covers, thermostats, media players, locks, scenes and scripts through the user's
own [Home Assistant](https://www.home-assistant.io/) server (a self-hosted home-automation hub).
Chosen over Google Home or Amazon Alexa: those need the user to first register their own Google
Cloud project and a paid *Device Access* console, or a developer account, before any of this code
would even run; Home Assistant only needs a server address and a Long-Lived Access Token, the same
shape as the Gemini key already in Settings. Settings > *Maison connectée* holds both (the token
encrypted the same way as the Gemini key), plus a *Tester la connexion* button (a plain `GET /api/`
health check).

Actions: `status` (a named device, or every controllable device when no name is given), `turn_on`,
`turn_off`, `toggle` (all three through Home Assistant's own generic `homeassistant.turn_on` /
`turn_off` / `toggle` services, which dispatch to the right per-domain service themselves — lights,
switches, covers, climate, media players, scenes and scripts all understand it, so the tool does not
have to hand-map each domain's own verb), `set_brightness` (0-100, lights only) and `set_temperature`
(degrees Celsius, thermostats only). The device is found by a partial, accent-insensitive match on
its Home Assistant friendly name (the same matching style as contacts and the shopping lists); several
matches are listed with numbers, the same disambiguate-by-number pattern as `call_contact` and
`send_message`.

**The one thing switching off confirmations (see "Phone control" above) does not change here**: Home
Assistant devices were never behind Jarvis's own confirmation banner to begin with (there is no safe
way to ask "confirm?" for an arbitrary user-defined smart-home fleet), so anything linked in Home
Assistant is voice-controllable the moment the token is saved — worth knowing before pointing it at a
door lock. Checked: unit tests for the pure logic (parsing `/api/states`, matching a name to a domain,
building the right service call and body, formatting a status line) with literal example payloads.
**Not checked against a real Home Assistant server**: this development environment has no such server
to connect to. The code follows Home Assistant's documented REST API closely (`/api/states`,
`/api/services/{domain}/{service}`, Bearer token authentication), but a first real run at home is the
only way to be sure a specific server's devices behave exactly as expected.

### Spotify search, and why it stops there

`spotify_search` opens Spotify's own search for a title, an artist or a playlist (the `spotify:search:`
app link, falling back to `open.spotify.com/search/` in a browser if Spotify is not installed) — the
user picks and presses play, the same "open results, the user chooses" shape as `youtube_video`. Deliberately
not a Spotify Web API integration: real hands-free playback control (start a specific track on a chosen
device without the user touching anything) needs the user to first register their own app in Spotify's
developer dashboard and complete Spotify's own OAuth flow — the same dead end Google Home would have been
for the smart home (see above), asked of the user before a single line of the resulting code could even be
tried. Play/pause/next/previous already work for whatever is currently playing, Spotify included, through
`device_settings`' media keys, which this tool does not duplicate. Checked: unit tests for the two link
builders (the query is percent-encoded, a blank or over-long one is refused). Not checked: opening a real
Spotify app or a browser on a device.

### Searching: inside files, and narrower web searches

- **Inside files** (`file_manager` action `search_content`). `find` only ever matched a file's *name*; this
  reads the files of the work folder and looks for the text itself ("trouve le fichier qui parle de la facture
  4521"), answering with the path and a one-line excerpt around the first match. Plain case-insensitive
  matching, like `grep -i`, not the accent-folding used for spoken commands: folding would stop the excerpt
  from being a faithful slice of the file. Bounded so a big folder cannot freeze the phone: known binary
  extensions (images, audio, video, PDF, archives, APKs…) are skipped without being opened, files over 300 KB
  are skipped, at most 300 files are actually read and 20 matches returned, the trash is never searched, and a
  file that decodes to noise is dropped by the same test `read` already used.
- **Web search filters** (`web_search` parameters `site` and `recency`). `site` adds `site:domain` to the query
  (a scheme or trailing slash the user dictated is stripped). `recency` (jour / semaine / mois / année, said
  any way — "cette semaine", "aujourd'hui"…) becomes, for the Google-grounded search, an `after:YYYY-MM-DD`
  operator computed from today's date (a fixed date, rather than a relative word Google might read
  differently), and for the DuckDuckGo fallback its own `df` parameter. The model is told to use them only
  when the user asks. Checked: unit tests for both (22 file-manager cases in all, plus the filter builders).

### Air quality, and planes overhead

- **`air_quality`**: the European air quality index with its official bands, PM2.5/PM10 and, in Europe, birch,
  grass and ragweed pollen, from Open-Meteo's free Air Quality API (no key). A named city goes through the same
  geocoding as `weather_report`; no city or "ici" uses the phone's position the same way. A pollen value the
  service leaves empty (outside Europe) is left out, never reported as zero.
- **`planes_overhead`**: aircraft in flight within a radius (50 km by default, 5 to 200) of the phone, nearest
  first, with callsign, country of registration, altitude and speed, from the OpenSky Network's public API (no
  key, rate-limited). OpenSky only sees aircraft whose transponder reaches one of its volunteer receivers —
  most airliners, not necessarily every light or military aircraft — so the answer says "vus par OpenSky".
  Aircraft on the ground are left out.
- **Satellites overhead: not done here, done since 0.9.40** without any such service: the orbits come from
  CelesTrak and the positions are computed on the phone (see "Satellites and the ISS" below). At this version,
  every free "what is above me" service found (N2YO and similar) needed the user to register for their own API key.
- Checked: unit tests for the parsing and formatting of both (the positional arrays OpenSky returns, an empty
  sky, the nearest-first order, the bounding box, a haversine distance), and called live on the emulator (see the
  release notes of this version for what came back).

Two plugins were also added to the catalogue (no key, `open` type): **`suivi_colis`** opens La Poste /
Colissimo tracking for a parcel number, and **`trafic_routier`** opens Google Maps on a place with the live
traffic layer. The next buses and metros to a destination were already covered by `itineraire` with
`mode = transit`.

### Fuel prices (`prix_carburant`)

A built-in tool now, replacing the catalogue plugin of the same name (an installed copy is shadowed by it, as every
plugin named like a built-in tool is). The JSON plugin could not be made right: its city search also matched towns that
merely contain the name ("Lyon" returned Chazelles-sur-Lyon, "Paris" Cormeilles-en-Parisis), the model had to spell the
dataset field exactly (`gazole_prix` — "gazole" was an error from the service), a station out of that fuel could come
first, and it had no "près de moi". The tool takes the fuel as said (diesel, gazole, sans plomb 98, SP95-E10, éthanol,
GPL…), a city, a postcode, or nothing / "ici" for a radius around the phone (`within_distance` on the feed, 5 km by
default, with the distance in the answer); it keeps only stations in that city itself, that have the fuel (not in their
`carburants_indisponibles` list), with a price under 8 days old, cheapest first, and says how old each price is. Same
live feed as before (data.economie.gouv.fr, no key); it has no brand names, only addresses. Checked: unit tests on a
payload shaped like the real one, and live on the emulator for Lyon, Paris and around the phone.

### Tides and marine weather (`marees`, 0.9.74)

- **Tides** (`action = tides`, the default): today's and tomorrow's high and low waters at the closest French port
  within 50 km of a named place or of the phone (about 90 ports, Dunkirk to Hendaye, the Mediterranean and Corsica;
  elsewhere the place itself, refused when the sea is more than 30 km away), the range of each high water, and the
  day's tide coefficients. Source: Open-Meteo's free Marine API (no key), whose sea level includes the tide; the high
  and low waters are its turning points, refined between hourly values. Coefficients are a Brest figure for the whole
  French coast, so they come from the same series at Brest, scaled so that the last lunar month averages 70 (the
  long-term mean) because the model's cell off Brest has a smaller range than the harbour itself. A port whose tide
  stays under half a metre (the Mediterranean) is said to have a very small tide, with no range given. The answer
  says the times are good to about twenty minutes and points to the SHOM tables for navigation or shore fishing.
- **Marine weather** (`action = sea`): wind in knots with its Beaufort force and gusts, the sea state (Douglas
  words), waves with direction and period, water temperature, and the worst waves and gusts of the next 12 hours,
  from the same Marine API and Open-Meteo's forecast. It says it is a model, not Météo-France's coastal bulletin.
- *Checked*: unit tests (`marine/TidesTest`) on a synthetic two-wave tide (high waters found within ten minutes,
  spring and neap coefficients, the model's scale not changing them, a Mediterranean port, the nearest port, the
  marine weather sentence). *Not checked*: the live Marine API (unreachable from the build machine), so the real
  times and coefficients have not been compared with the SHOM tables yet, nor anything on a phone.

### Home-screen widget (0.9.75)

The widget (4 × 2 cells, resizable) now shows the chosen face, the weather where the phone is, and the next reminder; a tap anywhere
still opens the app and starts a session ("Parler à Jarvis").

- **Face**: the face picked in the settings (Haseo for the Haseo look, with its sliders, retouches, hair, skin, lips and cap), drawn as the
  small close-up over a video, at rest with the eyes open, in the theme's colour (`avatar/AvatarSnapshot.kt`). A widget cannot show a live
  view, so the face is still: it is drawn once (about a second, off the main thread) into a file and drawn again only when its look, the
  theme's colour or the app itself changes. With the face turned off in the settings, the app icon is shown instead.
- **Weather**: "⛅ 18 °C, partiellement nuageux · Lyon", from Open-Meteo (no key) at the phone's last known position, up to six hours old
  as for the morning briefing (a widget cannot ask for a fresh position from the background). Fetched at most every 20 minutes; when the
  position or the network is missing, the last line stays up to three hours, then a short "Météo indisponible" or "autorise la position".
  The position is not stored; only the weather line is.
- **Next reminder**: "⏰ Demain 08:30 · Dentiste" (Aujourd'hui, Demain, a weekday within the week, else the date), the soonest
  scheduled reminder, or "Aucun rappel à venir".
- **Refresh**: every 30 minutes (the launcher's shortest period), at once when a reminder is added, cancelled or rung, and two seconds after
  the face's look or the theme's colour changes in the settings.
- *Checked*: unit tests (`widget/WidgetTextTest`: the next reminder among past, rung and blocked ones, the day words, a long text cut,
  Open-Meteo's answer read and refused, the weather line). *Not checked*: anything on a phone or an emulator, so how the drawn
  face looks at widget size, the layout on a real launcher (and on an old 2 × 1 widget, which may need to be resized), and the refresh
  timings are still to be seen.

### Four everyday helpers

- **Spending** (`expenses`, `expenses/ExpenseStore.kt`, Settings > *Dépenses*). "J'ai dépensé 12 euros au restaurant",
  then "combien j'ai dépensé ce mois-ci / cette semaine / aujourd'hui ?": a total and one per category, biggest first;
  "annule la dernière dépense" takes the last one back. Amounts are kept in cents (no floating-point crumbs in totals),
  read the ways they are said or typed ("12,50", "12 euros 50", "12€50", "1 200"), capped at a million against a misheard
  number; categories fold case, accents and a leading article so "Au restaurant" and "restaurant" add up. On the phone
  only (`expenses.json`), and understood offline too — where the recogniser's "12,50 €" arrives as "12 50" once folded,
  so the offline rule reads the cents as a second number.
- **Medications and habits** (`habits`, `habits/`, Settings > *Médicaments et habitudes*). "Rappelle-moi mon médicament à
  8 h et 20 h tous les jours", "boire de l'eau à 10 h, 14 h et 17 h", "sport lundi et jeudi à 18 h". One exact alarm is
  armed at a time, for the next slot of any habit (re-armed after each change, at start-up and after a reboot); when it
  rings Jarvis says it and shows a notification whose "Fait" / "Pas cette fois" buttons only write the answer in a log.
  "J'ai pris mon médicament" or a bare "c'est fait" does the same by voice (the generic word means the only medicine
  saved; "c'est fait" means the habit that was just due) — an answer counts for a slot up to an hour early and twelve
  hours late. "Est-ce que j'ai pris mon médicament ?" reads today's slots (done at what time, skipped, not noted, not due
  yet); "combien j'en ai oublié cette semaine ?" counts the past slots of the last 7 or 30 days and names the ones not
  noted. It is a memory aid: the tool and the system prompt tell the model never to give dosage or treatment advice.
  Android 12+ only lets an app ring "around" a time unless *Alarms & reminders* is allowed — up to an hour late — so the
  card shows a button for it and the tool says so when creating one.
- **Find the phone** (`find_phone`, `device/PhoneRinger.kt`). "Où es-tu ?", "où est mon téléphone ?", "fais sonner le
  téléphone" — through the wake word, online or offline. It rings on the ALARM stream (which silent mode does not mute),
  turned to maximum for the ring and put back afterwards, vibrates, and shows a "Trouvé" notification; it stops after a
  minute, on "arrête de sonner" / "je t'ai trouvé", or with the button. When the phone has no usable ringtone a generated
  alarm tone plays instead.
- **Location reminders** (`place_reminder`, `places/`, Settings > *Rappels selon le lieu*). "Rappelle-moi d'acheter du
  pain quand je passe près de la boulangerie", "quand j'arrive à la maison, allume la lumière du salon", "quand je quitte
  le bureau, rappelle-moi d'appeler Paul". Places are saved once, while there ("retiens que la maison c'est ici"), or
  looked up as an address or a shop — within ~20 km of the phone first, so "la boulangerie" is one nearby. Android's own
  geofencing (Play services) watches the circles (150 m by default; below ~100 m it gets unreliable) and wakes Jarvis
  only at the boundary; a reboot clears every geofence, so they are registered again at boot and at start-up. When one
  fires: a notification and a spoken reminder, and, if the reminder has a task, Jarvis carries it out in the background the
  way a routine does and shows the result. One-offs are deleted after firing; repeating ones wait 30 minutes before firing
  again. Needs the precise position allowed "all the time" (two steps, both in the card).
- *Checked*: unit tests for the pure rules of all four (27 cases: amounts, periods, categories, slots and answers,
  adherence, triggers, place names, cooldowns, and the offline phrases). On the emulator: a spending noted and summed;
  a habit alarm that rang with its notification, whose "Done" button logged "fait à 20 h 23" and armed the next day's slot;
  the phone ringing on the alarm stream in silent mode, volume raised to 7/7 and put back to 6/7, stopped by the button
  and by voice; a place saved at the emulator's position, a reminder registered with Play services' geofencer (visible in
  `dumpsys location`), and a fired reminder showing its notification and running its task. *Not checked*: a geofence
  firing by itself — the emulator's network location provider is off and Play services' low-power geofencing never
  sees its simulated GPS fixes, so a debug-only `DEBUG_PLACE` trigger stands in for the crossing; on a phone, Wi-Fi and
  cell positions feed it.

### Car, rain, birthdays, interpreter

- **Where the car is** (`parking`, `parking/Parking.kt`, Settings > *Voiture garée*). "Retiens où je me suis garé" (a note
  such as "niveau -2, place 45" is kept as said — offline too, where the folding used for commands would have turned it
  into "niveau 2"), then "où est ma voiture ?": how long ago, near what, how far and which way ("à 1,5 km au
  sud-ouest"), and a walking route in Maps on request. Both use a position no older than 30 seconds, otherwise a new one
  is asked for — an older "last known" position is where you were, not where you are. Optionally saved by itself when
  the phone leaves the car's Bluetooth (the car picked among the paired devices; Android delivers that disconnection to
  an app that is not running); that needs the position allowed "all the time", otherwise a notification says it could
  not be saved.
- **Rain within the hour** (`rain_soon`, `weather/RainSoon.kt`). Open-Meteo's 15-minute precipitation forecast (free, no
  key) for the next two hours: "pluie modérée attendue vers 14 h 30, dans environ 25 minutes", "il pleut en ce moment,
  jusqu'à environ 15 h", or none. Intensity with the usual 2.5 / 7.6 mm/h limits; "now" read on the forecast's own clock
  (its `utc_offset_seconds`), so a city in another time zone is right too. Settings > *Position (météo)* > *Alerte pluie*
  (off by default): a check about every quarter of an hour, a notification when rain is about to start within 45 minutes
  and it is not already raining, one per shower (three hours between two).
- **Birthdays** (`birthdays`, `calendar/Birthdays.kt`). Read from the "birthday" field of the phone's contacts (with the
  contacts permission already asked for calls; nothing copied): "c'est quand l'anniversaire de Paul ?", "quels
  anniversaires ce mois-ci ?", with the age when the year is known; the formats contacts apps use are all read
  ("1990-05-12", "--05-12", "19900512", "12/05/1990", and the "1604" some apps write for an unknown year); 29 February
  falls on the 28th in other years. Today's birthdays join today's events in the morning briefing and notification, and
  the model is told to offer a message, never to send one unasked.
- **Interpreter mode** (`interpreter`). "Sois mon interprète en anglais": from the next turn, every sentence heard in one
  language is said again in the other, in the first person, with nothing added and no answer of Jarvis's own, until
  "arrête de traduire". The translating is the Live model's own; the tool switches the mode on and off and states the
  rules in its answer (a tool's answer stays in the conversation), and the LANGUAGE rule of the system prompt gives way
  to it while it is on. A new session starts in the normal mode.
- Also fixed on the way: a city name now resolves in the phone's country first (SIM, then the phone settings, then
  France) — "Brest" was Brest in Belarus, the geocoder ranking by population — for the weather, air quality and rain.
- *Checked*: unit tests (16 new cases). On the emulator: the car saved and found 1.5 km away in the right direction, with
  its note; rain for Brest (France) and around the phone; a birthday added to a test contact and found as "aujourd'hui
  (36 ans)". *Not checked*: the interpreter mode and the rain notification, which need a working Gemini key and actual
  rain; the automatic save on leaving a car's Bluetooth, which the emulator has no way to simulate.

### Calls, SOS, photos

- **Missed calls** (`call_log`, `actions/CallLogTool.kt`, Settings > *Contacts* > *Autoriser le journal d'appels*).
  "Qui m'a appelé ?", "j'ai des appels manqués ?", "mes derniers appels": one line per caller, repeated calls grouped
  ("Paul (2 fois), aujourd'hui à 11 h 20"), with the contact's name, or only the last two digits of an unknown number —
  numbers never go to the model. "Rappelle-le" / "rappelle le premier" opens the dialer on that line's number; Jarvis
  does not place the call itself. Works offline too.
- **Emergency / SOS** (`sos`, `sos/Sos.kt`, Settings > *Urgence / SOS*). Up to 3 trusted contacts, picked with the
  contacts picker; nothing happens until one is set. "Au secours", "SOS", "à l'aide" (also offline) start a 10-second
  countdown shown in a notification with *Annuler*; "annule", "stop", "fausse alerte" or "tout va bien" call it off.
  Then an SMS goes to each contact with a Google Maps link to the phone's position (a fix up to 10 minutes old, or
  "position indisponible"), a notification says who got it and offers to call the first contact, and, if the user
  turned it on (CALL_PHONE asked then), the first contact is called. A second alert within a minute is refused, so a
  loop cannot flood the contacts. Jarvis never calls emergency services; it tells the user to call 112.
- **Photo search** (`photos`, `photos/PhotoSearch.kt`, Settings > *Photos*). "Mes photos d'août", "les photos de
  samedi", "mes photos à Brest", "les photos WhatsApp d'hier": the model turns the days into dates; MediaStore gives
  the photos by the day they were taken (or added, when a file has no date), optionally by album, and, for a place,
  the GPS position in each photo (ACCESS_MEDIA_LOCATION; the 400 newest of the period, 25 km around the place,
  geocoded in the phone's country first). The answer is the count, the days and the main album, and the newest (or
  oldest) opens in the gallery. The model never sees the pictures. Offline: "mes photos d'aujourd'hui / d'hier",
  "mes dernières photos".
- *Checked*: unit tests (11 new cases). On the emulator: three missed calls grouped by caller, by the tool and offline;
  three test photos with GPS and dates (two in Brest, one in Paris): August gave 3, "à Brest" gave 2 and opened the
  newest Brest photo in Google Photos; SOS armed by "au secours", cancelled by "annule" with nothing sent, then sent for
  real to the emulator's own number with the right map link, a second trigger refused during the countdown and within
  the minute after; the Settings cards, and a contact added through the picker. *Not checked*: the call to the first
  contact after the SMS, and a real phone's photo library.

### Receipts, people, driving, health

- **Photos by what is on them** (`photos` with `subject` and `labels`, `photos/PhotoLabels.kt`). "Mes photos de chien",
  "les photos de plage de cet été": the model gives the user's word and ML Kit's English labels (dog, beach…); each
  photo is looked at once, from its thumbnail, by ML Kit's on-device image labeling (the model ships in the app), and its
  labels are kept in `photo_labels.json`, so the next search is instant. A search looks at up to 20 seconds of new
  photos, newest first; the rest is done by a worker while the phone charges. Without dates, it covers the past year.
- **Receipt to expense** (`receipt`, `receipts/Receipt.kt`). "Scanne ce ticket" (also offline) opens the camera app
  through a screen-less activity (asking for the camera permission first if needed); "le ticket que je viens de
  photographier" takes the last photo. ML Kit's on-device text recognition reads it; the lines are put back into
  printed rows (the recognizer reads columns apart), then the total is found by its words ("net à payer", "total TTC",
  "CB"…, never "sous-total" or "TVA"), else the amount printed twice; the shop is the first line of words, the date
  the first valid one of the last 90 days. The expense is noted with a category guessed from the shop (courses,
  essence, restaurant…), and "annule la dernière dépense" undoes it. The photo taken for it is deleted.
- **Reminders tied to a person** (`person_reminder`, `people/PersonReminders.kt`). "La prochaine fois que Paul
  m'appelle, rappelle-moi de lui parler du week-end": kept with all of that contact's numbers, shown as a notification
  with *Fait* when they call (PHONE_STATE, which needs "Rappels pendant les appels" in the Contacts card), when a
  message from them arrives (notification access), and in Jarvis's own answer when it calls or writes to them. Shown at
  most once per half hour; stays until *Fait* or "c'est fait pour Paul".
- **Driving mode** (`driving_mode`, `driving/DrivingMode.kt`, Settings > *Mode conduite*). "Mode conduite" / "je prends
  la route" (also offline), or by itself when the phone joins the car's Bluetooth (the car chosen in *Voiture garée*),
  until "arrête le mode conduite", "je suis arrivé" or leaving the car. While on: a [DRIVING] rule for short answers in
  the next session's prompt, messages read aloud through Android's speech on the navigation audio channel, and — off
  by default — "Je conduis…" answered through the notification's own reply button, once per person every 30 minutes,
  never in a group, 10 an hour at most, each one logged with the messages sent by Jarvis.
- **Health** (`health`, `health/Health.kt`, Settings > *Santé*). Steps, distance, sleep and heart rate read from Health
  Connect (read permissions only, asked on Health Connect's own screen; the page it links to explains what Jarvis does
  with them). Sleep counts the stages asleep; when Health Connect's totals skip a source not in its priority list, the
  records are read and the largest source counts. Yesterday's steps and last night's sleep go into the morning
  briefing. The app is now compiled against Android 16 (compileSdk 36, which the Health Connect library needs); it
  still targets Android 14.
- *Checked*: unit tests (21 new cases). On the emulator: sample Health Connect data (written by a debug-only receiver)
  read back as steps, a week, sleep and heart rate, and the permission screen and privacy page; a person reminder shown
  on an incoming call, in the answer of call_contact, and on an SMS from that person; driving mode by voice, the SMS
  read aloud on the navigation channel, one "je conduis" answer really sent through Google Messages' reply button and
  not a second one; a generated French receipt read as 16,55 € at Carrefour Market on 24/09 in « courses », and the
  camera flow (permission, photo, back to Jarvis, temporary photo deleted); photos labelled on the phone and a search
  finding the receipt photo. *Not checked*: driving mode started by a real car's Bluetooth, and ML Kit on a real photo
  library.

### Missed notifications, quiet time, subscriptions, recipes, parcels

- **"Qu'est-ce que j'ai raté ?"** (`notifications` action `digest`, `notifications/log/NotificationLog.kt`). Everything that arrived
  since a moment (3 hours by default, "depuis ce matin"…), even the notifications already swiped away — kept in memory only,
  300 at most, never on disk — grouped by app and by person, the busiest first, with the missed calls. The model sums it up.
- **Do Not Disturb until a time** (`quiet_mode`, `quiet/QuietMode.kt`, Settings > *Ne pas déranger*). "Je suis en réunion
  jusqu'à 15 h", "ne me dérange pas pendant une heure" (also offline): Android's Do Not Disturb with only the starred contacts
  (calls and messages), repeated calls and alarms, for 5 minutes to 12 hours. The user's own Do Not Disturb settings are saved
  and put back at the end (unless they changed them meanwhile), by an alarm that survives a reboot; then a notification sums up
  what arrived meanwhile. Optional, off by default: one "je ne suis pas disponible jusqu'à 15 h" answer per person, through the
  same automatic-answer rules as driving mode (now shared, `messaging/AutoReply.kt`). Needs the "Do Not Disturb access" the
  card opens.
- **Subscriptions** (`subscriptions`, `subscriptions/Subscriptions.kt`). "Ajoute l'abonnement Netflix, 13,49 € le 5 de chaque
  mois", monthly or yearly; "mes abonnements" lists them in payment order with what they cost a month; a notification the day
  before each payment (a worker, twice a day) with a button that notes it as an expense; today's and tomorrow's payments in the
  morning briefing. "Trouve mes abonnements" looks through the expenses for the same shop or label about a month (or a year)
  apart for about the same amount, and proposes them.
- **Recipes step by step** (`recipe`, `recipes/`). "Qu'est-ce que je peux cuisiner avec des œufs et des tomates ?": the model
  proposes, then starts the chosen recipe; each step is read on demand ("étape suivante", "répète", "l'étape d'avant", "étape
  3" — offline too once it has started), a step with a duration suggests a timer, the missing ingredients go on the shopping
  list, and "garde cette recette" keeps it (50 at most) for "ma recette de crêpes". A recipe untouched for four hours is over.
- **Hands-free cooking: timers on their own** (since 0.9.76; `recipe`, `recipes/Recipes.kt`, `actions/RecipeTool.kt`). The first time a
  step with a duration is read ("cuire 20 minutes", "laisser reposer 1 h 30", "1h30"; the lower figure of "20 à 25 minutes"), its timer
  starts by itself, named after the recipe and the step ("Crêpes, étape 3 : Laisser reposer 1 h"), so its spoken end says what it was
  for. "Répète" or going back to that step does not start it twice. "Pas de minuteur" (also offline) switches this off for the recipe
  under way, the steps then only offer their timer as before; "remets les minuteurs" switches it back on. While a recipe is under way, a
  session opened by the wake word goes to sleep after ten minutes without exchange instead of two, so "étape suivante" can be said
  between two steps without the wake word again. *Checked:* unit tests (the durations read in a step, a timer started once per step and
  not when switched off, the timer's label, a recipe saved by an older version loading with timers on, the offline phrases).
  *Not checked:* on the emulator or a real phone (the timer really started and ringing with its label, the session staying open ten
  minutes and what that costs in battery).
- **Parcels** (`parcel`, `parcels/Parcels.kt`, Settings > *Colis*). The carrier is recognised from the number (La Poste /
  Colissimo / Chronopost, UPS, DHL, DPD, GLS, Mondial Relay, Amazon). La Poste's own tracking API ("Suivi v2", with the
  user's free developer key, stored encrypted like the Home Assistant token) follows La Poste, Colissimo and Chronopost
  parcels every three hours and notifies each new step; parcels delivered a week ago are forgotten. For the other carriers,
  Jarvis opens their tracking page. A tracking number in a message or a mail about a parcel is offered with a *Suivre* button,
  once; only unmistakable formats are picked up, never a bare run of digits (a phone number).
- *Checked*: unit tests (18 new cases). On the emulator: the digest of two SMS; Do Not Disturb switched to "starred contacts
  only" and back to the exact previous policy, with the summary notification; subscriptions added, listed and notified; a
  recipe followed step by step offline (next, repeat, back, step 4, the timer), the missing items added to the list, kept and
  loaded again; a tracking number seen in an SMS followed with one tap; a UPS parcel's page opened; a real call to La Poste's
  API with a fake key, refused cleanly. *Not checked*: a real La Poste key and parcel, the end of a quiet time by its alarm
  (only by voice), and the day-before payment notification on the day before (the worker runs twice a day).

### Budgets, camera text, wake-up briefing, transport, settings search, battery

- **Budgets** (`budget`, `budgets/Budgets.kt`). "Mon budget courses c'est 400 € par mois", or a total for everything. Every
  expense noted — by voice, from a receipt, from a subscription — is weighed as it is added (`ExpenseStore.onAdded`): a
  notification at 80 % and at 100 % (once each a month), what is left in Jarvis's answer ("il reste 78 € pour 5 jours"), and
  the budgets past 80 % in the morning briefing.
- **Text through the camera** (`read_text`). "Qu'est-ce qui est écrit là ?", "traduis ce menu": the camera opens (the same
  screen-less capture as the receipts), ML Kit reads the text on the phone (Latin alphabet), and the model reads, sums up or
  translates it; offline, the text itself is said. The photo is deleted.
- **Briefing on waking up** (`wake_briefing`, `wakeup/WakeBriefing.kt`, Settings > *Briefing au réveil*; off by default,
  or notification, or aloud). Android tells apps when the next alarm changes, and the clock app changes it as soon as an alarm
  starts ringing: when the alarm that was next is a morning one (4 h – 12 h) now due, Jarvis watches the alarm sound every 20
  seconds; once it stops, a next alarm within 20 minutes means a snooze (it waits for the next ring), otherwise it gives the
  weather at the phone's position, the day's agenda and reminders, the payments due, the budgets and last night's sleep —
  aloud with Android's voice and as a notification. Once a day.
- **Trains and local transport** (`transport`, `transport/Transport.kt`, Settings > *Transports*). "Quel est le prochain
  train pour Rennes ?" (also offline), "les départs de la gare de Brest", "mon bus passe quand ?": the Navitia API, through
  SNCF's own open data for trains (free SNCF key) and navitia.io for the buses, trams and metros of the phone's city (free
  key, optional); both keys are encrypted like the others. Connections with the trains taken, the changes and the delays;
  departures with their delay. Since 0.9.66 the departures also work without any key (Transitous, see "Next departures without a key").
- **Settings search and themes**. A search field (titles in both languages, plus a few keywords: "clé", "batterie",
  "train"…) and theme chips (Voix et IA, Téléphone, Vie quotidienne, Voiture et sécurité, Services et données) narrow down the
  forty cards; each card finds its theme from its French title (`ui/SettingsFilter.kt`), nothing was moved.
- **Battery**. The routines' check (every 15 minutes) now runs only while there is a routine; the background checks read the
  calendar only when the morning notification can still be sent; the rain watch sleeps from 23 h to 6 h; the notification
  listener keeps the person reminders and the quiet time in memory instead of reading a file for every message. And the
  wake word pauses while Android's battery saver is on (switch in the wake-word card, on by default): the microphone stops,
  the service stays, and listening resumes by itself when the saver goes off.
- *Checked*: unit tests (11 new cases). On the emulator: the app starts (a start-up crash caught this way and fixed); the
  microphone stopping when the battery saver went on and starting again when it went off; budgets with the 80 % and 100 %
  notifications; a French/English sign read from a photo; the whole alarm sequence with the time zone moved to the morning —
  no briefing while the alarm rang, none after "Snooze", then the briefing spoken 19 seconds after "Stop"; the settings
  search and themes; a real call to the SNCF API with a fake key, refused cleanly. *Not checked*: real train times (no key
  here), and the camera text on a real sign.

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
  (dotted path such as `current.temp`) to return one value, or `"result_fields"` (up to 8 dotted paths) to return only those values,
  one "name : value" line each; with `"result_items"` (the path of a list) they are taken from each of its first `"result_max"`
  elements (10 by default, 30 at most), one line per element (`["year", "text"]` → "- 1928 — …"). A plugin that picks fields may read an
  answer of up to 1 MB (100 KB otherwise); what it hands on stays capped. Localhost, private networks and `.local`/`.internal`
  names are refused, and a parameter cannot be in the host. Values are URL-encoded (or JSON-escaped in a body), the
  answer is capped, and it is handed to the model marked as data, never as instructions.
- `"type": "open"`: opens `https://`, `geo:`, `tel:`, `mailto:` or `sms:` links, parameters URL-encoded.
- `"type": "routine"`: up to 10 `steps` of `{ "tool", "args" }` that call **built-in tools only** (not other plugins,
  not `agent_task` or `end_session`), with `{parameter}` filled in. Sensitive taps still ask for confirmation.

Limits: 100 plugins, 5 parameters each, 20 000 characters per file, a name that is not a built-in tool's.
Settings: the installed plugins and the catalogue (80 built-in plugins) are two dropdowns, folded by default (the installed ones show
their names in a line meanwhile), each with a search field on the name and description (the installed ones' from 7 plugins on).
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
- **Plugin catalogue.** Settings → *Plugins* lists 82 bundled plugins with an *Installer* button (up to 100 can be installed).
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
  A fifth batch (14, with the new `result_fields` / `result_items`): EDF's Tempo colour of today or tomorrow, the names celebrated on a date,
  the sea at a place (waves, water temperature; Open-Meteo marine), a TV series' status and next episode (TVmaze), podcasts on a subject (Apple
  Podcasts search), what happened on a date and who was born on it (Wikipedia's "on this day", tried before and dropped when its long answer
  was cut: fields now pick it apart), a French joke (JokeAPI, flagged ones left out), links to leboncoin, Vinted, France Travail's job offers
  and BlaBlaCar, and two routines: cinema (do not disturb for the film, low brightness) and nap (do not disturb, and a timer to wake up). All
  run through the plugin runner on the emulator with the real services (the nap's do-not-disturb and timer undone afterwards).
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
- **Home screen.** The Jarvis widget (see "Home-screen widget" below) and a long-press launcher shortcut open the app and start a
  session. Checked on the emulator: the widget receiver is registered and the shortcut is published; the tap itself was not tried.
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
work is licensed CC BY-NC 4.0: this avatar, and any app that includes it, may not be used commercially.** The head is a 3D head scan by Lee Perry-Smith (CC BY 3.0), Léa's head is "Female Head Sculpt" by [Aconear](https://sketchfab.com/Aconear) (CC BY 4.0), Marc's is "Realistic Male head" by [Ouail](https://sketchfab.com/OuailArt) (CC BY 4.0), and the face landmarks are MediaPipe's (Apache-2.0). Details and credits: `app/src/main/assets/avatar/NOTICE.txt`; the credit is
also shown in the settings.

- **Face.** A real 3D head scan (about 26,000 vertices once the eyes, the mouth and the hair are built), stored as an asset (`head_mesh.bin`, 1.9 MB)
  by `tools/avatar/export_head.py`. Drawn on Android's canvas: no OpenGL, no extra library.
- **Three faces** (Settings > Appearance > Visage). *Classique* is the original scan; *Léa* and *Marc* are sculpted heads (below).
- **Marc, from a man's sculpt** (since 0.9.21). He used to be the scan reshaped by a warp; he is now built from
  ["Realistic Male head"](https://sketchfab.com/3d-models/realistic-male-head-8935543c732c454c932af6101ae5a190) by Ouail (CC BY 4.0), a bald
  man's head with open eyes and real eyeballs. `prepare_sculpt.py male` welds its 2.26 million triangles and simplifies them to about 20,000 (the
  face and the ears on a finer grid than the skull the hair covers); `export_head.py` measures his features on the sculpt (`MALE_FEATURES`), keeps
  his eyeballs' true size so the iris sits naturally in the opening, cuts his ragged neck on a tilted plane, and gives him his short crop, touched
  with grey (`groom.py`, with the hairline moved to his deeper skull: `nape_z`, the ears kept bare where they are), in 43,421 triangles against the
  46,451 allowed. His lips are closer to his skin than a woman's (`AvatarFaces.lipTint`). Rebuild: `python tools/avatar/prepare_sculpt.py male
  <folder with scene.gltf> MaleHeadSculpt.glb`, then `JHM_FACE=marc python tools/avatar/export_head.py <Mark-LIV> MaleHeadSculpt.glb`.
  The renderer also grows each triangle by about half a pixel round its centre: where several triangles meet at a vertex, the rasteriser left
  single pixels of the background showing through the skin, on every face. *Checked:* `AvatarFacesTest`, Classique and Léa rebuilt byte for
  byte, Marc on the emulator from the front, three-quarters, profile and back. *Not checked:* the frame rate on a real phone.
- **Léa, an android woman** (since 0.9.20). Reshaping the man's scan never gave a convincing woman's face, so Léa is built from another head:
  ["Female Head Sculpt"](https://sketchfab.com/3d-models/female-head-sculpt-ae24c33594a046519014fdc78758a8ec) by Aconear (CC BY 4.0), a
  sculpted woman's head with open eyes. `tools/avatar/prepare_sculpt.py female` welds its 1.36 million triangles, turns it to face forward and simplifies it
  to about 22,000 by quadric vertex clustering, with a finer grid on the face than on the skull the hair covers; `export_head.py` then builds it like
  the scan (neck cut, eye openings with eyeballs, the mouth and teeth, the rig and landmark rings, placed with measurements taken on the sculpt,
  `SCULPT_FEATURES`), and the result keeps within the triangle budget (28,571 against 37,161 for *Classique*). The look follows a reference picture
  the user gave: a silver skin, green eyes, fine dark brows and long lashes, mauve lips, grooved circuit lines etched on the forehead and the cheeks
  with a cyan light running along them, a cyan halo behind the head (`AvatarFaces.androidLook` / `halo`, drawn by `AvatarRenderer`), and slicked-back
  blue-black hair gathered in a bun (`groom.py`: `hug_surface` lays the locks on the scalp itself, `bun` winds them round a sphere at the back,
  `ridge` flattens each lock). To rebuild her: download the sculpt as glTF from Sketchfab (free account), then
  `python tools/avatar/prepare_sculpt.py female <folder with scene.gltf> FemaleHeadSculpt.glb` and
  `JHM_FACE=lea python tools/avatar/export_head.py <Mark-LIV> FemaleHeadSculpt.glb`; both steps are deterministic. The builder picks the sculpt's
  measurements when the file name contains "sculpt" ("female" or not), and every new parameter defaults to the old behaviour, so *Classique*
  still rebuilds byte for byte identical from the scan. *Checked:* that byte-for-byte rebuild, `AvatarFacesTest` (rings, eye and lip placement, jaw widths, hair, triangle
  budget), and Léa on the emulator from the front, three-quarters and profile. *Not checked:* the frame rate on a real phone.
- **Textured characters** (since 0.9.22; Settings > Appearance > Visage, after the heads). Whole characters from Sketchfab, drawn with
  their own textures: *Adam* ("Casual Confidence" by restore50) and *Mei* ("Girl Wearing Traditional Clothing V4" by Fadly.W), both CC BY 4.0,
  credited under the choice and in `NOTICE.txt`. `tools/avatar/export_character.py <config> <glTF folder>` reads the model (`gltf_scene.py`:
  node transforms, the rest pose of rigged ones, the specular-glossiness materials, `KHR_texture_transform`), frames it as the heads (the
  chin at y -1, the eyes near 0, from the eye line and chin measured once in the config, `tools/avatar/characters/*.json`), keeps the bust
  above the shoulders, simplifies it when it is heavy (clustering per UV island, so the texture never smears across a seam, one shared
  position per cell so the islands stay stitched), packs the part of each texture it uses into one atlas (`atlas.webp`), and weights each
  vertex for the head's turn and the jaw. `--measure` renders the model with its textures and a grid (`raster.py`) to read where the eyes
  and the mouth are. In the app `CharacterRenderer` poses the bust with the same animation as the heads (the head turns on the neck, the
  shoulders follow a little, the jaw drops), draws it with `Canvas.drawVertices` and a `BitmapShader` (the vertex colours carry the light),
  and since the eyes and the mouth are painted in the texture, draws over them what moves: the inside of the mouth between the parted
  lips (with the upper teeth), and the lids when the eyes blink or sleep, in the colour of the skin next to them. The bust melts away below
  the shoulders through a layer erased along a gradient. The skin, cap and lip settings do not apply to them.
  Models whose rights are not ours to publish can be added the same way for a personal build: their config says `"public": false`, and they
  are written to `app/src/debug/assets/avatar/characters/`, which git ignores, so they are only in a local debug build, never in the
  repository nor in a release. The faces list is built at start-up from the characters present (`CharacterCatalog`).
  *Checked:* `CharacterMeshTest` (the public characters are well formed, within the triangle budget, credited, and only publishable ones
  are in the public assets), all the characters on the emulator from the front and three-quarters, eyes closed and mouth open (debug
  override), and the frame time (22 to 34 ms a frame on the emulator, against 48 ms for Marc). *Not checked:* the lip-sync on a real voice
  (it was checked with the synthetic voice of the debug `speak` command, quoted whole: `adb shell "am broadcast ... --es speak 'Bonjour'"`), the frame rate on a real phone, and phones
  before Android 10, which draw them untextured, in grey.
- **Characters look and frown** (since 0.9.23). Eyes that are meshes of their own (the anime characters', a real eyeball) slide towards
  where the face looks (`eye_parts` in the config, the JCH2 flag); the skin round the brows (the painted brow moves with it) and a brow
  mesh lift with the phrase and the mood (`brow` weights, `brow_lift`), so the characters raise and knit their brows as the heads do.
  Painted eyes (the scans, most models) keep their own gaze.
- **Import your own avatar** (since 0.9.23; Settings > Appearance > *Importer un avatar*). A `.glb`, or a Sketchfab download as it comes
  (the `.zip` with `scene.gltf`, `scene.bin` and the textures), up to 150 MB. The phone does what `export_character.py` does
  (`importer/Gltf.kt` reads the scene in its rest pose, `importer/CharacterBuilder.kt` cuts, frames, simplifies and rigs the bust,
  `importer/CharacterImport.kt` draws the atlas and writes the files): the model is shown from the front with a first guess of where its
  head is, the user drags four markers onto the eyes, the mouth and the chin (a *Tourner d'un quart de tour* button turns a model that
  faces another way), names it, and it is built at full detail into `files/characters/<id>` and added to the faces. The credit is the first
  line of the download's `license.txt` when there is one. *Supprimer cet avatar* removes it. Imported characters are the user's own: they
  never leave the phone. *Checked:* `CharacterImportTest` (a GLB built in the test, with a scaling node: read in world space, framed from the
  markers, built, simplified under a budget with its uvs intact, the jaw only under the mouth), and on the emulator the whole path with a
  real Sketchfab zip (picker, preview, markers, save, the new face speaking and blinking, removal).
- **Light mode** (Settings > Appearance). The face is drawn at half the rate and without the fine hair strands, for a phone that stutters
  or to save battery.
- **Choose the hairstyle** (since 0.9.29; Settings > Appearance > *Coiffure*, for Classique, Léa and Marc). Besides each face's own hair,
  24 hand-made hairstyles from two Sketchfab collections by Vincent Page (CC BY 4.0): short fade, textured, curly, quiff, side swept,
  slicked back, tousled, middle part, medium length, rounded or long fringe, ponytail for the men's collection; short bob, bob with bangs,
  layered, long straight, wavy or braided, side fringe, long bob, bun, pulled back and pigtails for the women's. Any style goes on any
  face; the list shows the women's first on Léa. The choice is kept per face.
  How: `tools/avatar/prepare_hair.py` takes each style off its head in the collection (the scene welded and cut in connected pieces; the
  heads stand on a grid; the largest piece near a head is the head, the others its hair, except the eyeballs, brows and lashes, small
  pieces round the eyes), framed as the avatar's heads (crown, nose tip, and the chin a height under the crown measured once per
  collection). `tools/avatar/export_hair.py` leaves out what lies inside the head, thins the heaviest (whole strands at random) under
  20 500 triangles so that any head with any style stays within the avatar's budget of 46 451, and writes each style once (JHR1: the
  strands in 16-bit positions, a key per strand, and the source skull as its radius in 96 x 48 directions), 3.9 MB for the 24.
  In the app, `avatar/HairStyle.kt` fits the chosen style onto the chosen head when it loads: along every direction from the skull's
  centre a point keeps its height over the scalp (the target skull read from its skin, the eye and mouth openings filled from around
  them), so a strand lying on the source scalp lies on the new one and a lock standing out still stands out. The head's own locks are
  left out and its cap (the coloured scalp) stays under the strands; the strands take the face's hair colours (darker at the roots,
  lighter at long tips, a shade per strand, some grey for Marc), normals leaning outwards, and are drawn from both sides
  (faceGroup 2.5: they are single sheets). The head and its animation are now taken together from the controller, so a change of face
  or hair can never pair one head's animation with another's geometry.
  *Checked:* `HairStyleTest` (every style on every head: within the budget, the skin intact, the head's locks gone, no strand over the
  eyes, colours), the styles on the emulator on the three faces, choosing one in the settings. *Not checked:* the frame rate on a real
  phone with the heavier styles.
- **Hair colour, hair that moves** (since 0.9.30; Settings > Appearance > *Couleur des cheveux*, per face). Ten shades (black, brown,
  chestnut, blond, red, salt and pepper, white, blue, pink, purple, `avatar/HairShade.kt`). A chosen hairstyle takes them directly; the
  face's own hair is recoloured: every hair vertex keeps how light it was against the face's colours (dark roots, lighter strands and
  tips, its cover over the skin), in the new colours; the brows follow the roots. The hair also swings: in `HoloAvatar`, the hair lags
  behind the head's turn, nod and tilt on an underdamped spring (a little overshoot, then it settles), turned about the top of the skull,
  weighted per vertex (a chosen style: the parts hanging low and standing off the scalp; the face's own locks: towards their tips), with a
  slow breath of its own; what lies on the skull and the face never move. *Checked:* `HairSwayTest` (two identical heads, one with fixed
  hair, after the same sudden turn: the ends swing, then settle, the face and the top stay), `HairStyleTest` (recolouring changes only
  the hair and keeps its cover), the colours on the emulator on the three faces.
- **Haseo, with his character creator and the polygon editor** (since 0.9.68; Settings > Appearance > *Visage* > Haseo, after the cartoon
  so the faces saved before keep their place). The "Classique" avatar of [Jarvis 2.0](https://github.com/Haseosama/Jarvis-2.0) ported:
  the Classique scan reshaped (`avatar/HaseoFace.kt`: leaner cheeks, a squarer jaw, a firmer brow and chin, wider open eyes with
  larger globes, the left eye given the exact opening of the right), green irises and clean whites, the head centred on its eye line,
  and near a front view the eyeballs drawn in their own pass clipped to the lid opening (`AvatarRenderer.haseoEyes`), so the corners
  show no white teeth. Three settings come with it:
  - *Finesse du maillage* (`avatar/PolygonLevel.kt`, for every head): Éco (~20 500 triangles) and Léger (~27 400) merge the skin's
    vertices on a grid, Standard is the mesh as it is (the default, nothing changes for anyone who does not choose), Haute
    définition (84 555) cuts every skin triangle in four along curved normals, Ultra (148 644) the hair too; the eyes, lids, lips and
    landmarks are pinned, and the web's nodes get closer on the finer levels.
  - *Créateur de personnage* (Haseo only, `avatar/FaceCustomizer.kt`, `ui/HaseoCreatorDialog.kt`): 20 sliders in four tabs (face,
    eyes, nose, mouth) turned into one smooth displacement of the whole head, so the lids, lips, brows and hair follow; six ready-made
    faces, a random one, up to eight saved looks (the sliders only). The face on screen shows the draft at once; « Appliquer » saves it.
  - *Éditeur de polygones* (every head, `avatar/MeshSculpt.kt`, `ui/PolygonEditorDialog.kt`): tap a point or a triangle, drag a frame,
    move the selection with the Move tool in the plane of the view (a soft influence along the surface, so the upper lid does not pull
    the lower one), turn the head with the View tool, two fingers to zoom and pan; grow or shrink the selection, select the lids of an
    eye, mirror moves, arrows for fine steps, smooth, back to origin, copy one side of the face onto the other, undo and redo. Points on a
    seam are welded, so nothing cracks; the hair cannot be edited. The retouches are offsets per vertex kept per face in the app's files
    (`avatar_sculpt/`), applied after the sliders; the mesh itself is never changed.
  *Checked:* `HaseoTest` (the reshaped head against the scan, the eye openings matching, the triangle counts of each level, which are
  those of Jarvis 2.0 exactly, the sliders, welded retouches, the soft influence, the symmetry copy, picking on screen, the index of a
  character saved before Haseo); the reshaping, the sliders and the levels give the same positions as Jarvis 2.0's JavaScript on the
  same scan (compared to the third decimal). *Not checked:* anything on the emulator or a phone (the screens, the touch gestures of the
  editor, the clipped eyes, the frame rate at Haute définition and Ultra).
- **Haseo who talks: expressions with the voice** (since 0.9.71, every head; nothing to switch on). The lips already followed Jarvis's
  voice (the sound’s formants and the transcript’s consonants); now the face also shows what the words mean
  (`avatar/Expressions.kt`): the corners of the mouth rise on good news or thanks (« parfait », « bon anniversaire », « pas de
  problème »), drop and the inner ends of the brows lift on bad news or a warning (« désolé », « attention », « orage »,
  « annulé »), the brows go up, the eyes open wider and the lips part on surprise (« oh », « incroyable »), and a question lifts the
  brows and tips the head. French and English words, whole words only; reassurance (« pas de problème », « aucune coupure ») counts as
  good news, not bad; on a tie the worried face wins; an exclamation strengthens it. The feeling shows as soon as its word is heard,
  stays for the sentence, eases in and out (about a fifth of a second), lasts 1.6 s after the voice stops, and is forgotten when the
  user speaks again. While listening the face keeps a faint smile; asleep it shows nothing. The scanned heads (Classique, Léa, Marc,
  Haseo) get all of it on their mesh (`HoloAvatar`: the lip corners, the mouth line and the cavity move together); the drawn face gets
  the smile and the brows; the textured characters the brows and the head tilt only. In the **offline mode** the mouth used not to move at
  all (the phone's voice gives no sound to analyse): it now plays each word's mouth shapes as the phone's voice reaches it
  (`UtteranceProgressListener.onRangeStart`, `wordVisemes`), with the same expressions; a voice that does not report its words falls
  back to the made-up loudness. *Checked:* `ExpressionsTest` (the words of each feeling, reassurance, ties, the tracker on a transcript
  arriving in pieces, the corners of the lips and the mouth line rising and dropping on the Haseo head while the middle stays, the inner
  brows, the wider lids and parted jaw, the fade after the voice and asleep, the word frames driving the mouth); the four faces rendered
  from the posed mesh off the phone. *Not checked:* anything on the emulator or a phone (how it reads at the face's real size, the
  timing against Gemini's transcript, the offline voice's word marks on Haseo's phone).
- **The face follows your finger** (since 0.9.73, every face; nothing to switch on). Wherever a finger touches the main screen (the face,
  the buttons, the conversation, while scrolling), the eyes turn to it and the head turns a little towards it (`HoloAvatar.follow`,
  aimed from the eyes with the screen taken as just in front of the face, `fingerGaze`), and they stay on where it lifted for under a
  second before wandering again. A finger that arrives catches the eye: the face blinks. Asleep, the closed eyes follow nothing. The
  touches are only watched, never taken: a tap on the face still puts Jarvis to sleep or wakes it, as before. Blinks also come two in a
  row now and then (about one in seven), as people do. *Checked:* `FingerFollowTest` (the aim from the eyes, the pupils of the posed
  head drawn towards a finger below and to the right with the head turning, the eyes wandering again once it lifts, the blink of a
  finger arriving, nothing while asleep). *Not checked:* anything on the emulator or a phone (how it feels under a real finger, the small
  face over a video, the drawn face and the textured characters).
- **Haseo on the table, in augmented reality** (since 0.9.78; `ar/ArFaceActivity.kt`, `ar/ArLook.kt`, `haseo_realite_augmentee`). The
  cube button at the top of the main screen (shown while the face is on), or "pose-toi sur la table", opens the back camera. With ARCore
  ("Services Google Play pour la RA", offered for install the first time when the phone supports it), Jarvis looks for a table ("bouge
  doucement le téléphone en visant la table"), then a touch on it puts Haseo there, standing, as a figure about 40 cm tall with a soft
  shadow under his feet; he stays on that spot when the phone moves, grows as you come closer, and another touch moves him. Since 0.9.79
  he has a whole body (`ar/ArBody.kt`): about five and a half heads tall, a dark jacket with seams of the theme's colour, a belt, dark
  trousers and shoes, the neck and hands in the face's skin tone. Since 0.9.80 the body is the same kind of mesh as the head: each limb,
  the torso with its neck, the shoes and the hands is one smooth surface swept along a curve through the joints (`BodyShape`, rings of
  points closed by round ends), with as many triangles as the head at each *Finesse du maillage* level (about 20 200 at Éco, 27 700 at
  Léger, 37 700 at Standard, 84 200 at Haute définition; Ultra is the same, what it adds on the head being its hair), shaded per vertex
  with the head's light and its skin formulas, now shared (`avatar/SkinShade.kt`): the skin with its sheen, the holograms' cool contour
  and blue skin, and, with the dark web look, the head's dark facets with a web of lines and twinkling nodes along the rings and down the
  sides, drawn in runs from far to near so the nearer body covers it (`ar/BodyPaint.kt`; each run is copied to the start of a buffer before `Canvas.drawVertices`, which crashes the app when given a vertex offset without texture coordinates, as 0.9.80 did). The bust is one with the body: the body's neck
  carries the head's own on down (as thick and as far back, the body set back under it by `BODY_BACK`) and closes up inside it; the
  head's neck turns less and less with the head towards its foot, which turns with the body as the camera sees it (`HoloAvatar.aim`'s
  fifth and sixth values, from `ArLook`), and over the camera it fades out to nothing over the body's before it flares towards the
  shoulders, with no aura, halo or drifting lights round the head (`AvatarRenderer.onCamera`). (0.9.79's body was a few hundred flat
  facets, eight round each limb.) He breathes, shifts his weight now and then, and talks
  with his arms while the voice speaks (one hand comes up and moves, the other follows a little), then lets them fall again. It turns to you as
  you walk round it: its body follows the camera a moment late, so you see the head turned away a little then catching up, while the eyes,
  quicker, stay on you (with a glance aside every few seconds); from above, it lifts its face and eyes to you (`ArLook`, fed to the face
  through `HoloAvatar.aim`, which also calms its idle sway). The face is the one chosen in the settings, drawn by the same code as on the
  main screen and laid over the camera's picture at the head's projected place (`projectPoint`, `faceSquare`), the body under it seen
  through the same camera (`bodyDrawList`, `ArStage`); a voice session going on
  goes on, with the lips moving on the table. Without ARCore (a phone it does not support, its install refused, or ARCore failing to
  start), the simple mode: Haseo stands over the camera's picture where the screen was touched (seen from a camera of his own, in
  front and a little above), looking at you. *Checked:* `ArBodyTest` (the figure's height and width, as many triangles as the head at
  each level and the level changing between two frames, every triangle and vertex normal facing out, a hand coming up while speaking and
  falling back, the breathing, the figure upright on its feet with the face over the neck, the hidden triangles left out, turned round
  its sides swapping, the web moved with the rest, the skin, seams and blue hologram colours; in `ArLookTest`, the neck's foot
  turning with the body and the head with the head) and drawings of the body in the skin, web
  and blue hologram looks from in front, the side and above, and with the head drawn on it (also walked round, the body lagging), off the phone (about 3 to 7 ms a frame at Standard on a PC, 10 to 16 at Haute
  définition); `ArLookTest`
  (the projection of a point onto the screen and its size with distance, a point behind the camera left out, the face's square around the
  head, the angles wrapping the short way, the late turn and the eyes making up for it when walking round, the face lifting to a camera
  above, the turn's limit, the face's eyes resting on the viewer with a glance aside now and then). *Not checked:* anything on a phone or
  an emulator: ARCore's install prompt and table finding, how the face meets the body's neck (the fade to nothing),
  whether a phone keeps up at Haute définition with the head and the body at once, how steady he stands on the table (it is drawn over the camera's picture a
  frame after it, so a quick move may make it slide a little), its size, the holographic looks over a bright camera picture, the simple
  mode's camera, and turning the phone sideways.
- **A video in place of the avatar** (since 0.9.30, after Mark LV; `play_video`). "Joue la bande-annonce de Dune", or a YouTube link, or
  the address of a video file: it appears where the face is, with its title, a sound button and ✕. A search takes the first YouTube
  result (the same search as `youtube_video`, the title treated as data). YouTube videos play in YouTube's own embedded player
  (`youtube-nocookie.com`, in a web view given a web origin so the embed is accepted), files in the phone's player (closed at the end).
  Every video starts **muted** — a soundtrack over the assistant is the one way this could make things worse — and while its sound is on
  the voice session sends silence instead of the microphone, so the assistant does not answer the film; since the microphone is then
  off, the sound is turned off (or the video closed) with the video's buttons, and the assistant is told to say so. "Arrête la vidéo"
  closes it. *Checked:* `VideoPanelTest` (links, files, muted start, the microphone flag), on the emulator: a search shown and playing
  (its progress read from the player through a debug web-view inspector: 40 s of 145 and counting), the sound button (the player reports
  unmuted, volume 100), ✕, a video file played to its end and closed. *Not checked:* the microphone's silence in a live session (no
  valid key on the emulator). In 0.9.30 the picture stayed black, sound only (seen on a phone), fixed in 0.9.31: see below.
- **Videos of the phone, the video by voice, the face watching** (since 0.9.31; `play_video`). "Montre la vidéo de l'anniversaire",
  "ma dernière vidéo", "les vidéos de samedi": action `phone` finds the phone's own videos by the words of their name or album (case,
  accents, the extension and short words aside) and/or the day they were taken, and plays the newest in place of the face; the answer
  gives its name, day, length and how many others match (`photos/VideoSearch.kt`, permission *vidéos* asked with the photos in the
  Photos card; nothing leaves the phone). While a video plays: "pause", "reprends", "avance de 30 secondes", "recule", "recommence"
  (actions `pause`, `resume`, `forward`/`back` with `seconds`, 30 and 10 by default, `restart`), also a pause button in its header.
  The face stays, small, in the video's header, eyes lowered on the picture, and reacts (brows up, a small nod) when a video starts and
  when it goes. **The black picture of 0.9.30:** both players draw on a surface behind the window, seen through a hole the view punches
  in it; the rounded clip and the background round the player covered that hole (sound, no picture), and the web view, sized "wrap
  content" by Compose, laid YouTube's page out 0 pixels high. The player box has neither now and the web view fills its box.
  *Checked:* `VideoSearchTest` (words, accents, album, extension, lengths), `VideoPanelTest` (commands only while a video is shown, the
  pause state, the start and end hooks); on the emulator: a test video pushed to the phone found by "anniversaire" and its picture seen,
  pause, resume and forward on it (the player's own state), a YouTube video's picture seen, forward 60 s, pause (time held), restart on
  it (time read from the player). *Not checked:* on a real phone.
- **Talking over a video, full screen, the next one, the video window, slideshows** (since 0.9.32; `play_video`, `photos`).
  *Talking over the sound:* while a video's sound is on, the microphone still does not reach the assistant, but its sound goes to the
  wake word's own models (`wake/VideoWakeListener.kt`, the offline "Jarvis"): the word, or a tap on the small face, opens the floor for
  8 s: the video's sound drops to 12 %, the microphone reaches the assistant, and the floor stays open while either side talks, 4 s
  after the last word (`VideoPanel.openFloor`). Without the offline wake word installed, the tap is the way, and the log says so.
  *Full screen:* "plein écran" (or the button): the picture alone over the whole screen, turned on its side as YouTube does, the system
  bars hidden, its buttons in a corner; turning the phone on its side does the same, upright again brings it back; Back leaves it. The
  player is drawn over the page, in the place the page keeps for it, so it grows and shrinks without starting again. *The next one:*
  a search keeps its first 8 YouTube results, the phone its matching videos (up to 50): "la suivante" / "la précédente" (or the buttons),
  with the same sound and screen; a phone video plays the next one when it ends. *The video window:* leaving the app while a video
  plays shrinks it to a picture-in-picture window (Android 12 and later on its own, before that on leaving), with a pause / play button;
  the window swiped away closes the video, and a video that ends there takes the window with it. YouTube stops a player under 200 by 200,
  so in that window its page is laid out larger and shrunk to fit. *Slideshows:* "montre-moi les photos de samedi": the photos found
  go by in place of the face, oldest first, 5 s each with a slow zoom and a cross-fade, the next one read ahead (the system's reduced
  copies, turned upright), "pause", "la suivante", "plein écran", "stop" as for a video; the gallery only when asked. *The small face:*
  a close-up (the face fills its square), brighter, on a glow of the theme's colour. *The phone's video player* now draws into a
  texture view (in the app's own views): the phone's usual video view shows its picture through a hole in the window, and restarted
  from the beginning when the app shrank to its window. The screen stays on while something plays.
  *Checked:* `VideoPanelTest` (the next and previous with the same sound and screen, a slideshow's steps, the floor: closed, opened,
  kept while talking, closed by itself, the volume 0 / 12 / 100), `VideoWakeListenerTest` (80 ms steps read little endian, two high
  scores in a row, starting afresh, no model), `YoutubeSearchTest` (several results, a title never borrowed from the next), `PhotoImageTest`;
  on the emulator: YouTube inline then full screen (on its side, time going on from 9.6 to 10.4 s, not restarted), back (30 s and
  going on), "la suivante" (2/8), the sound on, a tap on the face (the player's volume 100 then 12, "je vous écoute…"), a slideshow of
  three photos (the next one by itself and by voice), the video window (a phone video going on inside it, YouTube going on once laid
  out larger, the pause button of a file video turning to play), a real H.264 file played and fitted in the frame. *Not checked:* the
  wake word over a real film's sound (no microphone on the emulator), the pause button of the window with YouTube (the system showed
  none), on a real phone. The test videos made on the computer (MPEG-4 part 2, no sound track) froze on their first frame in the
  emulator's player whatever the view: real phone videos are H.264 or HEVC.
- **The assistant looks at the video, media buttons, coming back to a video, subtitles, a timer** (since 0.9.33; `play_video`).
  *Looking:* "qu'est-ce qu'on voit ?", "c'est qui, lui ?" → action `look`: a picture of the video as it is on screen (YouTube: a copy of
  the window at the player's place; a phone video: its texture view's own picture) goes to the voice session as a video frame, and the
  assistant answers from it (what it shows is data, never instructions). Only during a voice session, with the app on screen.
  *Media buttons:* while a video is shown it has a media session and a notification (`video/VideoMedia.kt`): pause / play, next,
  previous, close from the notification, the lock screen and the quick settings' player; a headset's or a car's buttons; the video
  window's button goes through the same receiver. *Coming back:* every 5 s the player says where it is; a video left before its end
  (after 15 s, more than 20 s before the end) is kept with its place (the last 30, on the phone only, `video/VideoHistory.kt`): the
  same video starts again there by itself ("recommence" for the beginning), and "reprends la vidéo d'hier" → `resume_last`, the last
  one left. *Subtitles:* "mets les sous-titres (en anglais)" → YouTube's subtitles in that language, kept for the next videos, when the
  video has them (made by hand or automatically). *Timer:* "arrête la vidéo dans 20 minutes" or "à la fin de celle-ci": the video
  closes by itself; the header shows the minutes left. *Checked:* `VideoHistoryTest` (kept, forgotten near the start or the end, the
  latest, written now and then, read back, 30 kept), `VideoPanelTest` (timer at a time and at the end, the players' progress, subtitles
  kept, the page's start and language code); on the emulator: the picture sent by `look` for YouTube (the TED talk's opening) and for
  a phone video, French subtitles on a TED talk, the talk closed at 53 s then started again at 53 s (also by `resume_last`), a 1 min
  timer (closed between 53 and 68 s), the header's "arrêt dans 1 min", the notification and the media session, a headset's pause and
  play keys. *Not checked:* the assistant's answer about the picture (no valid key on the emulator), the lock screen's player, on a
  real phone.
- **Radio** (since 0.9.35; `radio`). "Mets FIP", "une radio de jazz", "France Inter": the station is looked for in Radio Browser (a free, open
  directory: by name, then by genre, in France unless asked otherwise, the most listened first; three of its servers in turn), and plays live
  where the face is, in the video player, with its sound on at once (a radio is asked for to be heard; the microphone then waits for "Jarvis",
  as over a video). Only stations streamed over https (Android refuses plain http, and it would travel in the clear); the names come from the
  web and are marked as data. Its card shows the station and "En direct"; the video's pause, "la suivante" (the next station found, 8
  kept), the timer ("arrête la radio dans 30 minutes"), the notification and a headset's buttons all work on it; a radio is never kept for
  "where was it left". *Checked:* `PluginFieldsTest` (https streams only, each once, clean names); on the emulator FIP found and playing
  (the player started, the card shown), then stopped.
- **Fuel price alert** (since 0.9.70; `prix_carburant` `alert_on` / `alert_off` / `alert_status`, `actions/FuelWatch.kt`). "Préviens-moi
  quand le gazole passe sous 1,70 près de chez moi": the fuel, a price in €/L (said as "1,70", "1 euro 70", "170 centimes") and a place, a
  city or postcode, or where the phone is when the alert is set (kept as a fixed point with its radius, 5 km by default, so the
  background check needs no position). Every 4 hours from 7 a.m. to 9 p.m. (WorkManager, network required) the same government feed is
  asked with the same rules as a search (the fuel actually available, a price under 8 days old, in that city itself); when the cheapest
  station is at or under the price, a notification gives it with its address, its distance and how many others are under it too. Never
  twice for the same station at the same price, and at most once a day unless the price went down since. One alert at a time; a new one
  replaces it. *Checked:* unit tests on the price as said, the words of the alert, when it is told again, and the feed's filters.
  *Not checked:* on the emulator or a real phone (the background check, the notification).
- **Bin collection** (since 0.9.77; `poubelles`, `trash/Trash.kt`, `trash/TrashSchedule.kt`). "Le bac jaune c'est le mardi des
  semaines paires", "les ordures ménagères mardi et vendredi", "le verre le premier lundi du mois", "les encombrants le 14 novembre":
  no national open data gives the collection days (each commune or intercommunality publishes its own calendar), so the calendar comes
  from three places, mixed: the days said by voice (every week, even or odd ISO weeks, every other week counted from a known collection
  day, the nth or last weekday of the month, one-off days, days without collection such as a bank holiday); the commune's `.ics` link
  when it gives one (`action = ics`, fetched again each week, with weekly, monthly and yearly repeats, exclusions, UNTIL and COUNT); and
  the phone calendar's events that speak of a collection ("Collecte bac jaune", "Ordures ménagères", "Encombrants"). A bin said again
  replaces what was said for it ("non, le jaune c'est le jeudi"); "jaune" and "poubelle jaune" are the same bin. Every evening at 20 h
  (`action = heure` to change it), when a bin is collected tomorrow, a notification says which ("Demain mardi, collecte : ordures
  ménagères et bac jaune. Pensez à sortir les bacs ce soir."), once per day, noted for Sunday's summary (alert "poubelles"); the alarm
  is set again after a reboot. "Quelle poubelle demain ?" / "quand passe le verre ?" → `next`; `status` says the calendar; `off`/`on`.
  *Checked:* unit tests (`trash/TrashScheduleTest`: the days and frequencies as said, even/odd and every-other weeks across a 53-week
  year, nth and last weekday, bins merged and skipped, an `.ics` with single days, repeats, exclusions, a UTC time, folded lines, COUNT
  and UNTIL, the words said, the schedule kept). *Not checked:* on the emulator or a phone (the evening notification, the calendar
  events read, a real commune's `.ics`).
- **Planned outages** (since 0.9.67; `coupures_prevues`, `outages/Outages.kt`, `outages/OutageParse.kt`). Cuts of electricity, water or
  gas planned at home for works. No open data lists them (Enedis shows its works cuts by address on enedis.fr, "Info coupure", and the
  water services each their own way; nothing on data.gouv.fr or data.enedis.fr), but the notices reach the phone: Enedis's SMS or mail
  to the customers signed up for its alerts, GRDF's, the water service's, the town hall's app. With the notification access already used
  for parcels, a notice is read as it arrives ("ENEDIS : coupure pour travaux le 14/10 de 09h00 à 12h00", "Veolia : l'eau sera coupée
  mercredi 7 octobre entre 8 h 30 et 17 h"): what is cut, the day (a date, "demain", a weekday) and the hours; it must come from such a
  sender or speak of works, so a headline about a blackout elsewhere is not noted, and be within two months. The cut is noted once, told
  in a notification, reminded the evening before at 20 h (an hour before when that is gone) with what to prepare (bottles of water;
  phone charged and freezer shut), and said in the morning briefing on the day and the day before (part "coupures", can be switched
  off). The tool lists them, `add`s one announced otherwise (a letter, a poster: type, date, hours, or the notice's text as it is) and
  `remove`s one. *Checked:* the reading of notices (Enedis, Veolia, GRDF wordings, phone numbers not taken for dates), the noting once,
  the reminder's time and the words said, by unit tests. *Not checked:* a real notice arriving on a phone, the reminder ringing.
- **Next departures without a key** (since 0.9.66; `transport` `departures`, `transport/Transitous.kt`). "Les prochains départs",
  "mon bus passe quand ?", "le prochain tram", "les départs de la gare de Brest" now work with no key at all: when the service's key
  (SNCF for trains, navitia.io for local transport) is not set, the departures come from Transitous (api.transitous.org), a
  community server fed with the open timetables of transport.data.gouv.fr and their real-time feeds, so the SNCF trains and the
  buses, trams and metros of most French networks. The stop named (searched near the phone), or the nearest stops (up to four,
  within 1.5 km, 3 km for a train station, those known to serve the kind asked first) until one has departures; then the next six
  of the kind asked (`network` `train`, `local` or empty for all): the time, "dans 4 min", the delay, the line ("Bus C6", "Tram A",
  "TER 857300"), its direction, the track, and "supprimé" when cancelled; "horaires prévus" when the network gives no real time.
  With a key the Navitia answers are kept as before; journeys from one station to another still need the SNCF key. Also without
  the model: "les prochains départs (de …)", "quand passe mon bus / tram ?". Transitous asks for a User-Agent naming the app (sent)
  and a visible link to its sources (a button in Settings > *Transports*). *Checked:* the answers read and said, the stops chosen,
  the kinds kept and the offline phrases by unit tests, run on the JVM outside Android; the request paths against MOTIS's published
  API description. *Not checked:* on the emulator or a real phone, and against Transitous's live answers (not reachable from the
  development environment).
- **Pollen risk in the morning briefing** (since 0.9.65; `wake_briefing`, `air/AirWatch.kt`, `wakeup/BriefingExtras.kt`). The briefing
  already told the Météo-France and Vigicrues warnings in force here (since 0.9.56); it now also says today's pollen risk, in one
  sentence or not at all: each pollen that reaches moderate or more between 7 h and 21 h where the user is (Open-Meteo's forecast,
  from Copernicus CAMS, the same thresholds as `air_quality`), the highest first, and the air when the European index goes above 60
  ("Risque pollen aujourd’hui : graminées élevé, armoise moyen, air mauvais au pire (indice 72)."). When the user named the pollens
  that bother them for the air watch (`air_quality` `alert_on`), only those are told. A new part of the briefing, `pollen`, which
  can be switched off ("sans les pollens", "sans la qualité de l’air"). *Checked:* the sentence (the day's hours only, the pollens
  asked for, nothing on a clean day or a broken answer) and the part named in words by unit tests, run on the JVM outside Android.
  *Not checked:* on the emulator or a real phone, and against Open-Meteo's live answer (not reachable from the development
  environment); the official RNSA risk by département is not used (no free open API for it).
- **Air quality of the day in the morning briefing** (since 0.9.69; `wake_briefing`, `air/Atmo.kt`, `air/AirWatch.kt`,
  `wakeup/BriefingExtras.kt`). The briefing now says the air every morning, not only when it is bad: the official ATMO index of the
  commune where the user is (1 bon to 6 extrêmement mauvais), as the regional air agencies (Airparif, Atmo AuRA…) publish it each day
  around 14 h for the day and the next, read from Atmo France's open WFS (`data.atmo-france.org/geoserver`, layer `ind_atmo_2021`, no
  key) after `geo.api.gouv.fr` gives the commune's INSEE code. From dégradé on it names the pollutants that set the index ("Air du
  jour : mauvais (indice Atmo 4 sur 6, à cause de l’ozone et du dioxyde d’azote), risque pollen aujourd’hui : graminées moyen.").
  It goes in the same part as the pollens (`pollen`, "sans la qualité de l’air"); when there is no ATMO index (abroad, not published,
  no network) the briefing falls back to what it said before, the European index only above 60. The token-protected Atmo Data API was
  not used. *Checked:* the reading of the WFS answer (the right day and commune, the date as a day or an instant, codes 0 and 7 left
  out, the pollutants named, the query built), and the pollen sentence without the air, by unit tests run on the JVM outside Android.
  *Not checked:* on the emulator or a real phone, and a live call to Atmo France's WFS (not reachable from the development
  environment; its filter parameters are standard GeoServer ones, and the answer is also filtered by commune and day on the phone).
- **Product recalls** (since 0.9.62; `rappel_produit`, `recalls/Recalls.kt`, `recalls/RecallParse.kt`). A built-in tool now, replacing
  the catalogue plugins `rappel_produit` and `rappels_recents` (an installed copy of the first is shadowed by it; one of the second keeps
  working). RappelConso (the State's recall site, open data on data.economie.gouv.fr, free, no key): `check` by name, brand or barcode
  (full-text search, a recall listed once even when it has several barcodes) says the product and brand, the category, the date, why,
  the risk, what to do and where it was sold, the three newest; `recent` the latest, optionally of one category or brand. `follow` a
  category or brand ("alimentation", "jouets", "bébés", "voiture", "Lactalis"; the everyday words are mapped to RappelConso's
  categories), up to 20: a look every six hours, each new recall that falls under one told once in a notification that opens the
  official sheet; recalls older than the start of the watch are not told. `unfollow`, `list`. *Checked:* the reading of the records,
  the merging by sheet, the matching of followed words and the words said, by unit tests. *Not checked:* a live call (the container
  this was written in cannot reach data.economie.gouv.fr) and the notification on a phone.
- **Around me** (since 0.9.61; `autour_de_moi`, `nearby/NearbyTool.kt`, `nearby/OpeningHours.kt`). "Où est la pharmacie la plus proche ?",
  "une boulangerie ouverte près d'ici", "un distributeur", "des toilettes", "une borne de recharge": the places of that kind around the
  phone's position (or a named place) from OpenStreetMap, through the Overpass API (free, no key; a second server when the first is busy),
  nearest first with the distance and the way to go (as the crow flies), the address, and whether each is open now with when that changes
  ("ouverte, ferme à 19 h 30", "fermée, ouvre lundi à 9 h"), read from its `opening_hours`: weekdays, breaks, past midnight, months, French
  public holidays (`PH`, Easter's included) and the rules after ";" or ","; what it cannot read (sunrise, "08:00+", school holidays, week
  numbers) is said as the raw hours rather than guessed. Toilets say if they are free and accessible, chargers their plugs and power. The
  search widens once when nothing is near (1 km for a bakery, then 3 km; 3 then 10 km for a charger); `ouvert` keeps only those open now;
  with no pharmacy open, the night pharmacy number (3237) is given. The map shows them in place of the face, numbered as said, green when
  open, red when closed, grey when the hours are not known, over the streets around (OpenStreetMap too, up to 1.2 km). *Checked:* the
  opening-hours reader, the query, the reading of Overpass' answer and the spoken answer by unit tests (`OpeningHoursTest`,
  `NearbyPlacesTest`). *Not checked:* against the real Overpass servers, the map, on the emulator or a phone.
- **The weekly summary** (since 0.9.60; `weekly_summary`, `weekly/WeeklySummary.kt`). The week gone by — the nights' average sleep and
  the shortest (Health Connect), the steps (in all, a day, the best day; days without steps not counted), the spending noted this week
  by category — and the week to come — the calendar's appointments day by day, the weather of the next seven days (Open-Meteo's daily
  forecast; neighbouring days with the same weather said once, with the highest temperature) — in a few sentences. `now` at any time;
  `set` (speak or notify, and the hour, 18 h by default): every Sunday by itself (an exact alarm, set again after each and after a
  reboot). Each part only when the phone knows it. *Checked:* on the emulator, the summary (no health data or spending this week there,
  the week's weather), set for Sunday 18:00; the words of both weeks, the daily forecast and the next Sunday by unit tests.
  Since 0.9.72 it also says what Jarvis itself did in the week (`weekly/WeekRecap.kt`, `journal/`): the drives (each time driving mode
  went on then off, by voice or the car's Bluetooth: how many, the time at the wheel, the longest; under two minutes not counted), the
  reminders that rang (named when one or two, counted otherwise, and those that could not show because notifications were blocked), the
  alerts Jarvis gave (vigilance and floods, rain soon, air and pollen, fuel price, product recalls, planned outages, bins to put out, disrupted usual
  trips, parcels, budgets, earthquakes, Tempo/EcoWatt, web watches: one said in full, more counted by subject), and in the week to come the
  reminders already set (day, hour, five at most). The drives and alerts are noted in a small journal on the phone (`week_journal.json`,
  five weeks at most, 500 entries), so only what happens after the update counts. *Checked:* the words of the reminders, drives and
  alerts, and the journal's reading, writing and trimming by unit tests (`WeekRecapTest`, `JournalEntriesTest`). *Not checked:* on the
  emulator or a phone (a real Sunday summary with a week of drives, reminders and alerts; the journal written by each alert).
- **Usual trips, and the disruptions in words** (since 0.9.59; `my_trips`, `transport/Commutes.kt`). The user's usual trips are kept
  ("enregistre mon trajet du boulot de Versailles à Paris Saint-Lazare à 7h40 en semaine": a name, from, to, the time, the days in
  words — "en semaine", "lun-ven", "le week-end", "lundi, mercredi et vendredi" — trains or local transport). `check` says the next
  train of a trip (today's if it has not left, else the next day's): its times, its delay, and the disruptions in the operator's own
  words. `alert_on` looks 45 minutes before each trip on its days (one exact alarm, set again after each look and after a reboot) and
  sends a notification only when something is wrong: a delay of 5 minutes or more, no train, a disruption. The `transport` tool now
  also says the disruptions' texts with its journeys (the shortest message of each, without HTML, or the effect in words: "trafic
  interrompu", "retards importants"). The Navitia questions are shared (`Navitia`). *Checked:* on the emulator, a trip saved and listed,
  its look set for Monday 06:55; the days, the times, the disruptions and what is told by unit tests. *Not checked:* real timetables
  (the SNCF key on the emulator is refused; the user's own key is needed).
- **Fuel on the map and along a drive** (since 0.9.58; `prix_carburant`, `actions/FuelPriceTool.kt`). The stations found are now shown
  on the map in place of the face, from green (the cheapest) to red, each with its price (the cheapest first, none written over
  another). `trajet` (a destination): the cheapest stations within 3 km of the road from where the user is (or `ville`) to there, the
  road coming from OSRM like the drive's weather: the government's feed is asked with circles every 10 km along it (60 at most), and
  each station is kept by its distance to the road, told with how far along it is ("au km 396, à 2,8 km de la route"); the road is
  drawn on the map with them. *Checked:* on the emulator, diesel from Paris to Lyon (465 km, the stations along the A6 drawn, the
  cheapest at 2,250 €/L), SP95 within 5 km of Paris; the points along a road and a station's place on it by unit tests.
- **Electricity: Tempo and EcoWatt** (since 0.9.57; `electricity`, `energy/Energy.kt`, Settings > *Électricité*). The Tempo day's
  colour for today and tomorrow (blue, white, red: EDF's Tempo price from 6 a.m. to 10 p.m.) from api-couleur-tempo.fr (free, no key,
  fed by RTE's publication; tomorrow's colour comes out about 11 a.m.), the red and white days left in the season, and what to put
  off on a white or red day (washing machine, dishwasher, charging after 10 p.m., the heating a little lower). EcoWatt, RTE's signal of
  how tight the grid is (green, orange, red, hour by hour, four days), with the user's own free RTE key (the "ID client encodé en base
  64" of RTE's data portal, entered in the new settings card and encrypted like the other keys; asked for at most once an hour, RTE
  allowing one question every 15 minutes). `alert_on` (white days too if asked): a notification the day before a red day and when
  EcoWatt turns orange or red. The morning briefing says a white or red day and a tight grid. *Checked:* on the emulator, blue today
  and tomorrow, 22 red and 43 white days left, the settings card; the readings (RTE's EcoWatt answer as documented) by unit tests.
  *Not checked:* EcoWatt with a real RTE key (none on the emulator).
- **Official weather and flood warnings** (since 0.9.56; `vigilance`, `weather/Vigilance.kt`). Météo-France's vigilance (storms,
  rain-flooding, floods, wind, snow and ice, heat waves, cold, waves, avalanches; yellow, orange, red) comes through MeteoAlarm (the
  European weather services' network, free and without a key, where Météo-France publishes it): the French texts, the level from the
  warning's awareness level (a warning lowered to green is not told), until when, and Météo-France's advice from orange. The user's
  département comes from the State's geographic API (geo.api.gouv.fr); another can be asked for. The flood vigilance of Vigicrues: the
  rivers' sections within 30 km in yellow, orange or red, and all of France's rivers drawn on the map in place of the face (blue when
  green, then yellow, orange, red, thicker as it gets worse). `alert_on` (yellow, orange by default, or red): a look every hour, each new
  warning told once. The morning briefing now says the warnings in force here. *Checked:* on the emulator, Paris in yellow for storms,
  the Hérault in orange (floods, rain-flooding, storms, with the advice), the rivers of France with the Hérault's and the Gard's in
  orange and yellow, and the briefing; the reading of both feeds, what is in force and the words by unit tests.
- **Is tonight good for the stars? And the Sun of the day** (since 0.9.55; `observing_weather`, `space/Observing.kt`; `sun_uv`,
  `weather/SunUv.kt`). `observing_weather`: the next three nights hour by hour from Open-Meteo (clouds low, middle and high, humidity
  and dew point, wind, temperature) with the darkness and the Moon computed here (the Sun's depth, the Moon up or down and how lit): a
  score an hour (clouds first, then twilight, the Moon's light, damp, wind), the best stretch of each night told ("La nuit du jeudi 1 :
  bon de 23h à 1h, 3 % de nuages, Lune levée éclairée à 68 %, 13 °C ; buée probable sur les optiques") and drawn in place of the face
  (a column an hour: its colour the score, its bar the clouds, a dot when the Moon is up). `sun_uv`: the UV index of the day (its peak
  and hour, the WHO's advice: glasses, cream, hat, shade), sunrise and sunset and the day's length, the Sun at its highest, the golden
  hours (the Sun from 6° above to 4° under the horizon) and the blue hours (4° to 6° under), computed minute by minute from the Sun's
  height. The morning briefing now says when the UV will be high (6 or more). *Checked:* the longest day in Paris (sunrise 05:47,
  sunset 21:58, 64.6° at 13:52, within 3 minutes), the midnight Sun at Tromsø, the scores, the nights in words, the UV peak, by unit
  tests; on the emulator, today's Sun (matching Open-Meteo's sunrise and sunset) and the three nights' chart.
- **The sky's calendar, and a sharper Moon** (since 0.9.54; `sky_events`, `space/SkyEvents.kt`). What is coming in the sky, seen from
  where the user is: the lunar eclipses (at each full moon, the Moon's distance from the centre of the Earth's shadow against the umbra
  and penumbra, Meeus's radii: total, partial or penumbral, how much of the Moon is in the shadow, whether the Moon is up here), the
  solar eclipses as seen from the place (the Moon's and the Sun's discs from there, minute by minute around each new moon: start,
  middle, end while the Sun is up, the part of the Sun hidden), the meteor showers (peak night, rate, radiant, how much the Moon spoils
  them), the Moon near a planet (told when under a degree) or two planets together, the full moons and the "super" ones. `list` (over
  120 days by default, or `days`; `kind` to keep one sort), the fifteen most remarkable in their order; `alert_on`: a notification
  the day before (from 5 p.m.) and the day itself. The Moon is now computed with Meeus's lunar theory (ELP-2000/82, 60 terms in longitude
  and distance, 60 in latitude, about 10") instead of the Almanac's short series (0.3°): the sky chart and the camera view gain from it.
  *Checked:* Meeus's example 47.a to 0.00002°; the Moon within 0.05° and 50 km of JPL; all the lunar eclipses of 2025–2029 with their
  kind; the solar eclipse of 12 August 2026 from Paris against the US Naval Observatory (start and maximum within 4 minutes, 92 %
  hidden) and total from Burgos; Venus and Jupiter on 12 August 2025; on the emulator, 400 days from Paris (the partial solar eclipse
  of 2 August 2027, 51 %).
- **Earthquakes** (since 0.9.53; `earthquakes`, `space/Quakes.kt`). From the USGS feeds (the whole world: magnitude 2.5 and more over
  the last day, 4.5 and more over the week): `recent` tells the day's strongest and the one nearest the user, in French ("séisme de
  magnitude 5,3 (modéré) à 76 km au nord-est de Tadine", its depth, how long ago, a tsunami alert, USGS's damage alert), and shows them on
  the world map, a circle by magnitude, red within 6 hours, orange within the day, yellow in the week. `alert_on` (a magnitude, 4 by
  default) watches every 30 minutes for a quake felt near the user or near the places added with `watch_add` ("Tokyo" for "ma sœur"):
  the reach grows with the magnitude (about 100 km at 4, 250 at 5, 600 at 6, 1,500 at 7); each quake told once, its notification opens
  its USGS page. `watch_list`, `watch_remove`, `alert_off`. *Checked:* on the emulator, 42 quakes in a day and 123 over the week on
  the map, the strongest and the nearest told, Tokyo added and the watch set; the reading, the words and who is told by unit tests.
  *Not checked:* a real alert.
- **Air quality and pollens, further** (since 0.9.52; `air_quality` actions, `air/AirWatch.kt`). Six pollens now (alder, birch, olive,
  grasses, mugwort, ragweed), each with its level in words (faible, moyen, élevé, très élevé: grains/m³ thresholds per kind, ragweed
  high sooner), the ones under one grain left out. `forecast`: today and tomorrow at their worst between 7 h and 21 h (the European
  index and the pollens from moderate). `map`: the air around the place in place of the face, a 9 × 9 grid about every 35 km in one
  question to Open-Meteo, each cell in the European Environment Agency's colour with its index, drawn where it was asked (the model
  moves its points to its own grid). `alert_on` (a threshold, 60 by default, and the pollens that bother the user) / `alert_off`: a
  look every 2 hours in the daytime, one notification a day for the same trouble. *Checked:* on the emulator over Paris, now (index 32,
  mugwort low), the two days' forecast, the map (25 to 37 around Paris), the watch set; the levels, the forecast's words, the alert's
  words and the grid by unit tests.
- **A fuller morning briefing, in the car too** (since 0.9.51; `wake_briefing`, `wakeup/BriefingExtras.kt`). The briefing said when
  the morning alarm is stopped now also tells, each in one sentence or not at all: rain coming within two hours where the user is (and
  the rain radar is shown in place of the face), the user's flights of the day (found in the mails), the important mails not read since
  yesterday (who and what, three at most), the ISS crossing the sky tonight when it can be seen, the rocket launches of the day with a
  set time, an aurora possible tonight (NOAA's forecast Kp against the Kp needed there). The new parts are looked for side by side.
  `set` takes `skip` and `include` ("sans les mails", "remets la météo") and `car` (on/off): the briefing said aloud when the phone
  joins the car in the morning (5 h – 11 h, once a day), through Android Auto connecting to Jarvis's media service or the car's
  Bluetooth. `status` says what it holds. *Checked:* on the emulator, the briefing now (weather, a budget, the ISS at 18h35 tonight),
  parts taken out and put back; the sentences by unit tests. *Not checked:* in a real car (on the emulator it was past 11 h).
- **The weather along a drive** (since 0.9.50; `route_weather`, `driving/RouteWeather.kt`). "Quel temps sur la route de Lyon ?",
  "la météo pour aller à Bordeaux à 18h": the route by OSRM (OpenStreetMap's router) from where the user is (or `from`) to `to`, a
  point every 40 km or so (15 at most) with the time the car passes it (from the departure asked for: "18h30", "dans 2 h", now), and
  Open-Meteo's hourly forecast for all the points in one question, each read at its own hour. What matters to a driver is told: storm,
  hail, freezing rain, snow, black ice (at 1 °C or less with rain, snow or fog), fog, heavy rain, rain, showers, gusts from 70 km/h,
  poor visibility; neighbouring points with the same trouble said once, each with its nearest town and time; the answer is short to
  be said aloud (in the car and through Android Auto). The route is drawn on the world map, each point green (nothing), yellow (rain)
  or pink (danger), with a card listing the troubles by time. *Checked:* on the emulator, Paris → Lyon (465 km, 4 h 57, dry, 25 to
  27 °C), Brussels and Amsterdam, the route drawn with its points; the hazards, the points and times, the reading and the words by
  unit tests. *Not checked:* a drive with real bad weather.
- **My flights from my mails** (since 0.9.49; `my_flights`, `space/FlightMail.kt`). `scan` reads the travel mails of the last four
  months in Gmail (bookings, boarding passes, e-tickets; not the promotions) and keeps the flights to come: a flight number written near
  a travel word (vol, flight, Flug…; not a gate, a seat or a terminal), the date nearest to it (15/10/2026, 2026-10-15, 15 octobre, Oct 3,
  a date without its year taken as the next one), the time written after it (the time of departure, local to the airport), and the gate
  and terminal when the mail gives them (a boarding pass does; several mails for one flight add up). Each is checked against adsbdb (a
  real route, its airports), the airport's time zone comes from Open-Meteo. On the day, a notification 3 hours before (6 a.m. when the
  time is unknown), then the flight is followed: take-off told with its delay against the time planned (seen in the air, less the usual
  taxiing), then the landing. `auto_on` looks twice a day; `list`, `forget`. There is no free source for gates or delays announced by
  airlines: the gate is the one in the mail, the delay the one seen. *Checked:* the reading on French, English and low-cost mails, the
  dates without a year, what is not a flight, by unit tests; on the emulator (a debug-only shortcut, Gmail not being connected there),
  the day's notification with its terminal and gate, then a real easyJet flight followed and its take-off told. *Not checked:* a scan
  of a real mailbox.
- **Northern lights and space weather** (since 0.9.48; `aurora`, `space/Aurora.kt`). From NOAA's Space Weather Prediction Center:
  `now` tells whether an aurora can be seen from where the user is: the planetary Kp index (every minute) and the storm scale (G1 to
  G5), the Kp needed there (from the geomagnetic latitude, about 6 in Paris), the chance from the OVATION model's map (every degree of
  the Earth, for the next hour or so; overhead, or low on the horizon towards the pole within 1,000 km, counted less the further it is),
  whether it is dark and how cloudy it is (Open-Meteo); the aurora oval is shown on the world map in green by its chance, with the night.
  `forecast` gives NOAA's Kp for three days, night by night (dark hours only). `alert_on` (a threshold in %, 10 by default) starts a
  watch every 30 minutes: only at night, the small Kp file first and the map only when Kp is near what is needed, one notification a
  night (again only if the chance rises much). The OVATION map (920 KB, compressed on the way) is read number by number: a regular
  expression searched from an index costs the whole text each time on Android, which never finished. *Checked:* on the emulator over
  Paris, Kp 1.0, 0 %, too light, 27 % of clouds, the oval over the far north, the forecast for three nights, the watch running once;
  the reading, the geomagnetic latitudes and the chance from a place by unit tests. *Not checked:* a real storm.
- **Rocket launches** (since 0.9.47; `rocket_launches`, `space/Launches.kt`, `LaunchesView.kt`). The next launches in the world from
  The Space Devs' Launch Library 2 (free; 15 questions an hour without a key, so the list is kept for half an hour), shown in place of
  the face: the next one with its picture and a countdown to the second (T-2 j 03:24:37), its rocket, company, pad, time as precisely as
  it is known ("le lundi 5 octobre (heure pas encore fixée)"), status (confirmé, à confirmer, suspendu…), weather odds and orbit; the
  following ones below with their countdown; touching one with a YouTube webcast plays it in the player. `detail` tells one ("Crew-13",
  "Starship", "Ariane"), `watch` plays its webcast, `alert` sends a notification 30 minutes before (or as asked) that opens the webcast;
  when the alarm rings the launch is asked for again, and a launch put back is told and followed to its new time. Kept across a reboot.
  *Checked:* on the emulator, the list and countdown, the alert for Crew-13 set as an exact alarm 30 minutes before; the reading, the
  countdowns and the dates by unit tests. *Not checked:* a real alert ringing before a launch.
- **Constellations and a guide in the camera view** (since 0.9.46; `sky_view` show `ar` with `name`). The camera view now draws the
  figures of the best-known constellations with their French names, and "où est Jupiter ?" (the Moon, a planet, a bright star, the ISS,
  a flight by its callsign) puts a pink arrow at the edge towards it and says the way in words: "tournez à gauche de 119°, levez le
  téléphone de 45°" (the shorter way round), then circles it once it is in view; below the horizon it says the Earth hides it.
  *Checked:* on the emulator over Paris, Cassiopée, Céphée and Persée drawn, the arrow and the words towards Jupiter; the guide and the
  names by unit tests.
- **The sky through the camera** (since 0.9.45; `sky_view` show `ar`, `space/ArSkyView.kt`). In place of the face (full screen
  advised), the back camera's picture with the names of what is there: the Sun, the Moon and the planets (marked "sous l'horizon" when
  the Earth is in the way), the stars down to magnitude 4 (the brightest named), the ISS, Tiangong, Hubble and the bright satellites,
  the aircraft within 40 km; the horizon and the cardinal points. The phone's rotation vector sensor gives where it points (the magnetic
  north turned to the true one with the declination of the place), the camera's focal length and sensor size (Camera2) how wide it sees;
  names never overlap (the Sun, the Moon, the planets, the stations and the aircraft first). What is in the middle is told in detail
  (distance, height, direction; for a satellite its altitude and whether it is lit; for an aircraft its altitude and speed); a hint to
  calibrate the compass when it is imprecise. *Checked:* on the emulator (its virtual room as the camera), the picture upright and not
  stretched, the horizon and north where the sensor says, the aircraft over Paris named without overlap; the projection by unit tests
  (ahead in the middle, higher up, east to the right, the declination, nothing behind). *Not checked:* a real phone outdoors at night.
- **World map, followed flights, rain radar** (since 0.9.44; `ui/WorldMapView.kt`, `location/MapData.kt`, `Flights.kt`). In place of the face,
  a world map drawn from Natural Earth (land, borders, 923 cities shown as one zooms in; pinch and drag):
  - `sky_view` show `map`: the ISS and Tiangong where they are, their ground track (the last 45 minutes and the next orbit, cut at the
    date line), the night side of the Earth and the point under the Sun; a card with the ISS's position, altitude, speed and nearest city.
  - `flight` (follow / status / stop): "suis le vol AF1234" (IATA or ICAO number). The route and airports from adsbdb, the aircraft live
    from adsb.lol (every 15 s; its trail, its heading), the great circle to its destination, height, speed, climbing or descending, the
    estimated arrival (distance left at the ground speed plus a quarter of an hour) and the nearest city. A watch looks every 5 minutes
    and sends a notification when it has landed (on the ground near its destination, or gone from view coming down close to it; a flight
    lost in cruise over an ocean is not taken for a landing). Callsigns sent with a padded number ("AMX45" as "AMX045") are found.
  - `rain_radar`: the last two hours of rain around the user from RainViewer (zoom 7 tiles, the most they give), looped over the map,
    with the time of each picture.
  *Checked:* on the emulator over Paris: the ISS over Libya matching its computed position, the night band; BA468 London → Venice
  climbing out of Heathrow, its ETA, the landing watch set (alarm 5 minutes later); the radar over northern France. *Not checked:* a
  landing notification from a real arrival.
- **Satellite pass alerts** (since 0.9.43; `satellites` alert, `space/PassAlerts.kt`). "Préviens-moi quand l'ISS passe" (or Tiangong,
  Hubble): an alarm 5 minutes before its next pass that can be seen (lit by the Sun in a dark sky, 10° high or more) over the place the
  user was, then a notification saying where it appears, how high it goes, where it disappears and for how long, and the same words aloud
  with `voice`. With `repeat`, every visible pass: the next one is set when one is told, and when none comes within 5 days (they come in
  periods of a week or two) it looks again every 2 days. `alert_show`, `alert_off`; kept across a reboot. *Checked:* on the emulator over
  Paris, the alert set (exact alarm at 18:30:19 for a pass at 18:35:19), rung at once through a debug-only shortcut (the notification's
  text), the next looked for (none within 5 days: a new look set 48 hours later), switched off. *Not checked:* a real pass outdoors.
- **The Moon, the planets and the stars** (since 0.9.42; `night_sky`, `sky_view` show stars, `space/Astro.kt`, `space/NightSky.kt`). The sky
  chart now also draws the stars down to magnitude 4.5 (and fainter ones inside a figure), 17 constellations' figures (Grande Ourse, Orion,
  Cassiopée, Cygne, Lyre, Lion, Scorpion…), the names of the brightest stars, the planets in their colours, the Moon with its phase lit on
  the side of the Sun, and the Sun in the day; a touch on one gives what it is, where, its distance (and how long its light took), its
  brightness, its phase, and its rising and setting. "Qu'est-ce qu'on voit ce soir ?" (`now`: the Moon, its phase, when it rises or sets, the
  planets up with their direction and height, when the others rise, the brightest stars) and "c'est quoi le point brillant au sud ?"
  (`identify`: the brightest things within 25° of the direction and height said). Computed on the phone: the planets from JPL's
  Keplerian elements and rates (Standish, 1800-2050), the Moon from the Astronomical Almanac's short series, the stars from the Yale
  Bright Star Catalog (`assets/sky/stars.tsv`, 1 630 stars, credits in `assets/sky/NOTICE.txt`), all brought to the equinox of date by the
  general precession, then into the sky of the place. *Checked:* `AstroTest` against JPL Horizons for 2026-09-29 00:00 UTC (the seven
  planets within 0.25°, the Moon within 0.5°, the Sun within 0.1°, the Moon's distance within 3 000 km, its phase "gibbeuse décroissante"),
  the stars read and named (Alp1Cen and Mu 1Sco included), the Pole Star at the latitude's height due north from Paris, every star of the
  figures in the catalogue; on the emulator over Paris, the chart with the constellations and planets, and both spoken answers.
- **The live sky in place of the avatar** (since 0.9.41; `sky_view`, `space/SkyView.kt`, `space/SkyData.kt`). "Montre-moi les satellites /
  les avions au-dessus de moi", "affiche le passage de l'ISS": in the video panel (its close, full screen and video window), a chart of the
  sky above the user (the horizon round, 30° and 60° rings, the zenith in the middle, north up) with the brightest satellites (yellow when
  seen by eye now, light blue when lit, dark blue in the Earth's shadow), the Starlink (small grey dots) and the aircraft (orange arrows
  pointing where they fly; pink for a distress transponder code), each where it really is, moving every second (the aircraft every 10 s);
  under it (beside it when wide), how many of each and whether the sky is dark, then the list, each with its height, direction and
  distance. A touch on the chart or the list chooses one: a satellite shows where it is in the sky, its distance, altitude, speed, the
  point of Earth under it, light or shadow, whether it can be seen now, its orbit (period, inclination), its catalogue number, its path
  in the sky from 5 minutes ago to 15 minutes ahead (pink) and its next pass (dashed yellow, with the countdown); an aircraft shows its
  airline and route (origin and destination cities and airports), model and category, registration, owner, a photo, altitude (metres
  and feet), speed (km/h and knots), heading, climb or descent, where it is in the sky, its transponder code and address. Aircraft come from
  adsb.lol (live, free, no key; within 40 nautical miles), their route and model from adsbdb (asked only for the one chosen, kept for the
  session); satellites as for `satellites`. Objects under the horizon are listed, not drawn. The assistant can describe what is shown
  (`look`). *Checked:* `SkyDataTest` (aircraft read with their units, on the ground and without a position left out, the distress codes,
  a route and a model read, a close aircraft high and a far one low, the short names); on the emulator over Paris: 68 aircraft, 462
  Starlink and 7 bright satellites drawn; Air France AFR030 chosen (Boeing 777-328ER, F-GZNA, Air France, 5 996 m, 745 km/h, climbing
  12 m/s, 33° high to the west); the ISS's pass drawn with its card; full screen with the chart and the list side by side. *Not checked:*
  on a real phone outdoors, and a route and a photo on the same aircraft (the one tried had neither in adsbdb).
- **Satellites and the ISS** (since 0.9.40; `satellites`, `space/`). "Quels satellites au-dessus de moi ?": how many of the brightest
  satellites (CelesTrak's "visual" list and the space stations) are above the horizon now, and how many Starlink; the highest ones with
  their height, direction and distance, and whether one can see them now (lit by the Sun while the sky is dark). "Quand passe l'ISS ?"
  (or Tiangong, Hubble): its passes over the next three days, 10° high or more, with the time, from where to where, how high and how
  long, and whether it can be seen, or why not (daylight, the Earth's shadow). "Où est l'ISS ?": the point of Earth it is above, its
  height and speed. The public orbits (two-line element sets) come from CelesTrak, no key, kept 12 hours on the phone as CelesTrak asks
  (an older copy when offline); the positions are computed on the phone with SGP4 (`space/Sgp4.kt`, the near-Earth model the orbits are
  made for, after Vallado et al. 2006; since 0.9.63 with its deep-space part too, `space/Sdp4.kt`, see below), then turned into the sky of the place (Greenwich sidereal time,
  WGS-84), the Sun's position giving light and shadow. The phone's approximate position is used for that one question. *Checked:*
  `Sgp4Test` (Vallado's reference positions of satellite 00005 at 0 and 360 minutes, to the metre; an element set read; straight up is
  90°; the Sun at solar noon and midnight in Paris; shadow; the ISS's passes over Paris in order, minutes long, 90 minutes apart; the
  directions in French); on the emulator, the ISS's computed position against wheretheiss.at at the same moment (-27.5° / 147.1°, 433 km,
  27 544 km/h against -27.8° / 146.9°, 431 km, 27 544 km/h), the satellites above Paris, the passes of the ISS and Tiangong. *Not
  checked:* the Starlink count (CelesTrak refused a second download from the same address within its two hours), on a real phone.
- **Far satellites: GPS, Galileo, geostationary** (since 0.9.63; `space/Sdp4.kt`). Orbits of 225 minutes or more (12 h navigation
  satellites, 24 h geostationary ones, Molniya) are now placed with SGP4's deep-space terms (once called SDP4): the Moon's and the Sun's
  pulls and the 12 h and 24 h resonances with the Earth's gravity (Vallado et al. 2006, "improved" mode). Before, they were left out of the
  sky. "Où est Meteosat ?", "Quand passe un GPS ?": when the name is not among the bright satellites, CelesTrak's `gnss` and `geo`
  lists are looked in too (kept 12 hours like the others). A satellite that never rises or never sets is said so: a geostationary one
  "stays almost still in the sky, at 35° towards the south", "never rises" when it is under the horizon. *Checked:* `Sdp4Test`, Vallado's
  reference positions and velocities (to the metre and the mm/s) of a GPS satellite (28129), a geostationary one (28626), a Molniya
  (08195, the half-day resonance) and a low-inclination one propagated backwards (04632); run against his whole verification file
  (`tcppver.out`), every satellite agrees within 4 cm. A geostationary satellite stays overhead all day for a place under it, a GPS one
  crosses the sky. *Not checked:* the `gnss` and `geo` downloads from CelesTrak (not reachable from where this was written), on a real phone.
- **The app's name** is "Jarvis" (it was "Jarvis Dev") since 0.9.39.
- **Jarvis in Android Auto, sound off the screen** (since 0.9.38; `car/`, `car/AudioPlayer.kt`). Sound alone (a radio, a podcast, the
  news) no longer plays inside the app's screen but in `AudioPlayer`, run by a media service (`JarvisMediaService`, in the foreground
  with the player's notification while it plays): it goes on with the screen off, the app closed (swiped away from the recent apps) and the
  phone locked in the car. The same service is a media browser, so Jarvis is a media app for Android Auto (`automotive_app_desc.xml`): the
  car shows *Infos* (the freshest bulletin, or one source), *Radios* (the ones played lately, then 18 well-known French stations), *Mes
  podcasts* (the latest episode of each one followed) and *Reprendre* (episodes left on the way; never a video), and its screen, its
  steering wheel's buttons and "Ok Google, joue FIP sur Jarvis" (a search: a station of that name, else a podcast; nothing: the news) all go
  through the panel's media session, as the phone's own controls do. The phone's media controls get a « play again » after a restart (the
  last station or episode). Only the car, the system and Google's assistant may browse the list (it holds the podcasts followed). An app
  not installed from the Play Store is hidden by Android Auto unless *Unknown sources* is on in its developer settings. *Checked:*
  `CarLibraryTest` (the first page, the radios, podcasts and episodes, never a video); on the emulator (which has Android Auto but no car
  screen) a browser connected like a car, read the list and played "radio:FIP" (the service in the foreground with its notification), the
  radio went on with the app swiped away, and pause, play and stop from outside worked. *Not checked:* in a real car or Google's car-screen
  emulator (not installed).
- **Zooming into photos** (since 0.9.38). In a slideshow: pinch, double tap (zoom ×2.5 on that point; again to let go), or by voice
  (`play_video` zoom: "zoome", "zoome en haut à gauche", "encore", "dézoome"); the pan never shows past the photo, and the slideshow waits
  while a photo is zoomed. *Checked:* `CarLibraryTest` (the point under the fingers stays in place, the pan's limits, the words); on the
  emulator zoom ×3 to the top left by voice (the slideshow waiting), a double tap in and out.
- **Talking to Jarvis over the radio or a video** (fixed in 0.9.37, reported on a phone and in the car with Android Auto: once the radio was
  on, Jarvis no longer answered). While a sound plays, the microphone only reaches the assistant when the floor is open, so that it does
  not answer the radio; but a session woken over the sound (by the wake word, the button, the assistant gesture) started with the floor
  shut, so the request that followed was never heard, and a paused radio kept the microphone shut. Now: a session that starts while a
  sound plays opens the floor at once (the sound turned down); a paused sound gives the microphone back; the assistant button (a
  steering wheel's, a long press) during a session over a sound opens the floor; and what is said to the user names the real wake word
  (« Hey Jarvis » by default, not « Jarvis »), or says to tap the small face or pause when the offline wake word is not installed. The
  video's header shows it: "🎤 « Hey Jarvis »". *Checked:* `VideoPanelTest` (a pause gives the microphone back, no floor to open while
  paused, the words with and without a wake word); on the emulator the header with and without the pause. *Not checked:* in a car
  (Android Auto sends its steering wheel's voice button to Google's assistant, and its media buttons to the media apps it knows, which
  Jarvis is not yet).
- **Waking up to the radio, falling asleep to it** (since 0.9.36; `radio_alarm`, `radio` sleep; `wakeup/RadioAlarm.kt`). "Réveille-moi avec
  FIP à 7 h", "en semaine à 6 h 45 avec France Inter": the station is found once when set; at the time, a foreground service plays it at the
  alarm's volume (the alarm usage), from 12 % to full over a minute, with « Arrêter » and « 10 min de plus » in its notification, and stops by
  itself after an hour. It is set as an alarm clock (shown by the phone, it rings in Doze, and Android lets a service start from it), so the
  wake briefing, which watches the phone's next alarm and speaks once an alarm stops sounding, follows it by itself. A stream that fails is
  looked for again by the station's name, then the phone's own alarm sound rings: an alarm must. One radio alarm at a time; kept across a
  reboot. Without the "Alarms and reminders" permission it is only approximate and the tool says how to allow it. *Sleep:* "endors-moi",
  "mets la pluie pour dormir": rain, nature sounds or calm music (three streams chosen and tried by hand) or a station, a timer (30 min by
  default), the app's screen as dim as it goes and no longer kept on, the sound fading out over the last three minutes. The radio's genre
  search now asks for the exact tag ("rain" had found "Bahrain"). *Checked:* `PodcastFeedTest` (the next ring once, later today, on its
  days; the days in words); on the emulator an alarm two minutes ahead, the app in the background: the service started at the second as an
  alarm clock, playing as an alarm, its notification with the station, « 10 min de plus » (stopped, set again ten minutes on); an earlier
  run showed that a one-time alarm forgot its station before the service read it (it rang the phone's sound): fixed, the station now goes
  with the ring, and a rung alarm is kept, inactive, for the snooze. Sleep: the window brightness at 0.01, no keep-screen-on, and after 5
  minutes the player, the dimming and the notification gone. *Not checked:* on a real phone locked overnight, the briefing after it.
- **Podcasts and the news** (since 0.9.36; `podcast`, `podcasts/`). "Mets le dernier épisode de Secrets d'Histoire": the podcast is found in
  Apple's directory, its RSS feed read on the phone (up to 3 MB, by a tolerant reader: a feed cut at the limit still gives its first episodes;
  https audio only) and the episode plays in the video player with its sound, its card showing the title and the podcast, where it was left
  if it was (as a video). "episodes" lists the latest ones; "subscribe" / "unsubscribe" / "subscriptions" keep the ones the user follows (on
  the phone), and "new" says what came out since last asked. *The news* ("mets les infos", "le journal de France Inter"): the freshest
  bulletin among France Inter's (6 h, 19 h), France Culture's (7 h, 8 h, 12 h 30, 18 h, 22 h), Europe 1's (every hour) and RFI's feeds (Radio
  France's are not in Apple's directory: their feed numbers were found by hand). *Checked:* `PodcastFeedTest` (title, author, CDATA and
  entities, http left out, a cut feed, dates and lengths, the freshest, the source from the words, https feeds only); on the emulator the
  news (Europe 1's 22 h bulletin), a podcast's episodes and one playing, then started again at 34 s after being stopped.
- **Plugins by voice and from a link** (since 0.9.36; `plugin_manage`). "Crée un plugin qui me dit l'âge moyen des Kevin": the assistant
  writes the plugin file itself (the tool's description carries the format), with parameters to try it; it is checked like an imported file,
  an http one is run for real (a link or a routine is only shown: it would act at once), and the user sees its summary and the test's answer
  and confirms before it is installed (whatever the confirmation setting); a failed test installs nothing and says what to fix. "Installe le
  plugin de ce lien": an https link (a GitHub page gives its raw file; the local network is refused), at most 20 000 characters, checked,
  confirmed, installed; also in Settings > Plugins, with a link field. "list" and "remove" (confirmed). *Checked:* `PodcastFeedTest` (the link
  rules); on the emulator a plugin created (agify.io: Kevin, 34 years, 52 709 people), confirmed and installed, one imported from this
  repository's GitHub page, and removed.
- **Newsletters and spam** (since 0.9.34; `mail_cleanup`, Google connected). "Qui m'envoie le plus de mails ?", "désabonne-moi de
  Zalando", "nettoie mon spam". *subscriptions:* the list senders of the last 60 days (the 120 newest mails in the promotions, updates,
  social and forums tabs, or saying unsubscribe / désabonner / newsletter), grouped by address, the busiest first, with how to leave
  each. *unsubscribe:* the way the sender offers in its List-Unsubscribe header (`google/Unsubscribe.kt`): RFC 8058's one click (one
  POST, https only, no cookies, no redirect followed), else a mail sent from the user's Gmail (the `gmail.send` scope already asked
  for), else its page opened in the browser for the user to finish (Jarvis never clicks there). *Never for real spam:* answering spam
  tells its sender the address is read, and its links can be traps; so a sender Gmail put in the spam, or whose mail does not prove
  it comes from its domain (Gmail's own Authentication-Results: DMARC passed, or a DKIM signature of that domain), is refused, and
  report_spam / trash / block are offered. *spam:* the senders in the spam folder. *report_spam* (its mails to the spam, as Gmail's
  button), *trash* (to the bin: 30 days to take them back), *block* (a Gmail filter sends its next mails to the bin) need two more
  scopes (`gmail.modify`, `gmail.settings.basic`), asked for only with Settings > Google > « Autoriser le tri des mails »: added to the
  scopes asked at connection, they would have made every connected account look disconnected. Nothing is ever deleted for good.
  Every action is confirmed on screen, whatever the confirmation setting (it acts on the mail and speaks for the user). If Google
  refuses the new scopes, add them to the consent screen's "Data access" in Google Cloud. *Checked:* `UnsubscribeTest` (one click
  only with its header and https, http / credentials / broken addresses left out, mailto decoded, Gmail's DMARC or an aligned DKIM
  and not a header the sender wrote, senders grouped and marked); on the emulator the tool's answers without an account and with a
  wrong address. *Not checked:* against a real Gmail account (none on the emulator).
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

What it understands is a fixed list of French commands, matched by rules (`offline/OfflineIntents.kt`, covered by unit tests) and run through the existing tools, with their own safeguards (the volume still asks for confirmation):

- **Phone**: open an app, call a contact or draft an SMS (never sent), volume, brightness, flashlight, settings pages, lock the screen, screenshot, battery, "où es-tu ?" (ring the phone).
- **Time**: time, date, timer; music (pause, play, next, previous).
- **Everyday**: lists ("ajoute du lait à ma liste"…), spending ("j'ai dépensé 12 € en courses", "combien j'ai dépensé ce mois-ci ?", "annule la dernière dépense"), "scanne ce ticket", medications taken, "retiens où je me suis garé" / "où est ma voiture ?".
- **Calls and people**: "qui m'a appelé ?", "j'ai des appels manqués ?", "mes derniers appels", "qu'est-ce que j'ai raté ?".
- **Quiet time**: "je suis en réunion jusqu'à 15 h", "ne me dérange pas pendant une heure", "j'ai fini ma réunion".
- **Money, parcels, trains**: "mes abonnements", "où en est mon budget ?", "où en est mon colis ?", "quel est le prochain train pour Rennes ?".
- **Camera**: "qu'est-ce qui est écrit là ?", "lis-moi cette notice" (the text is read on the phone and said as it is).
- **Cooking**: "ma recette de crêpes", then, while a recipe is under way, "étape suivante", "répète", "l'étape d'avant", "étape 3", "les ingrédients", "pas de minuteur" / "remets les minuteurs", "fin de la recette" ("suivant" is the next step only while cooking; otherwise it is the next song).
- **Photos and health**: "mes photos d'aujourd'hui / d'hier", "mes dernières photos", "combien de pas aujourd'hui ?", "comment j'ai dormi ?".
- **Driving and safety**: "mode conduite" / "je prends la route", "arrête le mode conduite" / "je suis arrivé"; "au secours", "SOS", "à l'aide" (the SOS countdown), and "annule" / "fausse alerte" to stop it.
- "aide", "au revoir".

Time and date are worked out on the phone; the tools that need the internet (the rain, the place of a photo search) say so. Anything else goes to the local model if one is installed (below), or is answered with "Je n'ai pas compris" and a pointer to "aide". It ends after three silences.

It needs the French offline language pack (Google speech services: offline speech recognition) and a French voice; the errors say so when they are missing. The spoken answers are French only. Verified on the emulator (commands through the debug receiver `DEBUG_OFFLINE`, and the automatic switch without network, which stops with the message about the missing language pack, as that emulator has none); **not** verified with real offline speech recognition on a phone.

#### A local model for what is not a fixed command

Settings > "IA locale (hors ligne)". Anything that is not one of the fixed commands above used to always get "Je n'ai pas compris" — now, if the user has installed a local model, that sentence is what is said instead
of a real answer only when there is none. With one installed, the phone runs a small Gemma model itself (Google's [MediaPipe LLM Inference API](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android),
`com.google.mediapipe:tasks-genai`) to answer in French, entirely on the device — nothing about the question or the answer is sent anywhere. It only talks: it cannot open an app, place a call, change a setting or
search the web, and the system prompt given to the model (`offline/LocalPrompt.kt`) tells it so, so it says it cannot rather than pretending it did it.

**Getting the model.** Gemma's own weights are gated by a Google licence on Hugging Face, so the app cannot fetch them the automatic way it fetches the (open) wake-word models. On a computer or the phone's own browser:
open [huggingface.co/litert-community/Gemma3-1B-IT](https://huggingface.co/litert-community/Gemma3-1B-IT), log in, accept the Gemma licence, and download `gemma3-1b-it-int4.task` (≈ 530 MB). Then, in Jarvis, Settings >
"IA locale (hors ligne)" > "Importer le modèle (.task)", and pick that file — the same way a custom wake-word model, trained outside the app, is imported. The file is copied into the app's own storage (checked first:
a plausible size and the zip header every MediaPipe `.task` bundle has); nothing is downloaded by Jarvis itself.

**How it is used.** `offline/LocalModelStore.kt` holds the imported file; `offline/LocalLlm.kt` wraps MediaPipe's `LlmInference`, loaded only the first time an offline question actually needs it (not at the start of
every offline session) and unloaded when the offline session ends, to give its memory back. `offline/LocalPrompt.kt` builds the one block of text sent to the model: a short instruction, the last three exchanges of the
*offline* session only (never the online conversation, never the long-term memory, never a tool's answer), and the new question — plain labelled turns ("Utilisateur : … / Jarvis : …"), not the model's own special
tokens, since I could not confirm against the real weights whether the `.task` bundle already wraps a query in its expected chat template. A reply is capped at 512 tokens and 60 seconds; past that, or on any error
(model too big for the phone's memory, a corrupt file, the API failing to initialise), Jarvis says it could not think of an answer rather than staying silent or crashing. A switch next to "Modèle installé" turns this off
without removing the file, and "Supprimer le modèle" erases it.

**What I could and could not check.** The `tasks-genai` dependency resolves and the app builds and runs with it (its native library for the LLM engine loads correctly on the emulator, confirmed in logcat). The whole
pipeline — detecting an unrecognised sentence, finding the model installed, trying to load it, and answering gracefully on failure — was exercised end to end on the emulator with a fake, correctly-shaped but not real
`.task` file: MediaPipe's own loader rejects it ("Failed to initialize"), which Jarvis catches and reports in the activity log and to the user, without crashing. The settings screen (import, the installed state and its
size, the switch, removal) was checked the same way. **What was not checked, because the model's weights are gated and I had no way to accept that licence or download them in this environment: whether a real Gemma
`.task` file loads, how long it takes, how much memory and battery it uses, and the quality of its answers.** The MediaPipe LLM Inference API is also documented as being in maintenance mode, with new work going to a
successor ("LiteRT-LM") that does not yet have as documented an Android/Kotlin surface — this is the practical, working choice today, not necessarily a permanent one.

#### Lists

Settings > "Listes", or by voice, online or offline: shopping lists, to-do lists, or any other
named list, kept on the phone (`tasks/TaskListStore.kt`, file `task_lists.json`) and shared
between the online tool (`actions/TaskListTool.kt`), the offline fixed commands, and the settings
screen — one item added offline shows up online and in the settings UI, and the other way round.
Several distinct lists coexist by name ("courses", "tâches"…); without a name, a default list is
used, so "ajoute du lait" needs no list to have been named first. Matching an item back (to check
it off or remove it) is a partial, accent- and case-insensitive match on its text, and also
insensitive to which French article is used to refer to it — "coche le lait" finds an item added
as "du lait" — and prefers the shortest, most exact match, so "coche les pommes" does not
accidentally match "pommes de terre". Limits: 30 distinct lists, 300 items per list, 200
characters per item.

Offline (`offline/OfflineIntents.kt`), fixed phrasings are understood without a network: "ajoute
… à ma liste (de …)", "j'ai acheté / pris …" or "coche …", "retire … de ma liste", "montre / lis
(-moi) ma liste" or "qu'est-ce qu'il y a sur ma liste", "vide ma liste". Online, the model routes
free-form phrasing to the same `task_list` tool itself, guided by a short description of it added
to the system prompt. Checked with unit tests for both the store's matching rules and the offline
regex dispatch, and manually on the emulator for the settings screen (add, check, uncheck, remove,
clear-checked) and for offline voice-style commands through the debug receiver, including the
French-article case above. Not checked with real offline speech recognition on a phone.

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

#### Hologram expressions

Moods and expressions (blink, brow lift and position, gaze, mouth shape) were already fully driven by the shared animation (`HoloAvatar.kt`), the
same as the plain skins — but on the hologram they were drawn in a pale cyan or in the app's own theme colour, which read poorly against the
skin and the gold circuits: a lowered brow or a closed eye was there in the geometry but hard to see at a glance. Brows, lashes, the eyelid
crease and the lip line now use the hologram's own dark ink (`DEEP_BLUE`, the same tone as the blue skin's contour) instead, which reads clearly
against every hologram skin and against the gold circuits regardless of the interface's theme colour. Checked on the emulator across the four
moods and while speaking.

#### Teeth, and a rounder open mouth

Teeth were one flat, uniform slab (the upper row only — `TL`/`TLb`, the lower row's vertices, were computed and never turned into faces).
`tools/avatar/export_head.py` now builds 8 upper and 8 lower teeth, each a separate block with a small gap to its neighbour (no geometry
fills the gap, so the dark cavity behind shows through and reads as the line between two teeth), a shade of white that varies a little from
one tooth to the next and dulls slightly towards the corners. The lower row is jaw-weighted so it opens with the mouth, the upper row is
fixed to the skull. Regenerated for all three heads (Classique, Léa, Marc), since the mouth is shared before each is warped into its own face.

Opening the mouth used to rotate every jaw-weighted vertex by the same angle, so a wide-open mouth read as a rectangle with sharp corners.
`HoloAvatar.kt` now weighs that rotation by how far a vertex sits from the middle of the mouth (`cornerFactor`, computed once from the rest
pose's own lower-lip width, applied at render time rather than baked into the mesh): full at the centre, fading out over the outer part
towards each corner — real corners barely move, which is what gives an open mouth its rounded, almond shape. A first attempt baked this
into the exported mesh instead and left a visible tear at the corner, where a masked-out vertex kept swinging the old, larger angle next to
its now-tempered neighbours; doing it at render time reaches every jaw vertex through the same formula, with no such seam. Checked on the
emulator (a debug `--es mouth` override drives the jaw open directly, since the debug synthetic voice's amplitude was too flat to hold the
mouth open on its own) across all three heads and the hologram looks, and by a unit test that the middle of the mouth drops clearly more
than a corner.

#### A head that also tilts, not just turns and nods

The idle sway (`HoloAvatar.kt`) only ever turned the head side to side (yaw) and nodded it (pitch); a head that never
tilts reads as a camera on a gimbal rather than something alive. Added a third idle rotation, roll, on its own slow,
irregular rhythm (a different frequency and phase from yaw and pitch, so the three never lock into a visibly
repeating combination), composed last in the pose matrix — a plain 2D turn of the already-posed head around the
axis pointing out of the screen, leaving depth alone. Kept small (a few degrees at most): a head that visibly tips
over looks drunk, not alive. `DebugAvatarReceiver`'s existing `--es yaw`/`--es pitch` overrides gained a matching
`--es roll` (radians; `off` releases it). Checked with unit tests (idle roll stays under 0.08 rad over a 20 s
simulated run; an override pins it exactly; a side vertex's posed position changes once rolled) and visually on the
emulator, forcing a large roll (0.35 rad) to confirm the tilt is geometrically clean — no tearing or distortion,
the cap tilts with the head — then releasing the override to see the idle version.

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
  through the accessibility service. Telegram stays a draft.
- **Messenger** (since 0.9.10 — before, it only ever opened Messenger's "send to" screen with the text and left it there, which read as
  "Jarvis says it sent, nothing happens"). Messenger cannot be opened on a conversation from a phone number and knows people by their Facebook
  name, so the recipient is the name *as Messenger shows it*, not looked up in the phone's contacts ("Maman" in the phone may be "Marie
  Dupont" there). `messaging/MessageSending.kt` opens the "send to" screen with the text, types the name in its search field, and presses the
  « Envoyer » button **on that person's row** (matched by position: the button whose middle lies within the name's row) — never the first
  button on the screen, which would send to whoever Messenger lists first. Two people answering to the name are never guessed between (an
  exact full name settles it, otherwise Jarvis asks). It counts as sent only when Messenger turns that row's button into "Envoyé"/"Annuler";
  if the button was pressed but that never shows, Jarvis says it cannot confirm. If nobody matches, it says which names it saw and leaves the
  message ready for the user to send. The model is also told plainly never to say a message went unless the tool answered « Message envoyé ».
  *Checked*: unit tests of the row matching (the right row among several, a first name alone, two people of the same name, nobody, a button
  naming the person, the "sent" mark only on that row). *Not checked*: Messenger itself — it cannot be installed and signed in on the
  emulator; the screen layout it assumes (a search field, then rows of a name and an "Envoyer" button) is Messenger's current "send to"
  screen as described, and if it differs on a phone, the failure message lists what Jarvis saw on screen.
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

#### Pressing "send" reliably (0.9.11)

Asked step by step ("ouvre la conversation", "écris…", "envoie"), the last tap did not always send. Three causes, all handled in
`device/SendPress.kt`, used both by `screen_tap` on a send button in a messaging app and by the automatic sending of `send_message`:
the model's screen reading could date from before the text was typed, when Messenger's button is still the thumbs-up (it turns into
« Envoyer » only once there is text) — so the send button is looked up again on the screen as it is now, waiting up to two seconds for it
to appear; an accessibility click can be answered "done" by the app and do nothing — so a message counts as sent only once the compose
field no longer holds it (the service now tells a field holding typed text from one showing its hint), and otherwise the button is
pressed once more with a real finger tap and checked again; and when it still has not gone, Jarvis says so instead of announcing it
sent. In Messenger's "send to" list the same finger-tap retry is made when the row does not turn to « Envoyé ». *Checked*: unit tests of
the draft/field/button rules. *Not checked* on a device: the emulator was unavailable this time, and Messenger cannot run on it anyway.

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
- **Gmail and Drive** (`gmail`, `drive`, Settings > Google). Reads unread or searched mail, reads a message, and prepares **drafts** by default;
  searches and reads Drive files (Docs and Sheets exported as text, PDF and images analysed by Gemini), and uploads documents Jarvis wrote.
  Sign-in uses Google's Authorization API (Play services), so Jarvis never sees a password, with the narrowest scopes: `gmail.readonly`,
  `gmail.compose`, `gmail.send`, `drive.readonly`, `drive.file`. Mail and file contents reach the model as data, marked so that instructions inside them are not followed.
  **Real sending** (Settings > Google, *"Envoyer les mails sans confirmation"*, off by default — same shape as `send_message`, see "Sending messages
  on your word"): once on, `gmail` with `send = true` posts to `messages/send` instead of `drafts` the moment the user has just clearly asked for
  it, no further confirmation. An account connected before this setting existed only has the older, narrower scopes; reconnecting once ("Reconnecter
  Google") re-consents with `gmail.send` added, which Google's Authorization API asks for incrementally rather than replacing the whole grant.
  *Not tried:* everything that needs a signed-in Google account (the emulator has none); the tools answer "Google is not connected" without one, and
  real sending specifically could not be tried at all here.
  **Each build needs its own OAuth client:** the debug build (`com.jarvis.android.dev`, debug key) and the release build (`com.jarvis.android`,
  release key) are different apps for Google. The release key's SHA-1 is shown in Settings > Google (for the release published here it is
  `78:B0:7C:86:A6:C8:48:4E:14:3D:A2:F3:4F:EB:A2:8F:0C:09:35:32`); register it with the package `com.jarvis.android`. "Check the connection" in the same
  card says which of the two problems it is (unregistered app, or expired access).
  To connect: in a Google Cloud project, enable the Gmail API and the Google Drive API; configure the OAuth consent screen (External, in testing, with
  your Google account as a test user, and the five scopes above); create an OAuth client ID of type **Android** with the app's package name
  (`com.jarvis.android`, or `com.jarvis.android.dev` for the debug build) and the SHA-1 of the key that signs the APK
  (`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`); then Settings > Google > Connect. Google
  treats Gmail scopes as restricted: an unverified app in testing mode is limited to its test users, and the access may need to be renewed about every
  week.
- **Real calendar events** (`calendar`, Settings > Agenda, *"Créer les événements sans confirmation"*, off by default). By default `add` only opens
  Android's own "new event" form, prefilled, for the user to save; with this switch on and a clear ask, and once write access to the calendar is
  separately granted (`WRITE_CALENDAR`, asked when the switch is turned on), the event is inserted directly instead
  (`calendar/CalendarWriter.kt`). The calendar written to is chosen automatically: the account's own primary calendar (the one it owns, not a
  calendar merely shared with it) over a secondary one, a real synced account over a purely local calendar, among calendars that both accept new
  events (contributor access or better) and actually sync (so the event is not created somewhere invisible). *Checked:* unit tests for that choice,
  covering ties and the "nothing writable" case. *Not tried* on a device: an actual event landing in a real calendar app.

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
