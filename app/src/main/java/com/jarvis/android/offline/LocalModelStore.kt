package com.jarvis.android.offline

import android.content.Context
import java.io.File

/*
 * The local model, for a real offline conversation instead of the fixed commands alone (see OfflineIntents.kt). The user either picks one
 * in Jarvis's list and lets it download (LocalModelCatalog.kt, LocalModelDownloads.kt), or imports a .task file fetched elsewhere — the
 * same way a custom wake-word model, trained outside the app, is imported. Nothing about the model or its use ever leaves the phone.
 */

internal const val LOCAL_MODEL_DIR = "local_llm"
internal const val LOCAL_MODEL_FILE = "model.task"
internal const val LOCAL_MODEL_MIN_BYTES = 40_000_000L        // the smallest usable Gemma .task bundles are tens of MB
internal const val LOCAL_MODEL_MAX_BYTES = 4_500_000_000L     // a generous ceiling: nothing this app would ask for is bigger

/** A MediaPipe .task bundle is a zip archive (model, tokenizer, metadata together): a plain size check would accept an unrelated file. */
internal fun looksLikeTaskBundle(size: Long, firstBytes: ByteArray): Boolean =
    size in LOCAL_MODEL_MIN_BYTES..LOCAL_MODEL_MAX_BYTES && firstBytes.size >= 2 && firstBytes[0] == 'P'.code.toByte() && firstBytes[1] == 'K'.code.toByte()

/** The first two bytes of [file] (fewer if it is shorter), or null if it cannot be read. InputStream.readNBytes needs Android 13. */
internal fun readHead(file: File): ByteArray? = try {
    file.inputStream().use { input ->
        val buf = ByteArray(2)
        var n = 0
        while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        buf.copyOf(n)
    }
} catch (_: java.io.IOException) { null }

internal const val LOCAL_MODEL_LABEL_FILE = "model.label"

/**
 * [dir] holds an imported model; [downloadDir] (the app's own external files, where Android's download manager can write) holds one
 * picked from the catalogue (see LocalModelCatalog.kt). Downloads stay there rather than being copied in, so a 1.6 GB model never needs
 * twice its size free. Only one model is installed at a time: installing either kind removes the other.
 */
internal class LocalModelStore(private val dir: File, val downloadDir: File? = null) {
    constructor(context: Context) : this(File(context.filesDir, LOCAL_MODEL_DIR), context.getExternalFilesDir(LOCAL_MODEL_DIR))

    private val importedFile: File get() = File(dir, LOCAL_MODEL_FILE)
    private val downloadedFile: File? get() = downloadDir?.let { File(it, LOCAL_MODEL_FILE) }

    val file: File get() = importedFile.takeIf { it.isFile } ?: downloadedFile?.takeIf { it.isFile } ?: importedFile

    fun installed(): Boolean = file.isFile && file.length() >= LOCAL_MODEL_MIN_BYTES

    /** The catalogue name of the installed model, or null for an imported file (or none). */
    fun label(): String? =
        if (!installed()) null else try { File(dir, LOCAL_MODEL_LABEL_FILE).readText().trim().takeIf { it.isNotEmpty() } } catch (_: java.io.IOException) { null }

    /** Takes a finished, checked download in as the model (same storage, so a rename). Null on success. */
    fun installDownloaded(part: File, label: String): String? {
        val target = downloadedFile ?: return "Stockage du téléphone indisponible."
        importedFile.delete()
        target.delete()
        if (!part.renameTo(target)) return "Installation du modèle impossible."
        dir.mkdirs()
        File(dir, LOCAL_MODEL_LABEL_FILE).writeText(label)
        return null
    }

    /** The installed model's size in whole MB, or null when there is none. */
    fun sizeMb(): Long? = file.takeIf { it.isFile }?.length()?.let { it / 1_000_000L }

    /** Copies [source] in as the model, after checking it looks like a real .task bundle. Null on success. */
    fun import(source: File): String? {
        val size = source.length()
        val head = readHead(source) ?: return "Fichier illisible."
        if (!looksLikeTaskBundle(size, head)) {
            return "Ce fichier ne ressemble pas à un modèle .task (attendu : quelques centaines de Mo, un fichier zip)."
        }
        return try {
            dir.mkdirs()
            val tmp = File(dir, "$LOCAL_MODEL_FILE.tmp")
            source.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(importedFile)) { importedFile.writeBytes(tmp.readBytes()); tmp.delete() }
            downloadedFile?.delete()
            File(dir, LOCAL_MODEL_LABEL_FILE).delete()
            null
        } catch (_: java.io.IOException) {
            "Copie du modèle impossible (place manquante sur le téléphone ?)."
        }
    }

    fun remove() {
        dir.deleteRecursively()
        downloadDir?.listFiles()?.forEach { it.delete() }
    }
}
