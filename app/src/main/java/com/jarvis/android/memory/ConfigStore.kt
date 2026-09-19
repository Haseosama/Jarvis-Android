package com.jarvis.android.memory

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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

    private val secure: SharedPreferences = openOrReset(
        open = {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        },
        reset = ::resetSecureStorage,
    )

    private fun resetSecureStorage() {
        Log.w("ConfigStore", "Stockage chiffré illisible : réinitialisation, la clé API doit être saisie à nouveau.")
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

    fun getApiKey(): String? = secure.getString(KEY_API_KEY, null)
    fun setApiKey(value: String) = secure.edit().putString(KEY_API_KEY, value).apply()
    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()

    /** Writes the key and reports whether it really reached storage (commit, not apply). */
    suspend fun saveApiKey(value: String): Boolean = withContext(Dispatchers.IO) {
        secure.edit().putString(KEY_API_KEY, value).commit()
    }

    /** Removes the key and reports whether the removal really reached storage. */
    suspend fun deleteApiKey(): Boolean = withContext(Dispatchers.IO) {
        secure.edit().remove(KEY_API_KEY).commit()
    }

    private val KEY_ASSISTANT_NAME = stringPreferencesKey("assistant_name")
    private val KEY_USER_NAME = stringPreferencesKey("user_name")
    private val KEY_VOICE = stringPreferencesKey("voice")
    private val KEY_MODEL = stringPreferencesKey("model")
    private val KEY_REST_MODEL = stringPreferencesKey("rest_model")
    private val KEY_THEME_HUE = floatPreferencesKey("theme_hue")
    private val KEY_WAKE_WORD = booleanPreferencesKey("wake_word_enabled")

    val assistantName: Flow<String> = context.dataStore.data.map { it[KEY_ASSISTANT_NAME] ?: "JARVIS" }
    val userName: Flow<String> = context.dataStore.data.map { it[KEY_USER_NAME] ?: "" }
    val voice: Flow<String> = context.dataStore.data.map { it[KEY_VOICE] ?: "Puck" }
    val model: Flow<String> = context.dataStore.data.map { it[KEY_MODEL] ?: DEFAULT_MODEL }
    val restModel: Flow<String> = context.dataStore.data.map {
        it[KEY_REST_MODEL]?.takeIf { value -> value.isNotBlank() } ?: DEFAULT_REST_MODEL
    }
    val themeHue: Flow<Float> = context.dataStore.data.map { it[KEY_THEME_HUE] ?: 190f }
    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_WAKE_WORD] ?: false }

    suspend fun setAssistantName(v: String) = context.dataStore.edit { it[KEY_ASSISTANT_NAME] = v }
    suspend fun setUserName(v: String) = context.dataStore.edit { it[KEY_USER_NAME] = v }
    suspend fun setVoice(v: String) = context.dataStore.edit { it[KEY_VOICE] = v }
    suspend fun setModel(v: String) = context.dataStore.edit { it[KEY_MODEL] = v }
    suspend fun setRestModel(v: String) = context.dataStore.edit { it[KEY_REST_MODEL] = v }
    suspend fun setThemeHue(v: Float) = context.dataStore.edit { it[KEY_THEME_HUE] = v }
    suspend fun setWakeWordEnabled(v: Boolean) = context.dataStore.edit { it[KEY_WAKE_WORD] = v }

    suspend fun snapshotAssistantName() = assistantName.first()
    suspend fun snapshotUserName() = userName.first()
    suspend fun snapshotVoice() = voice.first()
    suspend fun snapshotModel() = model.first()
    suspend fun snapshotRestModel() = restModel.first()

    companion object {
        private const val KEY_API_KEY = "gemini_api_key"
        private const val SECURE_PREFS_FILE = "jarvis_secure_prefs"
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
