package com.jarvis.android.offline

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/*
 * Downloads a catalogue model (LocalModelCatalog.kt) with Android's own download manager: a model is hundreds of MB to 1.6 GB, and the
 * system service keeps going with the app closed or the screen off, resumes after a network cut and shows its own progress notification.
 * The file lands in the app's external files folder, is checked (size, zip header, hash when known), then becomes the model in place.
 *
 * For a gated Gemma file the user's Hugging Face token is used once, here, to get the signed link Hugging Face redirects to; only that
 * link goes to the download manager, so the token is never stored anywhere.
 */

internal const val LOCAL_MODEL_PART_FILE = "model.task.part"
private const val PREFS = "local_model_download"
private const val KEY_ID = "download_id"
private const val KEY_MODEL = "model_id"

internal sealed class LocalDownloadState {
    data object Idle : LocalDownloadState()
    data class Running(val label: String, val percent: Int?, val waitingForNetwork: Boolean) : LocalDownloadState()
    data class Failed(val message: String) : LocalDownloadState()
    data class Installed(val label: String) : LocalDownloadState()
}

internal class LocalModelDownloads(private val context: Context, private val http: OkHttpClient, private val store: LocalModelStore) {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val manager get() = context.getSystemService(DownloadManager::class.java)

    /** Starts downloading [choice] (replacing any download under way). Null once started, otherwise what to tell the user. */
    suspend fun start(choice: LocalModelChoice, hfToken: String?): String? = withContext(Dispatchers.IO) {
        val dir = store.downloadDir ?: return@withContext "Stockage du téléphone indisponible."
        val free = dir.apply { mkdirs() }.usableSpace
        if (free in 1 until choice.bytes + 50_000_000L) {
            return@withContext "Pas assez de place : il faut ${choice.sizeLabel} libres, il reste ${free / 1_000_000L} Mo."
        }
        val token = hfToken?.trim().orEmpty()
        if (choice.needsHfToken && token.isEmpty()) return@withContext "Collez d’abord votre jeton Hugging Face."
        val url = if (choice.needsHfToken) signedUrl(choice, token).getOrElse { return@withContext it.message } else choice.downloadUrl
        cancel()
        File(dir, LOCAL_MODEL_PART_FILE).delete()
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Jarvis : ${choice.label}")
                .setDescription("Modèle pour l’IA hors ligne")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, LOCAL_MODEL_DIR, LOCAL_MODEL_PART_FILE)
            val id = manager.enqueue(request)
            prefs.edit().putLong(KEY_ID, id).putString(KEY_MODEL, choice.id).apply()
            null
        } catch (e: Exception) {
            "Téléchargement impossible : ${e.message ?: e.javaClass.simpleName}."
        }
    }

    /** Asks Hugging Face, with the token, where the gated file really is; a refusal becomes a message the user can act on. */
    private fun signedUrl(choice: LocalModelChoice, token: String): Result<String> = try {
        val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val request = Request.Builder().url(choice.downloadUrl).head().header("Authorization", "Bearer $token").build()
        client.newCall(request).execute().use { response ->
            val location = response.header("Location")
            when {
                response.isRedirect && location != null -> Result.success(response.request.url.resolve(location)?.toString() ?: location)
                response.code == 401 -> Result.failure(IOException("Jeton Hugging Face refusé : vérifiez-le (un jeton « Read » suffit)."))
                response.code == 403 -> Result.failure(IOException("Accès refusé : acceptez d’abord la licence sur la page du modèle, avec le compte de ce jeton."))
                else -> Result.failure(IOException("Hugging Face ne répond pas comme prévu (HTTP ${response.code})."))
            }
        }
    } catch (_: IOException) {
        Result.failure(IOException("Hugging Face injoignable : vérifiez la connexion."))
    }

    /** Where things stand; finishes the install when the download manager is done. Cheap enough to poll every second. */
    suspend fun state(): LocalDownloadState = withContext(Dispatchers.IO) {
        val id = prefs.getLong(KEY_ID, -1L)
        val choice = localModelChoice(prefs.getString(KEY_MODEL, null))
        if (id < 0 || choice == null) return@withContext LocalDownloadState.Idle
        val row = try {
            manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) null else Triple(
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                )
            }
        } catch (_: Exception) { null }
        if (row == null) {
            forget()
            return@withContext LocalDownloadState.Failed("Le téléchargement a été annulé.")
        }
        val (status, done, reason) = row
        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> finish(id, choice)
            DownloadManager.STATUS_FAILED -> {
                cancel()
                LocalDownloadState.Failed(failureMessage(reason))
            }
            else -> LocalDownloadState.Running(
                choice.label,
                (done * 100 / choice.bytes).toInt().coerceIn(0, 100).takeIf { done > 0 },
                status == DownloadManager.STATUS_PAUSED,
            )
        }
    }

    @Synchronized
    private fun finish(id: Long, choice: LocalModelChoice): LocalDownloadState {
        // The completion broadcast and the settings screen can both get here: whoever comes second finds the work done.
        if (prefs.getLong(KEY_ID, -1L) != id) {
            return if (store.installed()) LocalDownloadState.Installed(store.label() ?: choice.label) else LocalDownloadState.Idle
        }
        val part = store.downloadDir?.let { File(it, LOCAL_MODEL_PART_FILE) }
        forget()
        if (part == null || !part.isFile) return LocalDownloadState.Failed("Fichier téléchargé introuvable.")
        val head = readHead(part) ?: ByteArray(0)
        val problem = checkDownloadedModel(choice, part.length(), head) { sha256Of(part) }
            ?: store.installDownloaded(part, choice.label)
        if (problem != null) {
            part.delete()
            return LocalDownloadState.Failed(problem)
        }
        return LocalDownloadState.Installed(choice.label)
    }

    /** Stops the download under way, if any, and deletes what it had fetched. */
    fun cancel() {
        val id = prefs.getLong(KEY_ID, -1L)
        if (id >= 0) try { manager.remove(id) } catch (_: Exception) { }
        forget()
    }

    private fun forget() {
        prefs.edit().remove(KEY_ID).remove(KEY_MODEL).apply()
    }

    private fun failureMessage(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Téléchargement arrêté : plus assez de place sur le téléphone."
        DownloadManager.ERROR_CANNOT_RESUME, DownloadManager.ERROR_HTTP_DATA_ERROR -> "Téléchargement interrompu : relancez-le."
        in 400..599 -> "Téléchargement refusé par le serveur (HTTP $reason). Pour un modèle Gemma, relancez-le : le lien d’accès expire."
        else -> "Téléchargement échoué : relancez-le."
    }
}

private fun sha256Of(file: File): String? = try {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
} catch (_: IOException) { null }

/** Installs the model as soon as the download manager is done, even with Jarvis's settings closed. */
class LocalModelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val app = context.applicationContext as? com.jarvis.android.JarvisApp ?: return
        val result = goAsync()
        Thread {
            try {
                kotlinx.coroutines.runBlocking { app.container.localModelDownloads.state() }
            } catch (_: Exception) {
            } finally {
                result.finish()
            }
        }.start()
    }
}
