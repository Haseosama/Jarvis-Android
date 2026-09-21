package com.jarvis.android.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** A published version of the app on GitHub, with the APK to install. */
internal data class ReleaseInfo(val version: String, val notes: String, val apkName: String, val apkUrl: String, val apkSize: Long)

/** The numbers of a version name ("v0.4.0-dev" gives [0, 4, 0]); empty when there is none. */
internal fun versionNumbers(name: String): List<Int> =
    Regex("""\d+""").findAll(name.trim().removePrefix("v").substringBefore('-')).map { it.value.toIntOrNull() ?: 0 }.toList()

/** True when [candidate] is a higher version than [current]; a missing number counts as 0 (0.4 = 0.4.0). */
internal fun isNewer(candidate: String, current: String): Boolean {
    val a = versionNumbers(candidate)
    val b = versionNumbers(current)
    if (a.isEmpty()) return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/**
 * Reads the answer of GitHub's "latest release" endpoint. The APK is the asset ending in .apk; a debug build (package suffix .dev, signed
 * with the debug key) takes the one whose name says debug or dev, a release build the other one, and either falls back to the first APK.
 */
internal fun parseRelease(json: String, debugBuild: Boolean): ReleaseInfo? {
    val root = try { Json.parseToJsonElement(json) as? JsonObject } catch (_: Exception) { null } ?: return null
    val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val assets = (root["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter {
        it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true &&
            it["browser_download_url"]?.jsonPrimitive?.contentOrNull != null
    }
    if (assets.isEmpty()) return null
    fun isDebugName(o: JsonObject) = o["name"]!!.jsonPrimitive.content.lowercase().let { "debug" in it || "dev" in it }
    val asset = assets.firstOrNull { isDebugName(it) == debugBuild } ?: assets.first()
    return ReleaseInfo(
        version = tag.removePrefix("v"),
        notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
        apkName = asset["name"]!!.jsonPrimitive.content,
        apkUrl = asset["browser_download_url"]!!.jsonPrimitive.content,
        apkSize = asset["size"]?.jsonPrimitive?.longOrNull ?: -1L,
    )
}

/** The outcome of asking GitHub whether a newer version exists. */
internal sealed interface UpdateCheck {
    data class Available(val release: ReleaseInfo) : UpdateCheck
    data object UpToDate : UpdateCheck
    data class Failed(val reason: String) : UpdateCheck
}

/** Updating the app from the releases of its GitHub repository: look for a newer one, download its APK, hand it to Android's installer. */
internal class AppUpdater(private val context: Context, private val http: OkHttpClient) {
    companion object {
        const val REPO = "Haseosama/Jarvis-Android"
        private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    }

    val debugBuild: Boolean get() = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun installedVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }

    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()).execute().use { response ->
                when {
                    response.code == 404 -> UpdateCheck.Failed(tr("Aucune version n’a encore été publiée sur GitHub."))
                    response.code == 403 || response.code == 429 -> UpdateCheck.Failed(tr("GitHub limite les requêtes pour l’instant : réessayez plus tard."))
                    !response.isSuccessful -> UpdateCheck.Failed(trf("GitHub a répondu par une erreur ({0}).", response.code))
                    else -> {
                        val release = parseRelease(response.body?.string().orEmpty(), debugBuild)
                        when {
                            release == null -> UpdateCheck.Failed(tr("La dernière version publiée ne contient pas d’APK."))
                            isNewer(release.version, installedVersion()) -> UpdateCheck.Available(release)
                            else -> UpdateCheck.UpToDate
                        }
                    }
                }
            }
        } catch (_: java.io.IOException) {
            UpdateCheck.Failed(tr("Pas de connexion à GitHub."))
        }
    }

    /** Downloads the APK into the cache and checks that it is a file of this very app; returns the file or a message. */
    suspend fun download(release: ReleaseInfo, onProgress: (Float) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "update.apk")
        try {
            http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    return@withContext Result.failure(IllegalStateException(trf("Le téléchargement a échoué ({0}).", response.code)))
                }
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
                var done = 0L
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (total > 0 && done != total) return@withContext Result.failure(IllegalStateException(tr("Le téléchargement est incomplet.")))
            }
        } catch (_: java.io.IOException) {
            target.delete()
            return@withContext Result.failure(IllegalStateException(tr("Le téléchargement a été interrompu.")))
        }
        val archive = context.packageManager.getPackageArchiveInfo(target.path, 0)
        when {
            archive == null -> { target.delete(); Result.failure(IllegalStateException(tr("Le fichier téléchargé n’est pas une application valide."))) }
            archive.packageName != context.packageName -> {
                target.delete()
                Result.failure(IllegalStateException(trf("Cet APK est celui d’une autre application ({0}).", archive.packageName)))
            }
            else -> Result.success(target)
        }
    }

    /** True when Android lets Jarvis install apps (the "install unknown apps" switch of its settings page). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the settings page where the user allows Jarvis to install apps. */
    fun openInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Hands [apk] to Android's package installer through a session, which reports what happens (see [UpdateInstallReceiver]) instead of
     * failing silently as a "view this file" intent can on some phones. The system then asks the user to confirm. Null when the session
     * was committed, otherwise what went wrong.
     */
    fun install(apk: File): String? {
        if (!canInstall()) return tr("Autorisez d’abord Jarvis à installer des applications, puis touchez « Installer ».")
        UpdateInstall.status.value = null
        return try {
            val installer = context.packageManager.packageInstaller
            // a window that was cancelled leaves its session open: drop the old ones
            for (old in installer.mySessions) try { installer.abandonSession(old.sessionId) } catch (_: Exception) { }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("update.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                // The system adds the status to this intent, so it must be mutable (and explicit).
                val callback = PendingIntent.getBroadcast(
                    context, id, Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_UPDATE_INSTALL),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
            null
        } catch (e: Exception) {
            Log.w("AppUpdate", "install session failed", e)
            trf("Impossible de lancer l’installation : {0}", e.message ?: e.javaClass.simpleName)
        }
    }
}
