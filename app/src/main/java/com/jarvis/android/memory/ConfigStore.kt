package com.jarvis.android.memory

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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

    private val KEY_ASSISTANT_NAME = stringPreferencesKey("assistant_name")
    private val KEY_USER_NAME = stringPreferencesKey("user_name")
    private val KEY_VOICE = stringPreferencesKey("voice")
    private val KEY_MODEL = stringPreferencesKey("model")
    private val KEY_REST_MODEL = stringPreferencesKey("rest_model")
    private val KEY_TTS_MODEL = stringPreferencesKey("tts_model")
    private val KEY_THEME_HUE = floatPreferencesKey("theme_hue")
    private val KEY_WAKE_WORD = booleanPreferencesKey("wake_word_enabled")
    private val KEY_DEVICE_CONTROL = booleanPreferencesKey("device_control_enabled")

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
    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_WAKE_WORD] ?: false }

    suspend fun setAssistantName(v: String) = context.dataStore.edit { it[KEY_ASSISTANT_NAME] = v }
    suspend fun setUserName(v: String) = context.dataStore.edit { it[KEY_USER_NAME] = v }
    suspend fun setVoice(v: String) = context.dataStore.edit { it[KEY_VOICE] = v }
    suspend fun setModel(v: String) = context.dataStore.edit { it[KEY_MODEL] = v }
    suspend fun setRestModel(v: String) = context.dataStore.edit { it[KEY_REST_MODEL] = v }
    suspend fun setTtsModel(v: String) = context.dataStore.edit { it[KEY_TTS_MODEL] = v }
    suspend fun setThemeHue(v: Float) = context.dataStore.edit { it[KEY_THEME_HUE] = v }
    suspend fun setDeviceControlEnabled(v: Boolean) = context.dataStore.edit { it[KEY_DEVICE_CONTROL] = v }
    suspend fun setWakeWordEnabled(v: Boolean) = context.dataStore.edit { it[KEY_WAKE_WORD] = v }

    suspend fun snapshotAssistantName() = assistantName.first()
    suspend fun snapshotUserName() = userName.first()
    suspend fun snapshotVoice() = voice.first()
    suspend fun snapshotModel() = model.first()
    suspend fun snapshotRestModel() = restModel.first()
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
