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
internal const val LOCAL_MODEL_LM_FILE = "model.litertlm"
internal const val LOCAL_MODEL_MIN_BYTES = 40_000_000L        // the smallest usable Gemma .task bundles are tens of MB
internal const val LOCAL_MODEL_MAX_BYTES = 4_500_000_000L     // a generous ceiling: nothing this app would ask for is bigger
internal const val LOCAL_MODEL_HEAD_BYTES = 8

/**
 * The name a model file is kept under, from its first bytes: a MediaPipe .task bundle is a zip archive ("PK"), a LiteRT-LM file starts with
 * "LITERTLM". Null for anything else, so a plain size check never accepts an unrelated file. LocalLlm.kt picks the engine from that name.
 */
internal fun modelFileName(firstBytes: ByteArray): String? = when {
    firstBytes.size >= 2 && firstBytes[0] == 'P'.code.toByte() && firstBytes[1] == 'K'.code.toByte() -> LOCAL_MODEL_FILE
    firstBytes.size >= 8 && String(firstBytes, 0, 8, Charsets.US_ASCII) == "LITERTLM" -> LOCAL_MODEL_LM_FILE
    else -> null
}

internal fun looksLikeModelFile(size: Long, firstBytes: ByteArray): Boolean =
    size in LOCAL_MODEL_MIN_BYTES..LOCAL_MODEL_MAX_BYTES && modelFileName(firstBytes) != null

/** The first bytes of [file] (fewer if it is shorter), or null if it cannot be read. InputStream.readNBytes needs Android 13. */
internal fun readHead(file: File, count: Int = LOCAL_MODEL_HEAD_BYTES): ByteArray? = try {
    file.inputStream().use { input ->
        val buf = ByteArray(count)
        var n = 0
        while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        buf.copyOf(n)
    }
} catch (_: java.io.IOException) { null }

internal const val LOCAL_MODEL_LABEL_FILE = "model.label"

/**
 * [dir] holds an imported model; [downloadDir] (the app's own external files, where Android's download manager can write) holds one
 * picked from the catalogue (see LocalModelCatalog.kt). Downloads stay there rather than being copied in, so a 2.6 GB model never needs
 * twice its size free. Only one model is installed at a time: installing either kind removes the other.
 */
internal class LocalModelStore(private val dir: File, val downloadDir: File? = null) {
    constructor(context: Context) : this(File(context.filesDir, LOCAL_MODEL_DIR), context.getExternalFilesDir(LOCAL_MODEL_DIR))

    private val candidates: List<File> get() =
        listOfNotNull(dir, downloadDir).flatMap { d -> listOf(File(d, LOCAL_MODEL_FILE), File(d, LOCAL_MODEL_LM_FILE)) }

    /** The installed model (a .task or a .litertlm file); a path that does not exist when there is none. */
    val file: File get() = candidates.firstOrNull { it.isFile } ?: File(dir, LOCAL_MODEL_FILE)

    fun installed(): Boolean = file.isFile && file.length() >= LOCAL_MODEL_MIN_BYTES

    /** The installed model's size in whole MB, or null when there is none. */
    fun sizeMb(): Long? = file.takeIf { it.isFile }?.length()?.let { it / 1_000_000L }

    /** The catalogue name of the installed model, or null for an imported file (or none). */
    fun label(): String? =
        if (!installed()) null else try { File(dir, LOCAL_MODEL_LABEL_FILE).readText().trim().takeIf { it.isNotEmpty() } } catch (_: java.io.IOException) { null }

    private fun clearModels() {
        candidates.forEach { it.delete() }
        File(dir, LOCAL_MODEL_LABEL_FILE).delete()
    }

    /** Takes a finished, checked download in as the model (same storage, so a rename). Null on success. */
    fun installDownloaded(part: File, label: String): String? {
        val d = downloadDir ?: return "Stockage du téléphone indisponible."
        val name = readHead(part)?.let { modelFileName(it) } ?: return "Le fichier reçu n’est pas un modèle."
        clearModels()
        if (!part.renameTo(File(d, name))) return "Installation du modèle impossible."
        dir.mkdirs()
        File(dir, LOCAL_MODEL_LABEL_FILE).writeText(label)
        return null
    }

    /** Copies [source] in as the model, after checking it looks like a real .task or .litertlm file. Null on success. */
    fun import(source: File): String? {
        val size = source.length()
        val head = readHead(source) ?: return "Fichier illisible."
        val name = modelFileName(head)
        if (name == null || !looksLikeModelFile(size, head)) {
            return "Ce fichier ne ressemble pas à un modèle .task ou .litertlm (attendu : quelques centaines de Mo à quelques Go)."
        }
        return try {
            dir.mkdirs()
            val tmp = File(dir, "import.tmp")
            source.copyTo(tmp, overwrite = true)
            clearModels()
            val target = File(dir, name)
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            null
        } catch (_: java.io.IOException) {
            File(dir, "import.tmp").delete()
            "Copie du modèle impossible (place manquante sur le téléphone ?)."
        }
    }

    fun remove() {
        dir.deleteRecursively()
        downloadDir?.listFiles()?.forEach { it.delete() }
    }
}
