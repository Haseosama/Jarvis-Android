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

/** The three files, in download order. */
internal val WAKE_FILES = listOf(WAKE_FILE_MEL, WAKE_FILE_EMBEDDING, WAKE_FILE_CLASSIFIER)

/** True when a model file has a plausible size and the TFLite header ("TFL3" at offset 4). */
internal fun looksLikeTflite(bytes: ByteArray): Boolean =
    bytes.size in 100_000..WAKE_FILE_MAX_BYTES.toInt() &&
        bytes.size > 8 && String(bytes, 4, 4, Charsets.US_ASCII) == "TFL3"

/** Downloads, installs and locates the offline wake-word models. Nothing is downloaded unless the user asks. */
internal class WakeModelManager(private val context: Context, private val http: OkHttpClient) {
    val dir: File get() = File(context.filesDir, WAKE_MODEL_DIR)

    fun installed(): Boolean = File(dir, WAKE_MODEL_MARKER).exists() && WAKE_FILES.all { File(dir, it).exists() }

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
