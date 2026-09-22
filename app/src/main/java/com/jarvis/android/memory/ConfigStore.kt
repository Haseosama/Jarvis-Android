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

    fun getHomeAssistantToken(): String? = homeAssistantTokenStore.read()
    fun hasHomeAssistantToken(): Boolean = !getHomeAssistantToken().isNullOrBlank()
    suspend fun saveHomeAssistantToken(value: String): Boolean = withContext(Dispatchers.IO) { homeAssistantTokenStore.write(value) }
    suspend fun deleteHomeAssistantToken(): Boolean = withContext(Dispatchers.IO) { homeAssistantTokenStore.delete() }

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
    private val KEY_GOOGLE = booleanPreferencesKey("google_connected")
    private val KEY_AVATAR_SKIN = intPreferencesKey("avatar_face_skin")
    private val KEY_MESSAGE_AUTO_SEND = booleanPreferencesKey("message_auto_send")
    private val KEY_GMAIL_AUTO_SEND = booleanPreferencesKey("gmail_auto_send")
    private val KEY_CALENDAR_AUTO_CREATE = booleanPreferencesKey("calendar_auto_create")
    private val KEY_CAR_AUDIO = intPreferencesKey("car_audio_mode")
    private val KEY_OFFLINE_MODE = intPreferencesKey("offline_mode")
    private val KEY_KEEP_TRANSCRIPTS = booleanPreferencesKey("keep_session_transcripts")
    private val KEY_LOCAL_AI = booleanPreferencesKey("local_ai_enabled")
    private val KEY_SKIP_CONFIRMATIONS = booleanPreferencesKey("skip_confirmations")
    private val KEY_AVATAR_MODEL = intPreferencesKey("avatar_face_model")
    private val KEY_AVATAR_LIPS = intPreferencesKey("avatar_lip_colour")
    private val KEY_AVATAR_CAP = intPreferencesKey("avatar_cap")
    private val KEY_MUTE_WHILE_SPEAKING = booleanPreferencesKey("mute_mic_while_speaking")
    private val KEY_SPEECH_LANGUAGE = stringPreferencesKey("speech_language")
    private val KEY_PROACTIVE = booleanPreferencesKey("proactive_enabled")
    private val KEY_WAKE_SENSITIVITY = intPreferencesKey("wake_sensitivity")
    private val KEY_WORK_FOLDER = stringPreferencesKey("work_folder_uri")
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
    val avatarFace: Flow<Boolean> = context.dataStore.data.map { it[KEY_AVATAR_FACE] ?: true }
    /** 0 = the glowing web, 1..4 = a skin of that tone over the face (light by default). */
    /** Which head: 0 = the original, 1 and 2 = the other faces (see avatar/AvatarFaces.kt). */
    val avatarModel: Flow<Int> = context.dataStore.data.map { it[KEY_AVATAR_MODEL] ?: 0 }
    /** Off by default: when on, "send" said by the user really sends the message (SMS, WhatsApp) to a contact, without a confirmation. */
    /** Car mode (Android Auto): 0 = automatic, 1 = always, 2 = never. */
    val carAudioMode: Flow<Int> = context.dataStore.data.map { it[KEY_CAR_AUDIO] ?: 0 }
    /** Offline mode: 0 = automatic (when there is no network or Gemini cannot be reached), 1 = always, 2 = never. */
    val offlineMode: Flow<Int> = context.dataStore.data.map { it[KEY_OFFLINE_MODE] ?: 0 }
    /** Keeps what was said in the voice sessions (shown on the main screen, and recalled to the model at the next session). On by default. */
    val keepSessionTranscripts: Flow<Boolean> = context.dataStore.data.map { it[KEY_KEEP_TRANSCRIPTS] ?: true }
    /** Whether the offline mode may use the local model, once one is installed, for what is not a fixed command. On by default. */
    val localAiEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_LOCAL_AI] ?: true }
    val messageAutoSend: Flow<Boolean> = context.dataStore.data.map { it[KEY_MESSAGE_AUTO_SEND] ?: false }
    /** Off by default: when on, and the user has just asked clearly, gmail's send action really sends the mail instead of only drafting it. */
    val gmailAutoSend: Flow<Boolean> = context.dataStore.data.map { it[KEY_GMAIL_AUTO_SEND] ?: false }
    /** Off by default: when on, and the user has just asked clearly, calendar's add action really creates the event instead of only opening the form. */
    val calendarAutoCreate: Flow<Boolean> = context.dataStore.data.map { it[KEY_CALENDAR_AUTO_CREATE] ?: false }
    /**
     * Off by default: when on, volume changes, file writes/deletes/organising and any other on-screen action Jarvis would
     * normally ask about go ahead without a confirmation banner. Screens that touch system security, permissions or
     * app installs (settings, permission controller, package installer…) still always ask, whatever this is set to —
     * that one check is not a preference, it is what stops Jarvis from ever approving its own system-level access.
     */
    val skipConfirmations: Flow<Boolean> = context.dataStore.data.map { it[KEY_SKIP_CONFIRMATIONS] ?: false }
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
    suspend fun setHomeAssistantUrl(v: String) = context.dataStore.edit { it[KEY_HOME_ASSISTANT_URL] = v.trim() }
    suspend fun setWakeSensitivity(v: Int) = context.dataStore.edit { it[KEY_WAKE_SENSITIVITY] = v.coerceIn(0, 2) }
    suspend fun setProactiveEnabled(v: Boolean) = context.dataStore.edit { it[KEY_PROACTIVE] = v }
    suspend fun setAudioInputKey(v: String) = context.dataStore.edit { it[KEY_AUDIO_IN] = v }
    suspend fun setAudioOutputKey(v: String) = context.dataStore.edit { it[KEY_AUDIO_OUT] = v }
    suspend fun setChatHistoryEnabled(v: Boolean) = context.dataStore.edit { it[KEY_CHAT_HISTORY] = v }
    suspend fun snapshotChatHistoryEnabled() = chatHistoryEnabled.first()
    suspend fun setGoogleConnected(v: Boolean) = context.dataStore.edit { it[KEY_GOOGLE] = v }
    suspend fun setAvatarFace(v: Boolean) = context.dataStore.edit { it[KEY_AVATAR_FACE] = v }
    suspend fun setAvatarModel(v: Int) = context.dataStore.edit { it[KEY_AVATAR_MODEL] = v }
    suspend fun setKeepSessionTranscripts(v: Boolean) = context.dataStore.edit { it[KEY_KEEP_TRANSCRIPTS] = v }
    suspend fun setLocalAiEnabled(v: Boolean) = context.dataStore.edit { it[KEY_LOCAL_AI] = v }
    suspend fun setOfflineMode(v: Int) = context.dataStore.edit { it[KEY_OFFLINE_MODE] = v }
    suspend fun setCarAudioMode(v: Int) = context.dataStore.edit { it[KEY_CAR_AUDIO] = v }
    suspend fun setMessageAutoSend(v: Boolean) = context.dataStore.edit { it[KEY_MESSAGE_AUTO_SEND] = v }
    suspend fun setGmailAutoSend(v: Boolean) = context.dataStore.edit { it[KEY_GMAIL_AUTO_SEND] = v }
    suspend fun setCalendarAutoCreate(v: Boolean) = context.dataStore.edit { it[KEY_CALENDAR_AUTO_CREATE] = v }
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
        val AVAILABLE_VOICES = listOf("Puck", "Charon", "Kore", "Fenrir", "Aoede")
    }
}
