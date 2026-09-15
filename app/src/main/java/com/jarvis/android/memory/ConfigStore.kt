package com.jarvis.android.memory

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "jarvis_settings")

/**
 * App configuration — Android port of `config/api_keys.json` + the UI's
 * live-theming / customization settings. The Gemini API key is kept in
 * `EncryptedSharedPreferences` (AES-256, Android Keystore-backed key) rather
 * than plain DataStore, since it is a credential, not a preference.
 */
class ConfigStore(private val context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val secure = EncryptedSharedPreferences.create(
        context,
        "jarvis_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getApiKey(): String? = secure.getString(KEY_API_KEY, null)
    fun setApiKey(value: String) = secure.edit().putString(KEY_API_KEY, value).apply()
    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()

    private val KEY_ASSISTANT_NAME = stringPreferencesKey("assistant_name")
    private val KEY_USER_NAME = stringPreferencesKey("user_name")
    private val KEY_VOICE = stringPreferencesKey("voice")
    private val KEY_MODEL = stringPreferencesKey("model")
    private val KEY_THEME_HUE = floatPreferencesKey("theme_hue")
    private val KEY_WAKE_WORD = booleanPreferencesKey("wake_word_enabled")

    val assistantName: Flow<String> = context.dataStore.data.map { it[KEY_ASSISTANT_NAME] ?: "JARVIS" }
    val userName: Flow<String> = context.dataStore.data.map { it[KEY_USER_NAME] ?: "" }
    val voice: Flow<String> = context.dataStore.data.map { it[KEY_VOICE] ?: "Puck" }
    val model: Flow<String> = context.dataStore.data.map { it[KEY_MODEL] ?: DEFAULT_MODEL }
    val themeHue: Flow<Float> = context.dataStore.data.map { it[KEY_THEME_HUE] ?: 190f }
    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_WAKE_WORD] ?: false }

    suspend fun setAssistantName(v: String) = context.dataStore.edit { it[KEY_ASSISTANT_NAME] = v }
    suspend fun setUserName(v: String) = context.dataStore.edit { it[KEY_USER_NAME] = v }
    suspend fun setVoice(v: String) = context.dataStore.edit { it[KEY_VOICE] = v }
    suspend fun setModel(v: String) = context.dataStore.edit { it[KEY_MODEL] = v }
    suspend fun setThemeHue(v: Float) = context.dataStore.edit { it[KEY_THEME_HUE] = v }
    suspend fun setWakeWordEnabled(v: Boolean) = context.dataStore.edit { it[KEY_WAKE_WORD] = v }

    suspend fun snapshotAssistantName() = assistantName.first()
    suspend fun snapshotUserName() = userName.first()
    suspend fun snapshotVoice() = voice.first()
    suspend fun snapshotModel() = model.first()

    companion object {
        private const val KEY_API_KEY = "gemini_api_key"
        /**
         * Gemini Flash 3.6's Live variant, as requested. There's no
         * Google doc page confirming this exact id (their docs reference
         * "Gemini 3.8 Flash" as of this writing, and preview ids shift), so if
         * setup ever rejects specifically with a "model not found"-style
         * message, check aistudio.google.com/apikey for the exact id and
         * override it from Settings → Advanced → Live model — no rebuild
         * needed either way.
         */
        const val DEFAULT_MODEL = "models/gemini-3.6-flash"
        val AVAILABLE_VOICES = listOf("Puck", "Charon", "Kore", "Fenrir", "Aoede")
    }
}
