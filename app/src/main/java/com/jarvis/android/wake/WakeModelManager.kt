package com.jarvis.android.wake

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

internal const val WAKE_MODEL_DIR = "wake-model"
internal const val WAKE_MODEL_MARKER = ".installed"
internal const val WAKE_FILE_MEL = "melspectrogram.tflite"
internal const val WAKE_FILE_EMBEDDING = "embedding_model.tflite"
internal const val WAKE_FILE_CLASSIFIER = "hey_jarvis_v0.1.tflite"
internal const val WAKE_MODEL_BASE = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/"
internal const val WAKE_FILE_MAX_BYTES = 5_000_000L

internal const val WAKE_FILE_CUSTOM = "custom_wake.tflite"
internal const val WAKE_LEARNED_DIR = "wake-learned"
internal const val WAKE_LEARNED_PREFIX = "learned_"
internal const val WAKE_FILE_SELECTED = ".selected"

/** A ready-made openWakeWord phrase the user can pick (same release as the Jarvis model). */
internal data class WakePreset(val file: String, val label: String)

internal val WAKE_PRESETS = listOf(
    WakePreset(WAKE_FILE_CLASSIFIER, "« Hey Jarvis »"),
    WakePreset("alexa_v0.1.tflite", "« Alexa »"),
    WakePreset("hey_mycroft_v0.1.tflite", "« Hey Mycroft »"),
    WakePreset("hey_rhasspy_v0.1.tflite", "« Hey Rhasspy »"),
)

/** The three files, in download order. */
internal val WAKE_FILES = listOf(WAKE_FILE_MEL, WAKE_FILE_EMBEDDING, WAKE_FILE_CLASSIFIER)

/** True when a model file has a plausible size and the TFLite header ("TFL3" at offset 4). */
/** A classifier is much smaller than the shared models: only the TFLite header and an upper size are checked. */
internal fun looksLikeClassifier(bytes: ByteArray): Boolean =
    bytes.size in 1_000..WAKE_FILE_MAX_BYTES.toInt() &&
        String(bytes, 4, 4, Charsets.US_ASCII) == "TFL3"

internal fun looksLikeTflite(bytes: ByteArray): Boolean =
    bytes.size in 100_000..WAKE_FILE_MAX_BYTES.toInt() &&
        bytes.size > 8 && String(bytes, 4, 4, Charsets.US_ASCII) == "TFL3"

/** Downloads, installs and locates the offline wake-word models. Nothing is downloaded unless the user asks. */
internal class WakeModelManager(private val context: Context, private val http: OkHttpClient) {
    val dir: File get() = File(context.filesDir, WAKE_MODEL_DIR)

    /** Words the user taught: kept apart from the downloaded models, which "Supprimer les modèles" empties. */
    val learnedDir: File get() = File(context.filesDir, WAKE_LEARNED_DIR)

    private fun fileFor(name: String): File = if (name.startsWith(WAKE_LEARNED_PREFIX)) File(learnedDir, name) else File(dir, name)

    /** The words taught so far: file name and label. */
    fun learnedWords(): List<Pair<String, String>> =
        learnedDir.listFiles { f -> f.name.startsWith(WAKE_LEARNED_PREFIX) && f.extension == "bin" }.orEmpty().sortedBy { it.name }
            .mapNotNull { f -> try { LearnedWord.labelOf(f.readBytes())?.let { f.name to it } } catch (_: java.io.IOException) { null } }

    /** Keeps a taught word and selects it. Returns its file name. */
    fun saveLearned(word: LearnedWord): String {
        learnedDir.mkdirs()
        val name = WAKE_LEARNED_PREFIX + System.currentTimeMillis() + ".bin"
        File(learnedDir, name).writeBytes(word.toBytes())
        select(name)
        return name
    }

    fun deleteLearned(name: String) {
        if (name.startsWith(WAKE_LEARNED_PREFIX)) File(learnedDir, name).delete()
    }

    /** File name of the classifier in use: a preset, or [WAKE_FILE_CUSTOM]. Defaults to "Hey Jarvis". */
    fun selected(): String {
        val name = try { File(dir, WAKE_FILE_SELECTED).readText().trim() } catch (_: Exception) { "" }
        return if (name.isNotEmpty() && fileFor(name).exists()) name else WAKE_FILE_CLASSIFIER
    }

    fun select(name: String) {
        if (fileFor(name).exists()) {
            dir.mkdirs()
            File(dir, WAKE_FILE_SELECTED).writeText(name)
        }
    }

    fun label(name: String): String =
        when {
            name == WAKE_FILE_CUSTOM -> "modèle personnalisé"
            name.startsWith(WAKE_LEARNED_PREFIX) -> try { LearnedWord.labelOf(File(learnedDir, name).readBytes())?.let { "« $it » (appris)" } ?: name } catch (_: java.io.IOException) { name }
            else -> WAKE_PRESETS.firstOrNull { it.file == name }?.label ?: name
        }

    fun installed(): Boolean =
        File(dir, WAKE_MODEL_MARKER).exists() && File(dir, WAKE_FILE_MEL).exists() &&
            File(dir, WAKE_FILE_EMBEDDING).exists() && fileFor(selected()).exists()

    /** Downloads one preset classifier next to the shared models, then selects it. Null on success. */
    suspend fun downloadPreset(preset: WakePreset): String? = withContext(Dispatchers.IO) {
        try {
            if (!File(dir, WAKE_MODEL_MARKER).exists()) return@withContext "Installez d’abord les modèles de base."
            if (!File(dir, preset.file).exists()) {
                val request = Request.Builder().url(WAKE_MODEL_BASE + preset.file).build()
                http.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext "Téléchargement impossible (HTTP ${response.code})."
                    val bytes = response.body?.bytes() ?: return@withContext "Téléchargement vide."
                    if (!looksLikeClassifier(bytes)) return@withContext "Fichier reçu invalide : ce n’est pas un modèle TFLite."
                    File(dir, preset.file).writeBytes(bytes)
                }
            }
            select(preset.file)
            null
        } catch (_: IOException) {
            "Téléchargement interrompu : vérifiez la connexion."
        } catch (_: Exception) {
            "Installation du modèle impossible."
        }
    }

    /** Installs a classifier the user trained with openWakeWord (a .tflite file), then selects it. Null on success. */
    fun importCustom(bytes: ByteArray): String? {
        if (!File(dir, WAKE_MODEL_MARKER).exists()) return "Installez d’abord les modèles de base."
        if (!looksLikeClassifier(bytes)) return "Ce fichier n’est pas un modèle TFLite valide."
        File(dir, WAKE_FILE_CUSTOM).writeBytes(bytes)
        select(WAKE_FILE_CUSTOM)
        return null
    }

    fun remove() {
        dir.deleteRecursively()
    }

    /** Returns null on success, otherwise a message for the user. [onProgress] gets 0..100. */
    suspend fun download(onProgress: (Int) -> Unit): String? = withContext(Dispatchers.IO) {
        try {
            remove()
            dir.mkdirs()
            WAKE_FILES.forEachIndexed { index, name ->
                currentCoroutineContext().ensureActive()
                val request = Request.Builder().url(WAKE_MODEL_BASE + name).build()
                http.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext fail("Téléchargement impossible (HTTP ${response.code}).")
                    val body = response.body ?: return@withContext fail("Téléchargement vide.")
                    if (body.contentLength() > WAKE_FILE_MAX_BYTES) return@withContext fail("Fichier inattendu (trop volumineux).")
                    val bytes = body.bytes()
                    if (!looksLikeTflite(bytes)) return@withContext fail("Fichier reçu invalide : ce n’est pas un modèle TFLite.")
                    File(dir, name).writeBytes(bytes)
                }
                onProgress((index + 1) * 100 / WAKE_FILES.size)
            }
            File(dir, WAKE_MODEL_MARKER).writeText("ok")
            null
        } catch (e: CancellationException) {
            remove()
            throw e
        } catch (_: IOException) {
            fail("Téléchargement interrompu : vérifiez la connexion.")
        } catch (_: Exception) {
            fail("Installation du modèle impossible.")
        }
    }

    private fun fail(message: String): String {
        remove()
        return message
    }
}
