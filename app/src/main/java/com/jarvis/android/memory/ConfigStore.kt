package com.jarvis.android.memory

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore

private val Context.dataStore by preferencesDataStore(name = "jarvis_settings")

/**
 * Opens the encrypted store, and if it cannot be decrypted (the file survived a reinstall or
 * restore but its Keystore key did not) wipes it once and opens a fresh one. The stored
 * value is unrecoverable at that point anyway; the alternative is a crash on every launch.
 */
internal fun <T> openOrReset(open: () -> T, reset: () -> Unit): T =
    try {
        open()
    } catch (e: GeneralSecurityException) {
        reset()
        open()
    } catch (e: IOException) {
        reset()
        open()
    }

/**
 * App configuration — Android port of `config/api_keys.json` + the UI's
 * live-theming / customization settings. The Gemini API key is kept in
 * `EncryptedSharedPreferences` (AES-256, Android Keystore-backed key) rather
 * than plain DataStore, since it is a credential, not a preference.
 */
class ConfigStore(private val context: Context) {

    private val keystoreKey = KeystoreKeyProvider(API_KEY_ALIAS)

    /** One encrypted file per key slot; slot 1 keeps the file name older versions used. */
    private val stores = (1..MAX_API_KEYS).map { slot ->
        SecureStore(
            dir = context.noBackupFilesDir,
            name = if (slot == 1) API_KEY_FILE else "jarvis_api_key_$slot.enc",
            keys = keystoreKey,
        )
    }
    private val store get() = stores[0]

    /** The Home Assistant Long-Lived Access Token — a credential, so encrypted like the Gemini key, not plain DataStore. */
    private val homeAssistantTokenStore = SecureStore(dir = context.noBackupFilesDir, name = "jarvis_ha_token.enc", keys = keystoreKey)

    /** The La Poste developer key for parcel tracking (Okapi) — a credential too. */
    private val laPosteKeyStore = SecureStore(dir = context.noBackupFilesDir, name = "jarvis_laposte_key.enc", keys = keystoreKey)

    /** The SNCF key (trains) and the navitia.io key (local buses and trams), for public transport — credentials too. */
    private val sncfKeyStore = SecureStore(dir = context.noBackupFilesDir, name = "jarvis_sncf_key.enc", keys = keystoreKey)
    private val navitiaKeyStore = SecureStore(dir = context.noBackupFilesDir, name = "jarvis_navitia_key.enc", keys = keystoreKey)

    /** The RTE data portal key (EcoWatt), a credential too. */
    private val rteKeyStore = SecureStore(dir = context.noBackupFilesDir, name = "jarvis_rte_key.enc", keys = keystoreKey)

    /** The Perplexity API key, for sourced web answers (perplexity/PerplexityTool.kt), a credential too. */
    private val perplexityKeyStore = SecureStore(dir = context.noBackupFilesDir, name = "jarvis_perplexity_key.enc", keys = keystoreKey)

    /** The Jarvis PC pairing (address, pinned certificate, tokens), a credential too: see pc/PcLink.kt. */
    private val pcPairingStore = SecureStore(dir = context.noBackupFilesDir, name = "jarvis_pc_pairing.enc", keys = keystoreKey)

    /** The connectors (remote MCP servers) with their tokens and logins: see connectors/ConnectorManager.kt. */
    private val connectorsStore = SecureStore(dir = context.noBackupFilesDir, name = "jarvis_connectors.enc", keys = keystoreKey)

    private var slotCache: List<String?>? = null

    @Synchronized
    private fun slotValues(): List<String?> {
        slotCache?.let { return it }
        val values = stores.map { it.read() }
        if (values.any { it != null }) slotCache = values // do not cache a momentary read failure
        return values
    }

    private val rotation = KeyRotation(::slotValues)

    init {
        migrateLegacyKey()
    }

    /**
     * Older versions kept the key in EncryptedSharedPreferences. Moves it to [store] once, and
     * only removes the old copy after the new one has been written and read back. If the old
     * file cannot be decrypted the key is unrecoverable and the old storage is wiped.
     */
    private fun migrateLegacyKey() {
        val legacyFile = File(context.applicationInfo.dataDir, "shared_prefs/$SECURE_PREFS_FILE.xml")
        if (!legacyFile.exists()) return
        if (store.read() == null) {
            val legacy = openOrReset(open = ::openLegacy, reset = ::removeLegacyStorage)
                .getString(KEY_API_KEY, null)
            if (!legacy.isNullOrBlank() && !store.write(legacy)) return // keep it, retry next launch
        }
        removeLegacyStorage()
    }

    private fun openLegacy(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun removeLegacyStorage() {
        context.deleteSharedPreferences(SECURE_PREFS_FILE)
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        } catch (_: Exception) {
            // Best effort: a stale key is replaced on the next MasterKey build anyway.
        }
    }

    /** The key to use now (the first one until it is refused, see [keyRejected]). */
    fun getApiKey(): String? = rotation.current()

    /** Tells the store that [key] was refused; returns the next configured key, if any. */
    fun keyRejected(key: String): String? = rotation.rejected(key)

    /** 1-based slot of the key currently in use. */
    fun activeKeySlot(): Int = rotation.activeSlot()

    /** Which of the [MAX_API_KEYS] slots hold a key (never the keys themselves). */
    fun keySlotsFilled(): List<Boolean> = slotValues().map { !it.isNullOrBlank() }

    fun setApiKey(value: String): Boolean = saveApiKeySync(1, value)
    fun hasApiKey(): Boolean = slotValues().any { !it.isNullOrBlank() }

    @Synchronized
    private fun saveApiKeySync(slot: Int, value: String): Boolean {
        require(slot in 1..MAX_API_KEYS)
        slotCache = null
        return stores[slot - 1].write(value)
    }

    /** Writes the key in [slot] and reports whether it really reached storage (written, then read back). */
    suspend fun saveApiKey(value: String, slot: Int = 1): Boolean =
        withContext(Dispatchers.IO) { saveApiKeySync(slot, value) }

    /** Removes every stored key and reports whether the removal really reached storage. */
    suspend fun deleteApiKey(): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@ConfigStore) {
            slotCache = null
            stores.map { it.delete() }.all { it }
        }
    }

    /** Removes only the key in [slot]. */
    suspend fun deleteApiKey(slot: Int): Boolean = withContext(Dispatchers.IO) {
        require(slot in 1..MAX_API_KEYS)
        synchronized(this@ConfigStore) {
            slotCache = null
            stores[slot - 1].delete()
        }
    }

    fun getPcPairing(): String? = pcPairingStore.read()
    fun savePcPairing(json: String): Boolean = pcPairingStore.write(json)
    fun deletePcPairing(): Boolean = pcPairingStore.delete()

    fun getConnectors(): String? = connectorsStore.read()
    fun saveConnectors(json: String): Boolean = connectorsStore.write(json)

    fun getHomeAssistantToken(): String? = homeAssistantTokenStore.read()
    fun hasHomeAssistantToken(): Boolean = !getHomeAssistantToken().isNullOrBlank()
    suspend fun saveHomeAssistantToken(value: String): Boolean = withContext(Dispatchers.IO) { homeAssistantTokenStore.write(value) }
    suspend fun deleteHomeAssistantToken(): Boolean = withContext(Dispatchers.IO) { homeAssistantTokenStore.delete() }

    fun getLaPosteKey(): String? = laPosteKeyStore.read()
    suspend fun saveLaPosteKey(value: String): Boolean = withContext(Dispatchers.IO) { laPosteKeyStore.write(value) }
    suspend fun deleteLaPosteKey(): Boolean = withContext(Dispatchers.IO) { laPosteKeyStore.delete() }

    fun getSncfKey(): String? = sncfKeyStore.read()
    suspend fun saveSncfKey(value: String): Boolean = withContext(Dispatchers.IO) { sncfKeyStore.write(value) }
    suspend fun deleteSncfKey(): Boolean = withContext(Dispatchers.IO) { sncfKeyStore.delete() }
    fun getNavitiaKey(): String? = navitiaKeyStore.read()
    suspend fun saveNavitiaKey(value: String): Boolean = withContext(Dispatchers.IO) { navitiaKeyStore.write(value) }
    suspend fun deleteNavitiaKey(): Boolean = withContext(Dispatchers.IO) { navitiaKeyStore.delete() }
    fun getPerplexityKey(): String? = perplexityKeyStore.read()
    suspend fun savePerplexityKey(value: String): Boolean = withContext(Dispatchers.IO) { perplexityKeyStore.write(value) }
    suspend fun deletePerplexityKey(): Boolean = withContext(Dispatchers.IO) { perplexityKeyStore.delete() }
    fun getRteKey(): String? = rteKeyStore.read()
    suspend fun saveRteKey(value: String): Boolean = withContext(Dispatchers.IO) { rteKeyStore.write(value) }
    suspend fun deleteRteKey(): Boolean = withContext(Dispatchers.IO) { rteKeyStore.delete() }

    private val KEY_ASSISTANT_NAME = stringPreferencesKey("assistant_name")
    private val KEY_USER_NAME = stringPreferencesKey("user_name")
    private val KEY_VOICE = stringPreferencesKey("voice")
    private val KEY_MODEL = stringPreferencesKey("model")
    private val KEY_REST_MODEL = stringPreferencesKey("rest_model")
    private val KEY_TTS_MODEL = stringPreferencesKey("tts_model")
    private val KEY_THEME_HUE = floatPreferencesKey("theme_hue")
    private val KEY_WAKE_WORD = booleanPreferencesKey("wake_word_enabled")
    private val KEY_DEVICE_CONTROL = booleanPreferencesKey("device_control_enabled")
    private val KEY_BRIEFING = booleanPreferencesKey("briefing_enabled")
    private val KEY_CHAT_HISTORY = booleanPreferencesKey("chat_history_enabled")
    private val KEY_AVATAR_FACE = booleanPreferencesKey("avatar_face")
    private val KEY_AVATAR_LIGHT = booleanPreferencesKey("avatar_light")
    private val KEY_AVATAR_HAIR = stringPreferencesKey("avatar_hair")
    private val KEY_AVATAR_HAIR_COLOUR = stringPreferencesKey("avatar_hair_colour")
    private val KEY_GOOGLE = booleanPreferencesKey("google_connected")
    private val KEY_GOOGLE_CLEANUP = booleanPreferencesKey("google_cleanup")
    private val KEY_AVATAR_SKIN = intPreferencesKey("avatar_face_skin")
    private val KEY_MESSAGE_AUTO_SEND = booleanPreferencesKey("message_auto_send_v2")
    private val KEY_GMAIL_AUTO_SEND = booleanPreferencesKey("gmail_auto_send_v2")
    private val KEY_CALENDAR_AUTO_CREATE = booleanPreferencesKey("calendar_auto_create_v2")
    private val KEY_CAR_AUDIO = intPreferencesKey("car_audio_mode")
    private val KEY_OFFLINE_MODE = intPreferencesKey("offline_mode")
    private val KEY_KEEP_TRANSCRIPTS = booleanPreferencesKey("keep_session_transcripts")
    private val KEY_LOCAL_AI = booleanPreferencesKey("local_ai_enabled")
    private val KEY_SKIP_CONFIRMATIONS = booleanPreferencesKey("skip_confirmations_v2")
    private val KEY_RAIN_ALERTS = booleanPreferencesKey("rain_alerts")
    private val KEY_WAKE_PAUSE_SAVER = booleanPreferencesKey("wake_pause_saver")
    private val KEY_AVATAR_MODEL = intPreferencesKey("avatar_face_model")
    /** Set once a face is chosen in a build that has Haseo (see [avatarModelIndex]). */
    private val KEY_AVATAR_MODEL_HASEO = booleanPreferencesKey("avatar_face_model_haseo")
    private val KEY_AVATAR_POLYGONS = stringPreferencesKey("avatar_polygon_level")
    private val KEY_AVATAR_HASEO = stringPreferencesKey("avatar_haseo_custom")
    private val KEY_AVATAR_HASEO_LOOKS = stringPreferencesKey("avatar_haseo_looks")
    private val KEY_AVATAR_LIPS = intPreferencesKey("avatar_lip_colour")
    private val KEY_AVATAR_CAP = intPreferencesKey("avatar_cap")
    private val KEY_MUTE_WHILE_SPEAKING = booleanPreferencesKey("mute_mic_while_speaking")
    private val KEY_SPEECH_LANGUAGE = stringPreferencesKey("speech_language")
    private val KEY_PROACTIVE = booleanPreferencesKey("proactive_enabled")
    private val KEY_WAKE_SENSITIVITY = intPreferencesKey("wake_sensitivity")
    private val KEY_WORK_FOLDER = stringPreferencesKey("work_folder_uri")
    private val KEY_OBSIDIAN_VAULT = stringPreferencesKey("obsidian_vault_uri")
    private val KEY_HOME_ASSISTANT_URL = stringPreferencesKey("home_assistant_url")
    private val KEY_AUDIO_IN = stringPreferencesKey("audio_input_device")
    private val KEY_AUDIO_OUT = stringPreferencesKey("audio_output_device")
    private val KEY_LAST_BRIEFING = stringPreferencesKey("last_briefing_date")

    val assistantName: Flow<String> = context.dataStore.data.map { it[KEY_ASSISTANT_NAME] ?: "JARVIS" }
    val userName: Flow<String> = context.dataStore.data.map { it[KEY_USER_NAME] ?: "" }
    val voice: Flow<String> = context.dataStore.data.map { it[KEY_VOICE] ?: "Puck" }
    val model: Flow<String> = context.dataStore.data.map { it[KEY_MODEL] ?: DEFAULT_MODEL }
    val restModel: Flow<String> = context.dataStore.data.map {
        it[KEY_REST_MODEL]?.takeIf { value -> value.isNotBlank() } ?: DEFAULT_REST_MODEL
    }
    /** Speech model for spoken chat answers; blank means "find one from ListModels". */
    val ttsModel: Flow<String> = context.dataStore.data.map { it[KEY_TTS_MODEL].orEmpty() }
    val themeHue: Flow<Float> = context.dataStore.data.map { it[KEY_THEME_HUE] ?: 190f }
    /** Master switch for the phone-control tools (on top of the system accessibility switch). */
    val deviceControlEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_DEVICE_CONTROL] ?: true }
    /** The folder (a Storage Access Framework tree URI) the file manager may work in; empty when none was chosen. */
    val workFolder: Flow<String> = context.dataStore.data.map { it[KEY_WORK_FOLDER].orEmpty() }
    /** The folder of the user's Obsidian vault (a tree URI they picked), empty when none. */
    val obsidianVault: Flow<String> = context.dataStore.data.map { it[KEY_OBSIDIAN_VAULT].orEmpty() }
    /** The user's own Home Assistant server address (e.g. "http://192.168.1.50:8123"); empty when not configured. Not a secret, kept in plain DataStore like the work folder. */
    val homeAssistantUrl: Flow<String> = context.dataStore.data.map { it[KEY_HOME_ASSISTANT_URL].orEmpty() }
    val wakeSensitivity: Flow<Int> = context.dataStore.data.map { it[KEY_WAKE_SENSITIVITY] ?: 1 }
    val proactiveEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_PROACTIVE] ?: false }
    val audioInputKey: Flow<String> = context.dataStore.data.map { it[KEY_AUDIO_IN].orEmpty() }
    val audioOutputKey: Flow<String> = context.dataStore.data.map { it[KEY_AUDIO_OUT].orEmpty() }
    val chatHistoryEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_CHAT_HISTORY] ?: true }
    /** True (default): the HUD shows the holographic face; false: the reactor core. */
    /** Whether the user connected their Google account for Gmail and Drive. */
    val googleConnected: Flow<Boolean> = context.dataStore.data.map { it[KEY_GOOGLE] ?: false }

    /** The user once allowed sorting the mail: every later « Reconnecter Google » asks for it again, so it is not lost on the way. */
    val googleCleanup: Flow<Boolean> = context.dataStore.data.map { it[KEY_GOOGLE_CLEANUP] ?: false }
    val avatarFace: Flow<Boolean> = context.dataStore.data.map { it[KEY_AVATAR_FACE] ?: true }
    /** The light avatar: half the frame rate and no fine hair strands, for a phone that struggles (or to save battery). */
    val avatarLight: Flow<Boolean> = context.dataStore.data.map { it[KEY_AVATAR_LIGHT] ?: false }
    /** The hairstyle chosen for each face: face label → hairstyle id (absent: the face's own hair). */
    val avatarHair: Flow<Map<String, String>> = context.dataStore.data.map { decodeHairChoices(it[KEY_AVATAR_HAIR].orEmpty()) }
    /** The hair colour chosen for each face: face label → HairShade id (absent: the face's own). */
    val avatarHairColour: Flow<Map<String, String>> = context.dataStore.data.map { decodeHairChoices(it[KEY_AVATAR_HAIR_COLOUR].orEmpty()) }
    /** 0 = the glowing web, 1..4 = a skin of that tone over the face (light by default). */
    /** Which head: 0 = the original, 1 and 2 = the other faces (see avatar/AvatarFaces.kt). */
    val avatarModel: Flow<Int> = context.dataStore.data.map { avatarModelIndex(it[KEY_AVATAR_MODEL] ?: 0, it[KEY_AVATAR_MODEL_HASEO] == true) }
    /** How finely the heads are cut into triangles: a PolygonLevel id (eco, low, medium, high, ultra), medium by default. */
    val avatarPolygonLevel: Flow<String> = context.dataStore.data.map { it[KEY_AVATAR_POLYGONS] ?: "medium" }
    /** Haseo's creator sliders, as "faceWidth=0.3;eyeSize=-0.2" (see avatar.FaceCustomizer.encode). */
    val avatarHaseoCustom: Flow<String> = context.dataStore.data.map { it[KEY_AVATAR_HASEO].orEmpty() }
    /** Haseo's saved looks: name → sliders, as "name=sliders" lines. */
    val avatarHaseoLooks: Flow<List<Pair<String, String>>> = context.dataStore.data.map { decodeLooks(it[KEY_AVATAR_HASEO_LOOKS].orEmpty()) }
    /** Car mode (Android Auto): 0 = automatic, 1 = always, 2 = never. */
    val carAudioMode: Flow<Int> = context.dataStore.data.map { it[KEY_CAR_AUDIO] ?: 0 }
    /** Offline mode: 0 = automatic (when there is no network or Gemini cannot be reached), 1 = always, 2 = never. */
    val offlineMode: Flow<Int> = context.dataStore.data.map { it[KEY_OFFLINE_MODE] ?: 0 }
    /** Keeps what was said in the voice sessions (shown on the main screen, and recalled to the model at the next session). On by default. */
    val keepSessionTranscripts: Flow<Boolean> = context.dataStore.data.map { it[KEY_KEEP_TRANSCRIPTS] ?: true }
    /** Whether the offline mode may use the local model, once one is installed, for what is not a fixed command. On by default. */
    val localAiEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_LOCAL_AI] ?: true }
    /*
     * Since 0.9.86 Jarvis asks no confirmation by default (the user asked for all of them to go): the four switches below
     * are on unless the user turns one off. Their keys took a "_v2" suffix so that an "off" saved by an older version
     * does not keep the confirmation alive.
     */
    /** On by default: "send" said by the user really sends the message (SMS, WhatsApp, Messenger) to a contact, without a confirmation. */
    val messageAutoSend: Flow<Boolean> = context.dataStore.data.map { it[KEY_MESSAGE_AUTO_SEND] ?: true }
    /** On by default: when the user has just asked clearly, gmail's send action really sends the mail instead of only drafting it. */
    val gmailAutoSend: Flow<Boolean> = context.dataStore.data.map { it[KEY_GMAIL_AUTO_SEND] ?: true }
    /** On by default: when the user has just asked clearly, calendar's add action really creates the event instead of only opening the form. */
    val calendarAutoCreate: Flow<Boolean> = context.dataStore.data.map { it[KEY_CALENDAR_AUTO_CREATE] ?: true }
    /** Off by default: a notification when rain is about to start where the phone is (see weather/RainSoon.kt). */
    val rainAlerts: Flow<Boolean> = context.dataStore.data.map { it[KEY_RAIN_ALERTS] ?: false }
    /** On by default: the wake word stops listening while Android's battery saver is on (the microphone is the costliest part). */
    val wakePauseInSaver: Flow<Boolean> = context.dataStore.data.map { it[KEY_WAKE_PAUSE_SAVER] ?: true }
    /**
     * On by default: volume changes, file writes/deletes/organising, mail cleanup, plugin installs and every on-screen
     * action (system, permission and install screens included) go ahead without a confirmation banner. Turned off, they
     * all ask again (see device/ScreenModel.kt ALWAYS_CONFIRM_PACKAGES for the screens where every tap then asks).
     */
    val skipConfirmations: Flow<Boolean> = context.dataStore.data.map { it[KEY_SKIP_CONFIRMATIONS] ?: true }
    val avatarSkin: Flow<Int> = context.dataStore.data.map { it[KEY_AVATAR_SKIN] ?: 7 }   // 7 = the blue hologram (avatar.BLUE_HOLO_SKIN), unless the user chose another look
    /** 0 = natural, 1..4 = a lip colour (rose, red, plum, coral). */
    val avatarLips: Flow<Int> = context.dataStore.data.map { it[KEY_AVATAR_LIPS] ?: 0 }
    /** The cap on the avatar: 0 = none, 1..5 = black, blue, red, white, khaki, 6 = grey-green with an emblem. */
    val avatarCap: Flow<Int> = context.dataStore.data.map { it[KEY_AVATAR_CAP] ?: 0 }
    val muteMicWhileSpeaking: Flow<Boolean> = context.dataStore.data.map { it[KEY_MUTE_WHILE_SPEAKING] ?: true }
    /** BCP-47 code the voice is pinned to, or empty for automatic (the assistant may switch language). */
    val speechLanguage: Flow<String> = context.dataStore.data.map { it[KEY_SPEECH_LANGUAGE].orEmpty() }
    val briefingEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_BRIEFING] ?: true }
    val lastBriefingDate: Flow<String> = context.dataStore.data.map { it[KEY_LAST_BRIEFING].orEmpty() }
    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_WAKE_WORD] ?: false }

    suspend fun setAssistantName(v: String) = context.dataStore.edit { it[KEY_ASSISTANT_NAME] = v }
    suspend fun setUserName(v: String) = context.dataStore.edit { it[KEY_USER_NAME] = v }
    suspend fun setVoice(v: String) = context.dataStore.edit { it[KEY_VOICE] = v }
    suspend fun setModel(v: String) = context.dataStore.edit { it[KEY_MODEL] = v }
    suspend fun setRestModel(v: String) = context.dataStore.edit { it[KEY_REST_MODEL] = v }
    suspend fun setTtsModel(v: String) = context.dataStore.edit { it[KEY_TTS_MODEL] = v }
    suspend fun setThemeHue(v: Float) = context.dataStore.edit { it[KEY_THEME_HUE] = v }
    suspend fun setDeviceControlEnabled(v: Boolean) = context.dataStore.edit { it[KEY_DEVICE_CONTROL] = v }
    suspend fun setWorkFolder(v: String) = context.dataStore.edit { it[KEY_WORK_FOLDER] = v }
    suspend fun setObsidianVault(v: String) = context.dataStore.edit { it[KEY_OBSIDIAN_VAULT] = v }
    suspend fun setHomeAssistantUrl(v: String) = context.dataStore.edit { it[KEY_HOME_ASSISTANT_URL] = v.trim() }
    suspend fun setWakeSensitivity(v: Int) = context.dataStore.edit { it[KEY_WAKE_SENSITIVITY] = v.coerceIn(0, 2) }
    suspend fun setProactiveEnabled(v: Boolean) = context.dataStore.edit { it[KEY_PROACTIVE] = v }
    suspend fun setAudioInputKey(v: String) = context.dataStore.edit { it[KEY_AUDIO_IN] = v }
    suspend fun setAudioOutputKey(v: String) = context.dataStore.edit { it[KEY_AUDIO_OUT] = v }
    suspend fun setChatHistoryEnabled(v: Boolean) = context.dataStore.edit { it[KEY_CHAT_HISTORY] = v }
    suspend fun snapshotChatHistoryEnabled() = chatHistoryEnabled.first()
    suspend fun setGoogleConnected(v: Boolean) = context.dataStore.edit { it[KEY_GOOGLE] = v }
    suspend fun setGoogleCleanup(v: Boolean) = context.dataStore.edit { it[KEY_GOOGLE_CLEANUP] = v }
    suspend fun setAvatarFace(v: Boolean) = context.dataStore.edit { it[KEY_AVATAR_FACE] = v }
    suspend fun setAvatarLight(v: Boolean) = context.dataStore.edit { it[KEY_AVATAR_LIGHT] = v }
    suspend fun setAvatarHair(face: String, hair: String) = setPerFace(KEY_AVATAR_HAIR, face, hair)
    suspend fun setAvatarHairColour(face: String, colour: String) = setPerFace(KEY_AVATAR_HAIR_COLOUR, face, colour)

    private suspend fun setPerFace(key: androidx.datastore.preferences.core.Preferences.Key<String>, face: String, value: String) =
        context.dataStore.edit {
            val m = decodeHairChoices(it[key].orEmpty()).toMutableMap()
            if (value.isEmpty()) m.remove(face) else m[face] = value
            it[key] = m.entries.joinToString(";") { (k, v) -> "$k=$v" }
        }
    suspend fun setAvatarModel(v: Int) = context.dataStore.edit { it[KEY_AVATAR_MODEL] = v; it[KEY_AVATAR_MODEL_HASEO] = true }
    suspend fun setAvatarPolygonLevel(v: String) = context.dataStore.edit { it[KEY_AVATAR_POLYGONS] = v }
    suspend fun setAvatarHaseoCustom(v: String) = context.dataStore.edit { it[KEY_AVATAR_HASEO] = v }
    suspend fun setAvatarHaseoLooks(v: List<Pair<String, String>>) =
        context.dataStore.edit { it[KEY_AVATAR_HASEO_LOOKS] = v.takeLast(8).joinToString("\n") { (name, values) -> "${name.replace('\n', ' ').replace("=", "-")}=$values" } }
    suspend fun setKeepSessionTranscripts(v: Boolean) = context.dataStore.edit { it[KEY_KEEP_TRANSCRIPTS] = v }
    suspend fun setLocalAiEnabled(v: Boolean) = context.dataStore.edit { it[KEY_LOCAL_AI] = v }
    suspend fun setOfflineMode(v: Int) = context.dataStore.edit { it[KEY_OFFLINE_MODE] = v }
    suspend fun setCarAudioMode(v: Int) = context.dataStore.edit { it[KEY_CAR_AUDIO] = v }
    suspend fun setMessageAutoSend(v: Boolean) = context.dataStore.edit { it[KEY_MESSAGE_AUTO_SEND] = v }
    suspend fun setGmailAutoSend(v: Boolean) = context.dataStore.edit { it[KEY_GMAIL_AUTO_SEND] = v }
    suspend fun setCalendarAutoCreate(v: Boolean) = context.dataStore.edit { it[KEY_CALENDAR_AUTO_CREATE] = v }
    suspend fun setRainAlerts(v: Boolean) = context.dataStore.edit { it[KEY_RAIN_ALERTS] = v }
    suspend fun setWakePauseInSaver(v: Boolean) = context.dataStore.edit { it[KEY_WAKE_PAUSE_SAVER] = v }
    suspend fun setSkipConfirmations(v: Boolean) = context.dataStore.edit { it[KEY_SKIP_CONFIRMATIONS] = v }
    suspend fun setAvatarSkin(v: Int) = context.dataStore.edit { it[KEY_AVATAR_SKIN] = v }
    suspend fun setAvatarLips(v: Int) = context.dataStore.edit { it[KEY_AVATAR_LIPS] = v }
    suspend fun setAvatarCap(v: Int) = context.dataStore.edit { it[KEY_AVATAR_CAP] = v }
    suspend fun setMuteMicWhileSpeaking(v: Boolean) = context.dataStore.edit { it[KEY_MUTE_WHILE_SPEAKING] = v }
    suspend fun setSpeechLanguage(v: String) = context.dataStore.edit { it[KEY_SPEECH_LANGUAGE] = v }
    suspend fun setBriefingEnabled(v: Boolean) = context.dataStore.edit { it[KEY_BRIEFING] = v }
    suspend fun setLastBriefingDate(v: String) = context.dataStore.edit { it[KEY_LAST_BRIEFING] = v }
    suspend fun setWakeWordEnabled(v: Boolean) = context.dataStore.edit { it[KEY_WAKE_WORD] = v }

    suspend fun snapshotAssistantName() = assistantName.first()
    suspend fun snapshotUserName() = userName.first()
    suspend fun snapshotVoice() = voice.first()
    suspend fun snapshotModel() = model.first()
    suspend fun snapshotRestModel() = restModel.first()
    suspend fun snapshotBriefingEnabled() = briefingEnabled.first()
    suspend fun snapshotProactiveEnabled() = proactiveEnabled.first()
    suspend fun snapshotLastBriefingDate() = lastBriefingDate.first()
    suspend fun snapshotTtsModel() = ttsModel.first()

    companion object {
        private const val KEY_API_KEY = "gemini_api_key"
        const val MAX_API_KEYS = 3
        private const val SECURE_PREFS_FILE = "jarvis_secure_prefs"
        private const val API_KEY_FILE = "jarvis_api_key.enc"
        private const val API_KEY_ALIAS = "jarvis_api_key_v2"
        /**
         * Confirmed via this project's own ListModels response (Settings →
         * Advanced → "List Live-capable models"): there is no
         * "gemini-3.6-*" model that supports bidiGenerateContent for this key.
         * "gemini-3.8-live" is the newest one that does — matches Google's own
         * "Gemini 3.8 Flash is now available" announcement. If your account's
         * available models differ, override this from Settings — no rebuild
         * needed.
         */
        const val DEFAULT_MODEL = "models/gemini-3.8-live"

        /** Model for the text chat, over plain generateContent (not the Live WebSocket). */
        const val DEFAULT_REST_MODEL = "models/gemini-3.6-flash"
        /**
         * Gemini's prebuilt voices (the same for the Live API and the speech endpoint), women's voices first, each with the character
         * Google gives it. There were only five here, two of them women's; these are all thirty.
         */
        val VOICES = listOf(
            Voice("Kore", true, "ferme", "firm"), Voice("Aoede", true, "légère", "breezy"), Voice("Leda", true, "jeune", "youthful"),
            Voice("Zephyr", true, "lumineuse", "bright"), Voice("Autonoe", true, "lumineuse", "bright"),
            Voice("Callirrhoe", true, "décontractée", "easy-going"), Voice("Despina", true, "douce", "smooth"),
            Voice("Erinome", true, "claire", "clear"), Voice("Laomedeia", true, "enjouée", "upbeat"), Voice("Achernar", true, "tendre", "soft"),
            Voice("Gacrux", true, "mûre", "mature"), Voice("Pulcherrima", true, "assurée", "forward"),
            Voice("Vindemiatrix", true, "délicate", "gentle"), Voice("Sulafat", true, "chaleureuse", "warm"),
            Voice("Puck", false, "enjouée", "upbeat"), Voice("Charon", false, "posée", "informative"), Voice("Fenrir", false, "vive", "excitable"),
            Voice("Orus", false, "ferme", "firm"), Voice("Enceladus", false, "soufflée", "breathy"), Voice("Iapetus", false, "claire", "clear"),
            Voice("Umbriel", false, "décontractée", "easy-going"), Voice("Algieba", false, "douce", "smooth"),
            Voice("Algenib", false, "rocailleuse", "gravelly"), Voice("Rasalgethi", false, "posée", "informative"),
            Voice("Alnilam", false, "ferme", "firm"), Voice("Schedar", false, "égale", "even"), Voice("Achird", false, "amicale", "friendly"),
            Voice("Zubenelgenubi", false, "détendue", "casual"), Voice("Sadachbia", false, "vive", "lively"),
            Voice("Sadaltager", false, "savante", "knowledgeable"),
        )
        val AVAILABLE_VOICES = VOICES.map { it.name }
    }
}

/** One of Gemini's voices: a woman's or a man's, and its character in French and in English. */
data class Voice(val name: String, val female: Boolean, val styleFr: String, val styleEn: String) {
    fun label(english: Boolean): String =
        if (english) "$name · " + (if (female) "female" else "male") + ", $styleEn" else "$name · " + (if (female) "féminine" else "masculine") + ", $styleFr"
}

/**
 * The face chosen, as an index in avatar.AVATAR_FACES. Haseo came in as the fifth face (index 4), before the textured characters:
 * a character chosen in an older build (4 and on) is one further now, until a face is chosen again.
 */
internal fun avatarModelIndex(stored: Int, savedWithHaseo: Boolean): Int = if (!savedWithHaseo && stored >= 4) stored + 1 else stored

internal fun decodeLooks(s: String): List<Pair<String, String>> =
    s.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0] to it[1] } }

internal fun decodeHairChoices(s: String): Map<String, String> =
    s.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }?.let { it[0] to it[1] } }.toMap()
